package io.github.revenge.xposed.tweaks.plugins

import io.github.revenge.Logger
import io.github.revenge.logger
import io.github.revenge.plugins.Plugin
import io.github.revenge.plugins.PluginScope
import io.github.revenge.xposed.api.HostScope
import io.github.revenge.xposed.api.callJSMethod
import io.github.revenge.xposed.tweaks.plugins.internal.InternalPluginFlags
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.io.File

private inline val loaded get() = pluginRegistry.loaded

internal class LoadedPlugin(
    val plugin: Plugin,
    val scope: PluginScopeImpl,
    /** Collector persisting flag changes + broadcasting them to JS. Canceled on stop. */
    val persistJob: Job,
    /** Collector syncing native errors + broadcasting them to JS. Canceled on stop. */
    val errorSyncJob: Job,
)

internal class PluginScopeImpl(
    tweakScope: HostScope,
    plugin: Plugin,
    /** Lifecycle flags. */
    val flags: MutableStateFlow<Set<PluginFlags>>,
) : PluginScope, HostScope by tweakScope {
    override val log: Logger = logger("plugin:${plugin.manifest.id}")
    override val manifest = plugin.manifest

    override val storageDir: File by lazy {
        pluginStorageRoot(appInfo.dataDir).resolve(manifest.id).apply {
            mkdirs()
            if (!isDirectory || !canWrite()) throw PluginSystemError(
                PluginErrorCodes.STORAGE_FAILED,
                "Plugin storage directory is not writable: $this",
            )
        }
    }

    // Arbitrary limit of 1000 errors, should be enough in most cases
    override val errors = MutableSharedFlow<Throwable>(replay = 1000)

    override val enabled get() = PluginFlags.ENABLED in flags.value
    override val startedLate get() = PluginFlags.STARTED_LATE in flags.value

    override fun requireReload() {
        flags.value += PluginFlags.PENDING_RELOAD
    }

    // Fire-and-forget. No plugin code should run after it asks to be stopped.
    override fun stop() {
        pluginJobScope.launch { stopPlugin(manifest.id) }
    }

    override fun disable() {
        pluginJobScope.launch { disablePlugin(manifest.id) }
    }
}

/**
 * Builds and starts a plugin. Throws if the plugin can't be built or started.
 *
 * Exceptions thrown by the plugin's own `start()` are captured into its scope's errors instead.
 */
context(host: HostScope)
internal fun loadPlugin(
    factory: PluginFactory,
    /** Loading mid-session (user enable) instead of at boot. */
    late: Boolean = false,
): LoadedPlugin {
    val manifest = factory.manifest
    val graph = pluginRegistry.dependencies

    // Running satisfied dependencies. Some dependencies may've been stopped prior to this.
    val chain = graph.satisfiedDependencies(manifest.id).filterTo(mutableSetOf()) { it in loaded }

    // *Should* be unreachable, since disabling a plugin cascades to its required dependents.
    // Regardless, check is required for safety so the plugin won't immediately blow up on missing dependencies.
    val missing = graph.requiredDependencies(manifest.id) - chain
    if (missing.isNotEmpty()) throw PluginSystemError(
        PluginErrorCodes.DEPENDENCY_MISSING,
        "Plugin '${manifest.id}' cannot start, required dependencies are not running: ${missing.joinToString()}",
    )

    val plugin = factory.build(chain)
    // Use the boot flags as it's what's currently running this session.
    val flags = PluginStatesStore.boot.flagsOf(manifest.id)

    // Turn on plugins that are essential or on by default, unless the slot already says so.
    if (
        PluginFlags.ENABLED !in flags.value &&
        (InternalPluginFlags.ESSENTIAL in factory.internalFlags ||
                InternalPluginFlags.ENABLED_BY_DEFAULT in factory.internalFlags)
    ) {
        flags.value += PluginFlags.ENABLED
    }

    if (late) flags.value += PluginFlags.STARTED_LATE

    val scope = PluginScopeImpl(host, plugin, flags)
    val bootSlot = PluginStatesStore.boot

    // `drop(1)` skips the initial load so we don't re-persist the values we just loaded.
    val persistJob = flags.drop(1).onEach { newFlags ->
        bootSlot.write(manifest.id, newFlags)
        sendStateToJS(bootSlot.id, manifest.id, newFlags)
    }.launchIn(pluginJobScope)

    val errorSyncJob = scope.errors.onEach {
        runCatching {
            scope.callJSMethod(PluginEvents.PLUGIN_ERRORED, listOf(manifest.id, scope.errorsJSPayload))
        }
    }.launchIn(pluginJobScope)

    // The plugin's own code runs here, so anything it throws belongs to the plugin, not the loader.
    try {
        plugin.start(scope)
    } catch (e: Throwable) {
        scope.errors.tryEmit(e)
        pluginLog.e("Plugin ${manifest.id} threw in start()", e)
    }

    val entry = LoadedPlugin(plugin, scope, persistJob, errorSyncJob)
    loaded[manifest.id] = entry
    pluginLog.i("Started plugin: ${manifest.id}")

    return entry
}

/**
 * Stops a running plugin, cascading to any running dependents that require it.
 * 
 * JS is asked to stop first, so it can stop its own half and clean up any state before native tears down the plugin.
 * If JS refuses to stop after a timeout, native will still stop the plugin regardless.
 */
context(host: HostScope)
internal suspend fun stopPlugin(pluginId: String) {
    if (pluginId !in loaded) return

    if (!letJSStopPluginFirst(pluginId)) {
        // No JS couldn't stop in time, so take down everything manually.
        for (dependentId in chainedDependentsOf(pluginId)) {
            pluginLog.i("Stopping $dependentId: it chained $pluginId, which is stopping")
            stopNativePlugin(dependentId)
        }
    }

    stopNativePlugin(pluginId)
}

/** Running plugins whose class loader chains [pluginId] transitively. */
private fun chainedDependentsOf(pluginId: String): Set<String> {
    val found = mutableSetOf(pluginId)

    var changed = true
    while (changed) {
        changed = false
        for (id in loaded.keys) {
            if (id in found) continue
            if (pluginRegistry.factories[id]?.chainedDependencies.orEmpty().any { it in found }) {
                found += id
                changed = true
            }
        }
    }

    return found - pluginId
}

/** Tears down the plugin's native half. */
context(host: HostScope)
internal fun stopNativePlugin(pluginId: String) {
    val entry = loaded.remove(pluginId) ?: return
    try {
        entry.plugin.stop(entry.scope)
    } catch (e: Throwable) {
        entry.scope.errors.tryEmit(e)
        pluginLog.e("Plugin $pluginId threw in stop()", e)
    } finally {
        // stop() may have changed flags (requireReload), we need to sync before stopping the job.
        dispatchStateToJS(PluginStatesStore.bootSlotId, pluginId, entry.scope.flags.value)

        entry.persistJob.cancel()
        entry.errorSyncJob.cancel()
    }
}

/**
 * Starts a plugin's native half, stopping it first if it is already running.
 *
 * [loadPlugin] recomputes the class loader chain, so a restart also relinks when the dependency set changes.
 *
 * Unknown IDs are ignored because it may be a JS-only plugin.
 *
 * @throws PluginSystemError with [PluginErrorCodes.RELOAD_REQUIRED] when the stop asks for a reload. A plugin which couldn't undo itself must not be resumed.
 */
context(host: HostScope)
internal fun restartPlugin(pluginId: String) {
    val factory = pluginRegistry.factories[pluginId] ?: return

    stopNativePlugin(pluginId)

    if (PluginFlags.PENDING_RELOAD in PluginStatesStore.boot.flagsOf(pluginId).value) throw PluginSystemError(
        PluginErrorCodes.RELOAD_REQUIRED,
        "Plugin '$pluginId' could not stop cleanly and cannot start again before a reload",
    )

    try {
        loadPlugin(factory, late = true)
    } catch (e: Throwable) {
        throw PluginSystemError(
            PluginErrorCodes.LOAD_FAILED,
            e.message ?: "Failed to load plugin '$pluginId'",
            e,
        )
    }
    pluginRegistry.bootErrors.remove(pluginId)
}

/**
 * Disables and stops a plugin.
 *
 * Required dependents transitively lose their enabled state in the active slot too.
 * Optional dependents handled by JS.
 *
 * Running dependents are stopped by [stopPlugin].
 *
 * Throws when the plugin is essential.
 */
context(host: HostScope)
internal suspend fun disablePlugin(pluginId: String) {
    if (isEssential(pluginId)) throw PluginSystemError(
        PluginErrorCodes.NOT_ALLOWED,
        "Plugin $pluginId is essential and cannot be disabled",
    )

    // The known graph, not the loaded one: a plugin that never ran must still lose its enabled flag.
    val dependents = pluginRegistry.knownDependencies.requiredDependents(pluginId)
    for (dependentId in dependents) {
        if (isEssential(dependentId)) throw PluginSystemError(
            PluginErrorCodes.NOT_ALLOWED,
            "Plugin '$pluginId' is depended by '$dependentId' which is essential, so it cannot be disabled",
        )
    }

    val toDisable = dependents + pluginId

    PluginStatesStore.batchSave {
        for (id in toDisable) {
            val plugin = loaded[id]
            val currentFlags = plugin?.scope?.flags?.value ?: emptySet()
            val newFlags = currentFlags.filter { it.persistAfterDisable }.toSet()
            writeActiveSlotFlags(id, newFlags)

            // Sync running instances for the boot slot as well.
            loaded[id]?.let { it.scope.flags.value = newFlags }
        }
    }

    stopPlugin(pluginId)
}

internal fun pluginEnablementFlags(requiredByUser: Boolean) = buildSet {
    add(PluginFlags.ENABLED)
    if (requiredByUser) add(PluginFlags.REQUIRED_BY_USER)
}

context(host: HostScope)
internal fun enablePlugin(pluginId: String, requiredByUser: Boolean) =
    writeActiveSlotFlags(pluginId, pluginEnablementFlags(requiredByUser))

private fun isEssential(pluginId: String): Boolean =
    pluginRegistry.factories[pluginId]?.let { InternalPluginFlags.ESSENTIAL in it.internalFlags } ?: false

private fun isPluginEnabled(pluginId: String, factory: PluginFactory?): Boolean {
    loaded[pluginId]?.let { return PluginFlags.ENABLED in it.scope.flags.value }
    val active = PluginStatesStore.active
    if (active.isPluginEnabled(pluginId)) return true
    if (factory == null) return false
    return InternalPluginFlags.ESSENTIAL in factory.internalFlags ||
            (InternalPluginFlags.ENABLED_BY_DEFAULT in factory.internalFlags &&
                    !active.hasPlugin(pluginId))
}

/** Writes the slot the user chose, which is what applies on the next boot, and tells JS. */
context(host: HostScope)
internal fun writeActiveSlotFlags(pluginId: String, flags: Iterable<PluginFlags>) {
    val newFlags = flags.toSet()
    PluginStatesStore.active.write(pluginId, newFlags)
    dispatchStateToJS(PluginStatesStore.activeSlotId, pluginId, newFlags)
}

/** Drops the plugin's entry from the slot this boot runs on. */
internal fun clearBootSlotFlags(pluginId: String) = PluginStatesStore.boot.remove(pluginId)

internal fun unsatisfiedDependenciesMessage(pluginId: String, problems: List<Map<String, Any?>>): String {
    val details = problems.joinToString(", ") { p ->
        val installed = p["installed"] as? String
        val disabled = if (installed != null && p["enabled"] == false) ", disabled" else ""
        "\"${p["id"]}\" (requires ${p["required"]}, installed: ${installed ?: "none"}$disabled)"
    }
    return "Cannot enable plugin \"$pluginId\": unsatisfied dependencies: $details"
}

/** Required dependencies of [pluginId] that would prevent it from starting right now, as a JS payload. */
internal fun PluginFactory.dependencyProblemsToJSPayload(
    factories: Map<String, PluginFactory>,
): List<Map<String, Any?>> {
    val graph = pluginRegistry.dependencies
    val pluginId = manifest.id

    return manifest.dependencies.mapNotNull { (depId, dep) ->
        if (dep.optional) return@mapNotNull null

        val depFactory = factories[depId]
        val installed = depFactory?.manifest?.version
        val unsatisfied = graph.problem(pluginId, depId) != null
        val enabled = isPluginEnabled(depId, depFactory)

        if (!unsatisfied && enabled) return@mapNotNull null

        mapOf(
            "id" to depId,
            "required" to dep.version.toString(),
            "installed" to installed?.toString(),
            "enabled" to (installed != null && enabled),
        )
    }
}
