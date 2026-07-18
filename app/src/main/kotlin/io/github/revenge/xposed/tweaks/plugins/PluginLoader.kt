package io.github.revenge.xposed.tweaks.plugins

import io.github.revenge.Logger
import io.github.revenge.bridge.asDelegate
import io.github.revenge.logger
import io.github.revenge.plugins.*
import io.github.revenge.xposed.api.HostScope
import io.github.revenge.xposed.api.callJSMethod
import io.github.revenge.xposed.api.registerNativeAsyncMethod
import io.github.revenge.xposed.api.registerNativeMethod
import io.github.revenge.xposed.tweak
import io.github.revenge.xposed.tweaks.plugins.internal.internalPlugins
import io.github.revenge.xposed.tweaks.plugins.repos.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.withLock
import java.io.File

private class PluginRegistry {
    /** Registerable plugins by ID. */
    val factories = mutableMapOf<String, PluginFactory>()

    /**
     * Plugins that failed discovery this session (session-skip). Disjoint from [factories]
     * by construction: [add] drops the failure when an id gets a factory.
     */
    val discoveryFailures = mutableMapOf<String, DiscoveryFailure>()

    val loaded = mutableMapOf<String, LoadedPlugin>()

    /**
     * Versions updated on disk this session, applied at next boot.
     *
     * All updates only stage files on disk, requiring a reload.
     * This is to not break what's already working, a reload takes at max a few seconds.
     */
    val pendingUpdates = mutableMapOf<String, Version>()

    /** Boot-time load failures per plugin. */
    val bootErrors = mutableMapOf<String, PluginErrorInfo>()

    /** Manifests of every known plugin (factories + failures with a valid manifest). */
    fun knownManifests(): Map<String, PluginManifest> = buildMap {
        for ((id, failure) in discoveryFailures) failure.manifest?.let { put(id, it) }
        for (factory in factories.values) put(factory.manifest.id, factory.manifest)
    }

    fun installedVersions(): Map<String, Version> =
        factories.values.associate { it.manifest.id to it.manifest.version }

    fun installedDependencies(): Map<String, Map<String, VersionRange>> =
        factories.values.associate { factory ->
            factory.manifest.id to factory.manifest.dependencies.mapValues { it.value.version }
        }

    /** Registers a factory, dropping stale failure or boot error for the ID. */
    fun add(factory: PluginFactory) {
        val id = factory.manifest.id
        factories[id] = factory
        discoveryFailures.remove(id)
        bootErrors.remove(id)
    }

    /**
     * Forgets every **in-memory** trace of a plugin, including its cached DEX loader.
     */
    fun forget(id: String) {
        factories.remove(id)
        discoveryFailures.remove(id)
        pendingUpdates.remove(id)
        bootErrors.remove(id)
        forgetNativePluginLoader(id)
    }
}

private val registry = PluginRegistry()
private inline val loaded get() = registry.loaded
private lateinit var log: Logger

/**
 * Loads plugins and exposes `revenge.plugins.*` bridge methods.
 */
val pluginLoader by tweak {
    io.github.revenge.xposed.tweaks.plugins.log = this@tweak.log

    val errors = mutableListOf<String>()
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val discovery = discoverExternalPlugins(
        appInfo.dataDir,
        internalPlugins.associate { it.manifest.id to it.manifest.version },
        log,
    )
    val external = discovery.factories

    // Discovery errors aren't hard errors (unsatisfied deps, bad artifacts), but won't allow the plugins to
    // run in this session. They can run once the issues are resolved (e.g. loader/plugin/Discord update).
    registry.discoveryFailures.putAll(discovery.failures)
    for (factory in internalPlugins + external) registry.add(factory)

    registerNativeMethod("revenge.plugins.getConstants") {
        mapOf(
            "storageRootPath" to pluginStorageRoot(appInfo.dataDir).absolutePath,
        )
    }

    registerNativeMethod("revenge.plugins.list") {
        val sources = SourcesStore.ensureLoaded(appInfo.dataDir)
        registry.factories.values.map { factory ->
            factory.toJSPayload(
                log,
                loaded[factory.manifest.id],
                registry.bootErrors[factory.manifest.id],
                sources[factory.manifest.id],
            )
        } + registry.discoveryFailures.mapNotNull { (id, failure) ->
            // Only failures with validated manifests
            val manifest = failure.manifest ?: return@mapNotNull null
            failure.toJSPayload(manifest, sources[id])
        }
    }

    fun handleInstallResult(result: InstallResult) {
        when (result) {
            is InstallResult.New -> {
                val factory = result.factory
                registry.add(factory)
                // A fresh install always starts disabled, even over leftover state from an earlier install.
                clearPersistedState(factory.manifest.id)
                val source = PluginSource(repo = null)
                runCatching { SourcesStore.set(factory.manifest.id, source) }
                    .onFailure { log.e("Failed to record plugin source", it) }

                emitPluginEvent(
                    scope, log, EVENT_PLUGIN_INSTALL_RESULT,
                    mapOf("error" to false, "plugin" to factory.toJSPayload(log, source = source)),
                )
            }

            is InstallResult.Updated -> {
                registry.pendingUpdates[result.manifest.id] = result.version
                runCatching { SourcesStore.set(result.manifest.id, PluginSource(repo = null)) }
                    .onFailure { log.e("Failed to record plugin source", it) }

                emitPluginEvent(
                    scope, log, EVENT_PLUGIN_UPDATED,
                    mapOf("id" to result.manifest.id, "version" to result.manifest.version),
                )
            }
        }
    }

    registerNativeMethod("revenge.plugins.installFile") {
        promptInstallPlugin(
            this,
            scope,
            { id -> registry.factories[id]?.manifest?.version },
            log,
        ) { result ->
            result.fold(
                // Emit a ready event and wait for confirmation.
                onSuccess = { prompt ->
                    emitPluginEvent(
                        scope, log, EVENT_PLUGIN_INSTALL_READY,
                        mapOf(
                            "token" to prompt.token,
                            "manifest" to mapOf(
                                "id" to prompt.manifest.id,
                                "name" to prompt.manifest.name,
                                "description" to prompt.manifest.description,
                                "author" to prompt.manifest.author,
                                "version" to prompt.manifest.version,
                                "icon" to prompt.manifest.icon?.let(::validatedPluginIcon),
                            ),
                            "replaces" to prompt.replaces?.toString(),
                        ),
                    )
                },
                onFailure = { e ->
                    emitPluginEvent(
                        scope, log, EVENT_PLUGIN_INSTALL_RESULT,
                        mapOf("error" to e.toPluginErrorInfo(PluginErrorCodes.INSTALL_FAILED).toJSPayload()),
                    )
                },
            )
        }
        null
    }

    /**
     * `revenge.plugins.confirmInstall(token, accepted) -> { result }`
     *
     * Answers a [EVENT_PLUGIN_INSTALL_RESULT]` prompt. `accepted = false`, an unknown token, or a stale token discards the plan,
     * and returns `cancelled`.
     *
     * Accepting applies the staged install, returning `installed` for a fresh plugin (registered disabled),
     * `pending` for an update (applies after reload).
     */
    registerNativeAsyncMethod("revenge.plugins.confirmInstall") { args ->
        val token = args.getOrNull(0) as? String ?: throw Error("Expected an install token")
        val accepted = args.getOrNull(1) as? Boolean ?: false

        val outcome = confirmPluginInstall(
            token,
            accepted,
            appInfo.dataDir,
            registry.installedVersions(),
            { it in registry.factories },
            log,
        )

        val result = when (outcome) {
            null -> "cancelled"
            is InstallResult.New -> "installed"
            is InstallResult.Updated -> "pending"
        }
        outcome?.let(::handleInstallResult)
        mapOf("result" to result)
    }

    /**
     * `revenge.plugins.planInstall(id, version?, channel?) -> InstallPlan`
     *
     * Resolves an install against cached indexes and returns a plan for JS to confirm and pass to `installFromRepo`.
     */
    registerNativeAsyncMethod("revenge.plugins.planInstall") { args ->
        val id = args.getOrNull(0) as? String ?: throw Error("Expected a plugin ID")
        val version = args.getOrNull(1) as? String
        val channel = args.getOrNull(2) as? String ?: REPO_CHANNEL_LATEST

        RepoStore.ensureLoaded(appInfo.dataDir)
        SourcesStore.ensureLoaded(appInfo.dataDir)

        val repos = RepoStore.list().filter { it.enabled }.mapNotNull { repo ->
            RepoStore.cachedIndex(repo.url)?.let { repo.url to it }
        }
        val sources = SourcesStore.all()

        val plan = resolveInstall(
            ResolveRequest(id, version, channel),
            repos,
            registry.installedVersions(),
            sources,
            registry.installedDependencies(),
        )

        mapOf(
            "actions" to plan.actions.map { action ->
                mapOf(
                    "id" to action.id,
                    "version" to action.version.toString(),
                    "url" to action.url,
                    "sha256" to action.sha256,
                    "size" to action.size,
                    "repo" to action.repo,
                    // The root uses the requested channel, dependencies keep their pinned one.
                    "channel" to if (action.id == id) channel
                    else sources[action.id]?.channel ?: REPO_CHANNEL_LATEST,
                    "replaces" to action.replaces?.toString(),
                )
            },
            "warnings" to plan.warnings,
        )
    }

    /**
     * `revenge.plugins.install(plan) -> { installed, pending, skipped }`
     *
     * Download, verify, then apply on disk. New plugins load right away. Updates only touch the disk and wait for a reload.
     * A manifest that doesn't match the plan aborts the whole plan with `dist/` untouched.
     * Concurrent calls get queued and run one by one, re-checking after each.
     */
    registerNativeAsyncMethod("revenge.plugins.install") { args ->
        val planMap = args.firstOrNull() as? Map<*, *> ?: throw Error("Expected an install plan")
        val actions = (planMap["actions"] as? List<*>).orEmpty().map(::parseRepoInstallAction)

        RepoStore.ensureLoaded(appInfo.dataDir)
        SourcesStore.ensureLoaded(appInfo.dataDir)

        repoInstallMutex.withLock {
            // An overlapping plan may have already satisfied some of these actions.
            val todo = actions.filter { action ->
                registry.factories[action.id]?.manifest?.version != action.version &&
                        registry.pendingUpdates[action.id] != action.version
            }
            val skipped = actions.map { it.id } - todo.map { it.id }.toSet()

            val result = executeInstallPlan(
                todo,
                appInfo.dataDir,
                registry.installedVersions(),
                isUpdate = { it in registry.factories },
                isPendingReload = { it in registry.pendingUpdates },
                log,
                onProgress = { p ->
                    emitPluginEvent(
                        scope, log, EVENT_DOWNLOAD_PROGRESS,
                        mapOf(
                            "id" to p.action.id,
                            "version" to p.action.version.toString(),
                            "repo" to p.action.repo,
                            "received" to p.received,
                            "total" to p.action.size,
                            "index" to p.index,
                            "count" to p.count,
                        ),
                    )
                },
            )

            for (factory in result.fresh) {
                registry.add(factory)
                // A fresh install always starts disabled, even over leftover state from an earlier install.
                clearPersistedState(factory.manifest.id)
                runCatching {
                    callJSMethod(
                        EVENT_PLUGIN_INSTALL_RESULT,
                        listOf(
                            mapOf(
                                "error" to false,
                                "plugin" to factory.toJSPayload(log, source = SourcesStore[factory.manifest.id]),
                            ),
                        ),
                    )
                }.onFailure { log.e("Failed to notify JS of plugin install", it) }
            }

            for (action in result.pending) {
                registry.pendingUpdates[action.id] = action.version
                runCatching {
                    callJSMethod(
                        EVENT_PLUGIN_UPDATED,
                        listOf(mapOf("id" to action.id, "version" to action.version.toString())),
                    )
                }.onFailure { log.e("Failed to notify JS of pending update", it) }
            }

            mapOf(
                "installed" to result.fresh.map { it.manifest.id },
                "pending" to result.pending.map { it.id },
                "skipped" to skipped,
            )
        }
    }

    /**
     * `revenge.plugins.repos.listUpdates(url) -> Update[]`
     *
     * Checks one repo's cached index against the plugins pinned to it.
     * An update exists when the pinned channel points to something newer.
     */
    registerNativeAsyncMethod("revenge.plugins.repos.listUpdates") { args ->
        val url = args.firstOrNull() as? String ?: throw Error("Expected a repository URL")

        RepoStore.ensureLoaded(appInfo.dataDir)
        SourcesStore.ensureLoaded(appInfo.dataDir)

        // Internal plugins update with the loader itself, never through repos.
        if (url == INTERNAL_REPO_URL) return@registerNativeAsyncMethod emptyList<Any?>()

        require(RepoStore.list().any { it.url == url }) { "Unknown repository: '$url'" }
        val index = RepoStore.cachedIndex(url)
            ?: throw Error("No cached index for '$url'; refresh it first")

        buildList {
            for ((id, source) in SourcesStore.all()) {
                if (source.repo != url) continue
                val installedVersion = registry.factories[id]?.manifest?.version ?: continue
                val plugin = index.plugins[id] ?: continue
                val target = plugin.channels[source.channel] ?: continue
                val available = runCatching { Version.parse(target) }.getOrNull() ?: continue

                // Compare against a pending on-disk update if there is one, so it isn't re-offered.
                val current = registry.pendingUpdates[id] ?: installedVersion
                if (available > current) add(
                    mapOf(
                        "id" to id,
                        "installed" to current.toString(),
                        "available" to available.toString(),
                        "channel" to source.channel,
                    )
                )
            }
        }
    }

    registerNativeMethod("revenge.plugins.startNative") { args ->
        val argv = args.asDelegate()
        val pluginId by argv.string()

        val entry = loaded[pluginId]
        when {
            entry != null -> {
                // was stopped earlier, we need to start again.
                if (!entry.started) {
                    entry.scope.flags.value += PluginFlags.ENABLED_LATE
                    try {
                        entry.plugin.start(entry.scope)
                    } catch (e: Throwable) {
                        entry.scope.errors.tryEmit(e)
                        log.e("Plugin $pluginId threw in start()", e)
                    }
                    entry.started = true
                }
            }

            else -> registry.factories[pluginId]?.let { factory ->
                // Not loaded at boot (disabled, or freshly installed).
                // Failures propagate to the JS caller as a bridge error.
                loadPlugin(this, scope, factory, late = true)
                registry.bootErrors.remove(pluginId)
            }
            // Unknown ID: a JS-side plugin with no native counterpart
        }
        null
    }

    registerNativeMethod("revenge.plugins.uninstall") { args ->
        val argv = args.asDelegate()
        val pluginId by argv.string()

        val factory = registry.factories[pluginId]
        require(factory == null || InternalPluginFlags.INTERNAL !in factory.internalFlags) {
            "Plugin $pluginId is internal and cannot be uninstalled"
        }

        // Disable first (cascading to linked dependents), then remove.
        // Disabling also stops the native side and cancels the flag persistence job, so removal can't re-persist.
        disablePlugin(pluginId)
        registry.forget(pluginId)

        requireValidPluginId(pluginId)

        File(externalPluginsRoot(appInfo.dataDir), pluginId).deleteRecursively()
        File(pluginStorageRoot(appInfo.dataDir), pluginId).deleteRecursively()

        clearPersistedState(pluginId)
        runCatching { SourcesStore.remove(pluginId) }
            .onFailure { log.e("Failed to remove plugin source for $pluginId", it) }

        log.i("Uninstalled plugin: $pluginId")
        null
    }

    registerNativeMethod("revenge.plugins.setEnabled") { args ->
        val argv = args.asDelegate()
        val pluginId by argv.string()
        val enabled by argv.boolean()

        val factory = registry.factories[pluginId]
        val entry = loaded[pluginId]

        if (enabled) {
            // Required deps must be installed, satisfied and enabled first. JS handles the resolution UX.
            val problems = factory?.dependencyProblems(registry.factories).orEmpty()
            if (problems.isNotEmpty()) {
                return@registerNativeMethod mapOf(
                    "code" to "DEPENDENCIES_UNSATISFIED",
                    "problems" to problems,
                )
            }

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

            // Required dependents get disabled with it, linked optional ones just stop.
            disablePlugin(pluginId)
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
                registry.bootErrors[manifest.id] = e.toPluginErrorInfo(PluginErrorCodes.LOAD_FAILED)
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
    /** Loading mid-session (user enable) instead of at boot. */
    late: Boolean = false,
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

    if (late) flags.value += PluginFlags.ENABLED_LATE

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
            EVENT_PLUGIN_ERRORED,
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

/**
 * Stops a running plugin, taking every dependent that's actually linked to it down first.
 */
private fun stopPlugin(pluginId: String) {
    val stopping = loaded[pluginId] ?: return
    val stoppingVersion = stopping.scope.manifest.version

    /* Snapshot since the cascade mutates [loaded]. */
    val dependents = loaded.entries.mapNotNull { (dependentId, dependent) ->
        val dep = dependent.scope.manifest.dependencies[pluginId] ?: return@mapNotNull null
        when {
            !dep.optional -> dependentId to "required"
            // If it's not satisfied, it wasn't linked in the first place
            dep.version.satisfies(stoppingVersion) -> dependentId to "linked optional"
            else -> null
        }
    }

    for ((dependentId, edge) in dependents) {
        if (dependentId !in loaded) continue // already stopped by a deeper cascade
        log.i("Stopping $dependentId: its $edge dependency $pluginId is stopping")
        stopPlugin(dependentId)
    }

    val entry = loaded.remove(pluginId) ?: return
    try {
        if (entry.started) entry.plugin.stop(entry.scope)
    } catch (e: Throwable) {
        entry.scope.errors.tryEmit(e)
        log.e("Plugin $pluginId threw in stop()", e)
    } finally {
        entry.persistJob.cancel()
        entry.errorSyncJob.cancel()
    }
}

/**
 * Disables and stops a plugin. Required dependents (transitively) lose their persisted enabled state too,
 * since they can't run without this plugin anymore. Linked optionals only stop, they can load fine next start.
 */
private fun disablePlugin(pluginId: String) {
    val toDisable = mutableSetOf(pluginId)
    // Walk required manifest edges over every known plugin, loaded or not: a dependent that
    // never ran this session (disabled, session-failed) must still lose its enabled flag.
    val knownManifests = registry.knownManifests()
    var changed = true
    while (changed) {
        changed = false
        for ((dependentId, manifest) in knownManifests) {
            if (dependentId in toDisable) continue
            val requiredOnDisabled = manifest.dependencies.any { (depId, dep) ->
                !dep.optional && depId in toDisable
            }
            if (requiredOnDisabled) {
                toDisable += dependentId
                changed = true
            }
        }
    }

    for (id in toDisable) {
        PluginStatesStore.states?.setPluginFlags(id, emptySet())
        // Sync correct data to running instances, to broadcast updates to JS as well.
        loaded[id]?.let { it.scope.flags.value = emptySet() }
    }
    PluginStatesStore.writeNow()

    stopPlugin(pluginId)
}

private fun enablePlugin(pluginId: String) {
    PluginStatesStore.states?.setPluginFlags(pluginId, setOf(PluginFlags.ENABLED))
    PluginStatesStore.writeNow()
}

/** Drops any persisted flags for an ID. */
internal fun clearPersistedState(pluginId: String) {
    PluginStatesStore.states?.removePlugin(pluginId)
    PluginStatesStore.writeNow()
}

/**
 * Problems blocking this plugin from being enabled: required deps that are missing,
 * version-unsatisfied, or disabled. Optional deps never block. Empty means no issues.
 */
private fun PluginFactory.dependencyProblems(
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
        !dep.version.satisfies(installed) -> problem(installed, isPluginEnabledNow(depId, depFactory))
        !isPluginEnabledNow(depId, depFactory) -> problem(installed, false)
        else -> null
    }
}

/** Whether a plugin is currently enabled: live flags if loaded, else persisted state + internal-flag defaults. */
private fun isPluginEnabledNow(pluginId: String, factory: PluginFactory?): Boolean {
    loaded[pluginId]?.let { return PluginFlags.ENABLED in it.scope.flags.value }
    val states = PluginStatesStore.states
    if (states?.isPluginEnabled(pluginId) == true) return true
    if (factory == null) return false
    return InternalPluginFlags.ESSENTIAL in factory.internalFlags ||
            (InternalPluginFlags.ENABLED_BY_DEFAULT in factory.internalFlags && states?.hasPlugin(pluginId) != true)
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
    /** Collector persisting flag changes + broadcasting them to JS. Canceled on stop/uninstall/replace. */
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
    bootError: PluginErrorInfo? = null,
    /* `null` = sideloaded */
    source: PluginSource? = null,
): Map<String, Any?> = mapOf(
    "manifest" to manifest.toMap(),
    "script" to readScript(log),
    "internal" to (InternalPluginFlags.INTERNAL in internalFlags),
    "essential" to (InternalPluginFlags.ESSENTIAL in internalFlags),
    "enabledByDefault" to (InternalPluginFlags.ENABLED_BY_DEFAULT in internalFlags),
    "api" to (InternalPluginFlags.API in internalFlags),
    "source" to source?.toJSPayload(),
    // Errors the native side has already hit (e.g. at boot, before JS was up).
    "errors" to buildList {
        bootError?.let { add(it.toJSPayload()) }
        loaded?.let { addAll(it.scope.errorsJSPayload) }
    },
)

private fun PluginSource.toJSPayload(): Map<String, Any?> = mapOf(
    "repo" to repo,
    "channel" to channel,
)

private val PluginScope.errorsJSPayload
    get() = errors.replayCache.map { e -> e.toPluginErrorInfo(PluginErrorCodes.PLUGIN_ERROR).toJSPayload() }

/**
 * List entry for a plugin that failed discovery. JS registers it so the user can see it and the reason,
 * but it can never run in this session and will try to recover on a later boot.
 */
private fun DiscoveryFailure.toJSPayload(manifest: PluginManifest, source: PluginSource? = null): Map<String, Any?> =
    mapOf(
        "manifest" to manifest.toMap(),
        "script" to null,
        "internal" to false,
        "essential" to false,
        "enabledByDefault" to false,
        "api" to false,
        "failed" to true,
        "source" to source?.toJSPayload(),
        "errors" to listOf(PluginErrorInfo(code, reason).toJSPayload()),
    )

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
    // Anything that reaches runtime already passed format validation.
    "format" to MANIFEST_FORMAT,
    "id" to id,
    "name" to name,
    "description" to description,
    "author" to author,
    "icon" to icon,
    "dependencies" to dependencies.mapValues { (_, dep) ->
        mapOf("version" to dep.version.toString(), "optional" to dep.optional)
    },
    "version" to mapOf("nums" to version.nums, "label" to version.label),
)
