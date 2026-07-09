package io.github.revenge.xposed.tweaks.plugins

import io.github.revenge.Logger
import io.github.revenge.bridge.asDelegate
import io.github.revenge.logger
import io.github.revenge.plugins.Plugin
import io.github.revenge.plugins.PluginBuilder
import io.github.revenge.plugins.PluginManifest
import io.github.revenge.plugins.PluginScope
import io.github.revenge.xposed.api.HostScope
import io.github.revenge.xposed.api.callJSMethod
import io.github.revenge.xposed.api.registerNativeMethod
import io.github.revenge.xposed.tweak
import io.github.revenge.xposed.tweaks.plugins.internal.internalPlugins
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.io.File

private val loaded = mutableMapOf<String, LoadedPlugin>()
private lateinit var log: Logger

/**
 * Loads plugins and exposes `revenge.plugins.loader.*` bridge methods.
 */
val pluginLoader by tweak {
    io.github.revenge.xposed.tweaks.plugins.log = this@tweak.log

    val errors = mutableListOf<String>()
    // Boot-time load failures per plugin
    val bootErrors = mutableMapOf<String, String>()
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val external = discoverExternalPlugins(
        appInfo.dataDir,
        internalPlugins.mapTo(mutableSetOf()) { it.manifest.id },
        log,
    )
    val factories = (internalPlugins + external).associateBy { it.manifest.id }.toMutableMap()

    registerNativeMethod("revenge.plugins.getConstants") {
        mapOf(
            "storageRootPath" to pluginStorageRoot(appInfo.dataDir).absolutePath,
        )
    }

    registerNativeMethod("revenge.plugins.list") {
        factories.values.map { factory ->
            factory.toJSPayload(log, loaded[factory.manifest.id], bootErrors[factory.manifest.id])
        }
    }

    registerNativeMethod("revenge.plugins.install") {
        // Update flow: download -> stop JS -> stop native -> replace -> dispatch to JS: not pending reload? start native -> start JS
        val onBeforeReplace: suspend (String) -> Unit = { pluginId ->
            if (pluginId in factories) {
                runCatching {
                    callJSMethod("revenge.plugins.loader.stop", listOf(pluginId))
                }.onFailure { log.e("JS failed to stop plugin $pluginId before update", it) }

                stopPlugin(pluginId)
                forgetNativePluginLoader(pluginId)
            }
        }

        promptInstallPlugin(this, scope, { factories.keys.toSet() }, log, onBeforeReplace) { result ->
            val payload = result.fold(
                onSuccess = { factory ->
                    factories[factory.manifest.id] = factory
                    mapOf("error" to false, "plugin" to factory.toJSPayload(log))
                },
                onFailure = { e ->
                    mapOf("error" to (e.message ?: "Failed to install plugin"))
                },
            )

            // Hand the result to JS. It registers (and runs, if enabled) the plugin, or shows the error.
            scope.launch {
                runCatching {
                    callJSMethod("revenge.plugins.events.pluginInstalled", listOf(payload))
                }.onFailure { log.e("Failed to notify JS of plugin install", it) }
            }
        }
        null
    }

    registerNativeMethod("revenge.plugins.startNative") { args ->
        val argv = args.asDelegate()
        val pluginId by argv.string()

        val entry = loaded[pluginId]
        when {
            entry != null -> {
                // was stopped earlier, we need to start again.
                if (!entry.started) {
                    var errored = false
                    try {
                        entry.plugin.start(entry.scope)
                    } catch (e: Throwable) {
                        errored = true
                        entry.scope.errors.tryEmit(e)
                        log.e("Plugin $pluginId threw in start()", e)
                    }
                    entry.started = true
                    entry.scope.flags.value = buildSet {
                        addAll(entry.scope.flags.value)
                        add(PluginFlags.ENABLED)
                        add(PluginFlags.ENABLED_LATE)
                    }
                }
            }

            else -> factories[pluginId]?.let { factory ->
                // Not loaded at boot (disabled, or freshly installed).
                // Failures propagate to the JS caller as a bridge error.
                val loadedPlugin = loadPlugin(this, scope, factory)
                bootErrors.remove(pluginId)
                loadedPlugin.scope.flags.value += setOf(PluginFlags.ENABLED, PluginFlags.ENABLED_LATE)
            }
            // Unknown ID: a JS-side plugin with no native counterpart
        }
        null
    }

    registerNativeMethod("revenge.plugins.uninstall") { args ->
        val argv = args.asDelegate()
        val pluginId by argv.string()

        val factory = factories[pluginId]
        require(factory == null || InternalPluginFlags.INTERNAL !in factory.internalFlags) {
            "Plugin $pluginId is internal and cannot be uninstalled"
        }

        // Stop the native side, if it's running. Stopping also cancels the flag persistence job
        // So we can remove it without it re-persisting after
        stopPlugin(pluginId)
        factories.remove(pluginId)
        forgetNativePluginLoader(pluginId)

        requireValidPluginId(pluginId)

        File(externalPluginsRoot(appInfo.dataDir), pluginId).deleteRecursively()
        File(pluginStorageRoot(appInfo.dataDir), pluginId).deleteRecursively()

        PluginStatesStore.states?.removePlugin(pluginId)
        PluginStatesStore.writeNow()

        log.i("Uninstalled plugin: $pluginId")
        null
    }

    registerNativeMethod("revenge.plugins.setEnabled") { args ->
        val argv = args.asDelegate()
        val pluginId by argv.string()
        val enabled by argv.boolean()

        val factory = factories[pluginId]
        val entry = loaded[pluginId]

        if (enabled) {
            if (entry != null) {
                entry.scope.flags.value += PluginFlags.ENABLED
            } else {
                // Not loaded this session (or not managed natively)
                enablePlugin(pluginId)
            }
        } else {
            require(factory == null || InternalPluginFlags.ESSENTIAL !in factory.internalFlags) {
                "Plugin $pluginId is essential and cannot be disabled"
            }

            if (entry != null) {
                try {
                    entry.plugin.stop(entry.scope)
                } catch (e: Throwable) {
                    entry.scope.errors.tryEmit(e)
                    log.e("Plugin $pluginId threw in stop()", e)
                }
                entry.started = false
                entry.scope.flags.value -= PluginFlags.ENABLED
            } else {
                // Not loaded this session; just disable it.
                disablePlugin(pluginId)
            }
        }
        null
    }

    PluginStatesStore.batchSave {
        val states = PluginStatesStore.ensureLoaded(appInfo.dataDir)

        for (factory in internalPlugins + external) {
            val manifest = factory.manifest

            try {
                val essential = InternalPluginFlags.ESSENTIAL in factory.internalFlags
                val enabledByDefault = InternalPluginFlags.ENABLED_BY_DEFAULT in factory.internalFlags

                val shouldLoad = states.isPluginEnabled(manifest.id) ||
                        essential ||
                        (enabledByDefault && !states.hasPlugin(manifest.id))

                if (!shouldLoad) {
                    log.i("Skipping disabled plugin: ${manifest.id}")
                    continue
                }
                loadPlugin(this, scope, factory)
            } catch (e: Throwable) {
                val err = "Failed to load plugin ${manifest.id}: ${e.message}"
                errors += err
                bootErrors[manifest.id] = e.stackTraceToString()
                log.e(err, e)
            }
        }
    }

    log.i("Loaded ${loaded.size} plugins with ${errors.size} errors")
    for (err in errors) log.e(err)
}

/**
 * Builds and starts a plugin. Throws when the plugin cannot be built or wired.
 * Exceptions thrown by the plugin's own `start()` are captured into its scope's errors instead.
 */
private fun loadPlugin(
    tweakCtx: HostScope,
    scope: CoroutineScope,
    factory: PluginFactory,
): LoadedPlugin {
    val manifest = factory.manifest

    val plugin = factory.builder.build(manifest)
    val flags = MutableStateFlow(PluginStatesStore.loadPluginFlags(manifest.id) ?: emptySet())

    // Set flags to enabled if it's not already set.
    if (
        PluginFlags.ENABLED !in flags.value &&
        (InternalPluginFlags.ESSENTIAL in factory.internalFlags ||
                InternalPluginFlags.ENABLED_BY_DEFAULT in factory.internalFlags)
    ) {
        flags.value += PluginFlags.ENABLED
    }

    val pluginScope = PluginScopeImpl(tweakCtx, plugin, flags)

    // Persist future flag changes and broadcast state back to JS.
    // `drop(1)` skips the initial load so we don't re-persist the values we just loaded.
    val persistJob = flags.drop(1).onEach { newFlags ->
        PluginStatesStore.updatePluginFlags(manifest, newFlags)

        scope.launch {
            runCatching {
                tweakCtx.callJSMethod(
                    "revenge.plugins.states.update",
                    listOf(manifest.id, newFlags.toJSPayload())
                )
            }
        }
    }.launchIn(scope)

    val errorSyncJob = pluginScope.errors.onEach {
        pluginScope.callJSMethod(
            "revenge.plugins.events.pluginErrored",
            listOf(manifest.id, pluginScope.errorsJSPayload)
        )
    }.launchIn(scope)

    // Real plugin errors only happen here.
    try {
        plugin.start(pluginScope)
    } catch (e: Throwable) {
        pluginScope.errors.tryEmit(e)
        log.e("Plugin ${manifest.id} threw in start()", e)
    }

    val entry = LoadedPlugin(plugin, pluginScope, persistJob, errorSyncJob)
    loaded[manifest.id] = entry
    log.i("Started plugin: ${manifest.id}")

    return entry
}

private fun stopPlugin(pluginId: String) {
    loaded.remove(pluginId)?.let { entry ->
        entry.persistJob.cancel()
        entry.errorSyncJob.cancel()
        if (entry.started) {
            try {
                entry.plugin.stop(entry.scope)
            } catch (e: Throwable) {
                entry.scope.errors.tryEmit(e)
                log.e("Plugin $pluginId threw in stop()", e)
            }
        }
    }
}

private fun disablePlugin(pluginId: String) {
    if (loaded.contains(pluginId)) stopPlugin(pluginId)
    PluginStatesStore.states?.setPluginFlags(pluginId, emptySet())
    PluginStatesStore.writeNow()
}

private fun enablePlugin(pluginId: String) {
    PluginStatesStore.states?.setPluginFlags(pluginId, setOf(PluginFlags.ENABLED))
    PluginStatesStore.writeNow()
}

/**
 * A class that contains a [PluginBuilder], its corresponding [PluginManifest],
 * and any internal flags that should be applied to the plugin.
 *
 * Enough to make a plugin registerable.
 */
internal class PluginFactory(
    val builder: PluginBuilder,
    val manifest: PluginManifest,
    val internalFlags: Set<InternalPluginFlags> = emptySet(),
    /** Absolute path to the plugin's `dist.script` JS bundle. Its source is handed to JS via `getPlugins`. */
    val scriptPath: String? = null,
)

/** A started plugin together with its scope, so it can be stopped/restarted later. */
private class LoadedPlugin(
    val plugin: Plugin,
    val scope: PluginScopeImpl,
    /** Collector persisting flag changes + broadcasting them to JS. Canceled on uninstall/replace. */
    val persistJob: Job,
    /** Collector syncing native errors + broadcasting them to JS. Canceled on stop. */
    val errorSyncJob: Job,
) {
    /** Whether the native side is currently running (`start` called without a matching `stop`). */
    @Volatile
    var started = true
}

private class PluginScopeImpl(
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
            check(isDirectory && canWrite()) { "Plugin storage directory is not writable: $this" }
        }
    }

    // Arbitrary limit of 1000 errors, should be enough in most cases
    override val errors = MutableSharedFlow<Throwable>(replay = 1000)

    override val enabled get() = PluginFlags.ENABLED in flags.value
    override val enabledLate get() = PluginFlags.ENABLED_LATE in flags.value

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

private fun PluginFactory.toJSPayload(
    log: Logger,
    loaded: LoadedPlugin? = null,
    bootError: String? = null,
): Map<String, Any?> = mapOf(
    "manifest" to manifest.toMap(),
    "script" to readScript(log),
    "internal" to (InternalPluginFlags.INTERNAL in internalFlags),
    "essential" to (InternalPluginFlags.ESSENTIAL in internalFlags),
    "enabledByDefault" to (InternalPluginFlags.ENABLED_BY_DEFAULT in internalFlags),
    // Errors the native side has already hit (e.g. at boot, before JS was up).
    "errors" to buildList {
        bootError?.let(::add)
        loaded?.let { addAll(it.scope.errorsJSPayload) }
    },
)

private val PluginScope.errorsJSPayload get() = errors.replayCache.map { e -> e.stackTraceToString() }

/** Read the plugin's `dist.script` source (if any) for JS to evaluate. */
private fun PluginFactory.readScript(log: Logger): String? = scriptPath?.let { path ->
    try {
        File(path).readText()
    } catch (e: Throwable) {
        log.e("Failed to read plugin script for ${manifest.id}: $path", e)
        null
    }
}

private fun PluginManifest.toMap(): Map<String, Any?> = mapOf(
    "id" to id,
    "name" to name,
    "description" to description,
    "author" to author,
    "icon" to icon,
    "dependencies" to dependencies.map { mapOf("id" to it.id, "url" to it.url) },
)
