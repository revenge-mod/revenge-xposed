package io.github.revenge.xposed.tweaks.plugins

import io.github.revenge.Logger
import io.github.revenge.logger
import io.github.revenge.plugins.Plugin
import io.github.revenge.plugins.PluginScope
import io.github.revenge.plugins.Version
import io.github.revenge.xposed.api.HostScope
import io.github.revenge.xposed.api.callJSMethod
import io.github.revenge.xposed.tweaks.plugins.internal.InternalPluginFlags
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.*
import java.io.File

private inline val loaded get() = pluginRegistry.loaded

internal class LoadedPlugin(
    val plugin: Plugin,
    val scope: PluginScopeImpl,
    /** Collector persisting flag changes + broadcasting them to JS. Canceled on stop. */
    val persistJob: Job,
    /** Collector syncing native errors + broadcasting them to JS. Canceled on stop. */
    val errorSyncJob: Job,
) {
    /** Whether the native side is currently running. */
    @Volatile
    var started = true
}

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

    override fun stop() {
        stopPlugin(manifest.id)
    }

    override fun disable() {
        disablePlugin(manifest.id)
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

    val plugin = factory.builder.build(manifest)
    val flags = MutableStateFlow(PluginStatesStore.loadPluginFlags(manifest.id) ?: emptySet())

    // Turn on plugins that are essential or on by default, unless a saved flag already says so.
    if (
        PluginFlags.ENABLED !in flags.value &&
        (InternalPluginFlags.ESSENTIAL in factory.internalFlags ||
                InternalPluginFlags.ENABLED_BY_DEFAULT in factory.internalFlags)
    ) {
        flags.value += PluginFlags.ENABLED
    }

    if (late) flags.value += PluginFlags.STARTED_LATE

    val scope = PluginScopeImpl(host, plugin, flags)

    // `drop(1)` skips the initial load so we don't re-persist the values we just loaded.
    val persistJob = flags.drop(1).onEach { newFlags ->
        PluginStatesStore.updatePluginFlags(manifest, newFlags)

        runCatching {
            host.callJSMethod(
                "revenge.plugins.states.update",
                listOf(manifest.id, newFlags.toJSPayload())
            )
        }
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

/** Stops a running plugin, taking every dependent that's linked to it down first. */
internal fun stopPlugin(pluginId: String) {
    if (pluginId !in loaded) return

    // Snapshot since the cascade mutates [loaded].
    val dependents = loaded.entries.mapNotNull { (dependentId, dependent) ->
        val dep = dependent.scope.manifest.dependencies[pluginId] ?: return@mapNotNull null
        val unlinked = pluginRegistry.factories[dependentId]?.unsatisfiedOptionalDependencies.orEmpty()
        when {
            !dep.optional -> dependentId to "required"
            pluginId !in unlinked -> dependentId to "linked optional"
            else -> null
        }
    }

    for ((dependentId, edge) in dependents) {
        if (dependentId !in loaded) continue // already stopped by a deeper cascade
        pluginLog.i("Stopping $dependentId: its $edge dependency $pluginId is stopping")
        stopPlugin(dependentId)
    }

    val entry = loaded.remove(pluginId) ?: return
    try {
        if (entry.started) entry.plugin.stop(entry.scope)
    } catch (e: Throwable) {
        entry.scope.errors.tryEmit(e)
        pluginLog.e("Plugin $pluginId threw in stop()", e)
    } finally {
        entry.persistJob.cancel()
        entry.errorSyncJob.cancel()
    }
}

/**
 * Disables and stops a plugin. Required dependents (transitively) lose their persisted enabled state too,
 * since they can't run without this plugin anymore. Linked optionals only stop, they can load fine next start.
 *
 * Throws when the plugin is essential.
 */
internal fun disablePlugin(pluginId: String) {
    if (isEssential(pluginId)) throw PluginSystemError(
        PluginErrorCodes.NOT_ALLOWED,
        "Plugin $pluginId is essential and cannot be disabled",
    )

    val toDisable = mutableSetOf(pluginId)
    // Read all manifests, an unloaded plugin must be disabled as well.
    val knownManifests = pluginRegistry.knownManifests()
    var changed = true
    while (changed) {
        changed = false
        for ((dependentId, manifest) in knownManifests) {
            if (dependentId in toDisable) continue
            val requiredOnDisabled = manifest.dependencies.any { (depId, dep) ->
                !dep.optional && depId in toDisable
            }
            if (requiredOnDisabled) {
                if (isEssential(dependentId)) {
                    throw PluginSystemError(
                        PluginErrorCodes.NOT_ALLOWED,
                        "Plugin '$pluginId' is depended by '$dependentId' which is essential, so it cannot be disabled"
                    )
                }

                toDisable += dependentId
                changed = true
            }
        }
    }

    PluginStatesStore.batchSave {
        for (id in toDisable) {
            PluginStatesStore.states?.setPluginFlags(id, emptySet())
            // Sync correct data to running instances to broadcast updates to JS as well.
            loaded[id]?.let { it.scope.flags.value = emptySet() }
        }
    }

    stopPlugin(pluginId)
}

internal fun enablePlugin(pluginId: String) {
    PluginStatesStore.states?.setPluginFlags(pluginId, setOf(PluginFlags.ENABLED))
    PluginStatesStore.writeNow()
}

private fun isEssential(pluginId: String): Boolean =
    pluginRegistry.factories[pluginId]?.let { InternalPluginFlags.ESSENTIAL in it.internalFlags } ?: false

private fun isPluginEnabled(pluginId: String, factory: PluginFactory?): Boolean {
    loaded[pluginId]?.let { return PluginFlags.ENABLED in it.scope.flags.value }
    val states = PluginStatesStore.states
    if (states?.isPluginEnabledInSaved(pluginId) == true) return true
    if (factory == null) return false
    return InternalPluginFlags.ESSENTIAL in factory.internalFlags ||
            (InternalPluginFlags.ENABLED_BY_DEFAULT in factory.internalFlags &&
                    states?.hasPluginInSaved(pluginId) != true)
}

internal fun clearPersistedState(pluginId: String) {
    PluginStatesStore.states?.removePlugin(pluginId)
    PluginStatesStore.writeNow()
}

internal fun unsatisfiedDependenciesMessage(pluginId: String, problems: List<Map<String, Any?>>): String {
    val details = problems.joinToString(", ") { p ->
        val installed = p["installed"] as? String
        val disabled = if (installed != null && p["enabled"] == false) ", disabled" else ""
        "\"${p["id"]}\" (requires ${p["required"]}, installed: ${installed ?: "none"}$disabled)"
    }
    return "Cannot enable plugin \"$pluginId\": unsatisfied dependencies: $details"
}

internal fun PluginFactory.dependencyProblems(
    factories: Map<String, PluginFactory>,
): List<Map<String, Any?>> = manifest.dependencies.mapNotNull { (depId, dep) ->
    if (dep.optional) return@mapNotNull null

    fun problem(installed: Version?, depEnabled: Boolean) = mapOf(
        "id" to depId,
        "required" to dep.version.toString(),
        "installed" to installed?.toString(),
        "enabled" to depEnabled,
    )

    val depFactory = factories[depId]
    val installed = depFactory?.manifest?.version
    when {
        installed == null -> problem(null, false)
        !dep.version.satisfies(installed) -> problem(installed, isPluginEnabled(depId, depFactory))
        !isPluginEnabled(depId, depFactory) -> problem(installed, false)
        else -> null
    }
}
