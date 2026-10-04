package io.github.revenge.xposed.tweaks.plugins

import io.github.revenge.plugins.Version
import io.github.revenge.xposed.api.HostScope
import io.github.revenge.xposed.api.callJSMethod
import io.github.revenge.xposed.tweak
import io.github.revenge.xposed.tweaks.plugins.external.InstallResult
import io.github.revenge.xposed.tweaks.plugins.external.confirmPluginInstall
import io.github.revenge.xposed.tweaks.plugins.external.promptInstallPlugin
import io.github.revenge.xposed.tweaks.plugins.external.validatedPluginIcon
import io.github.revenge.xposed.tweaks.plugins.internal.InternalPluginFlags
import io.github.revenge.xposed.tweaks.plugins.repos.*
import kotlinx.coroutines.sync.withLock

val pluginInstallMethods by tweak {
    pluginSystemMethod("revenge.plugins.installFile") {
        SourcesStore.ensureLoaded(appInfo.dataDir)
        promptInstallPlugin(
            installedVersion = { id -> pluginRegistry.factories[id]?.manifest?.version },
            requireInstallable = ::requireReplaceable,
        ) { result ->
            result.fold(
                // Emit a ready event and wait for confirmation.
                onSuccess = { prompt ->
                    emitPluginEvent(
                        PluginEvents.PLUGIN_INSTALL_FILE_READY,
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
                        PluginEvents.PLUGIN_INSTALL_RESULT,
                        mapOf("error" to e.toPluginError(PluginErrorCodes.INSTALL_FAILED).toJSPayload()),
                    )
                },
            )
        }
        null
    }

    /**
     * `revenge.plugins.confirmInstallFile(token, accepted) -> "cancelled" | "installed" | "pending"`
     *
     * Answers a [PluginEvents.PLUGIN_INSTALL_FILE_READY] prompt.
     * `accepted = false`, or an unknown/stale token discards the plan and returns `cancelled`.
     *
     * Accepting applies the staged install, returning `installed` for a fresh plugin (registers disabled),
     * `pending` for an update (applies after reload).
     */
    pluginSystemAsyncMethod("revenge.plugins.confirmInstallFile") { args ->
        val token = args.getOrNull(0) as? String
            ?: throw PluginSystemError(PluginErrorCodes.INVALID_ARGUMENT, "Expected an install token")
        val accepted = args.getOrNull(1) as? Boolean ?: false

        val outcome = confirmPluginInstall(
            token,
            accepted,
            appInfo.dataDir,
            pluginRegistry.installedManifests(),
        ) { it in pluginRegistry.factories }

        val result = when (outcome) {
            null -> "cancelled"
            is InstallResult.New -> "installed"
            is InstallResult.Updated -> "pending"
        }
        outcome?.let { handleInstallResult(it) }
        if (outcome != null) emitDependencyGraphUpdate()
        result
    }

    /**
     * `revenge.plugins.planInstall(id, options?) -> InstallPlan`
     *
     * Resolves an install against cached indexes and returns a plan for JS to confirm and pass to `install`.
     *
     * `options = { repos?: string[], targets?: { [id]: { repo?, version?, channel? } } }`:
     * - `repos` only considers those repositories, for every plugin.
     * - `targets` overrides default resolution rules.
     */
    pluginSystemAsyncMethod("revenge.plugins.planInstall") { args ->
        val id = args.getOrNull(0) as? String
            ?: throw PluginSystemError(PluginErrorCodes.INVALID_ARGUMENT, "Expected a plugin ID")
        val options = args.getOrNull(1)?.let {
            it as? Map<*, *> ?: throw PluginSystemError(
                PluginErrorCodes.INVALID_ARGUMENT,
                "Expected plan options or null"
            )
        }

        val targets = (options?.get("targets") as? Map<*, *>).orEmpty().entries.associate { (key, value) ->
            val target = value as? Map<*, *>
                ?: throw PluginSystemError(PluginErrorCodes.INVALID_ARGUMENT, "Expected a target object for '$key'")
            key as String to PlanTarget(
                repo = target["repo"] as? String,
                version = target["version"] as? String,
                channel = target["channel"] as? String,
            )
        }
        val filteredRepos = (options?.get("repos") as? List<*>)?.map {
            it as? String
                ?: throw PluginSystemError(PluginErrorCodes.INVALID_ARGUMENT, "Expected a list of repository URLs")
        }

        RepoStore.ensureLoaded(appInfo.dataDir)
        SourcesStore.ensureLoaded(appInfo.dataDir)

        val repos =
            RepoStore.list().filter { it.enabled && (filteredRepos?.contains(it.url) ?: true) }.mapNotNull { repo ->
                RepoStore.cachedIndex(repo.url)?.let { repo.url to it }
            }

        val plan = resolveInstall(
            ResolveRequest(id, targets),
            repos,
            pluginRegistry.installedVersions(),
            SourcesStore.all(),
            pluginRegistry.installedDependencies(),
        )

        for (action in plan.actions) requireReplaceable(action.id)

        mapOf(
            "actions" to plan.actions.map { action ->
                mapOf(
                    "id" to action.id,
                    "version" to action.version.toString(),
                    "url" to action.url,
                    "sha256" to action.sha256,
                    "size" to action.size,
                    "repo" to action.repo,
                    "channel" to action.channel,
                    "hold" to action.hold,
                    "replaces" to action.replaces?.toString(),
                    "dependents" to action.dependents.map { (dependent, how) ->
                        mapOf("id" to dependent, "optional" to how.optional, "range" to how.range.toString())
                    },
                    "allowed" to action.allowed,
                )
            },
            "warnings" to plan.warnings,
        )
    }

    /**
     * `revenge.plugins.install(plan) -> { installed, pending, skipped }`
     *
     * Download, verify, and apply on disk. New plugins load immediately. Updates run after a reload.
     * A manifest not matching the plan aborts the whole plan without committing.
     * Actions with exact artifact (version + hash) already on disk are skipped, only recording their source and hold status.
     *
     * Concurrent calls get queued and run one by one, re-checking after each.
     */
    pluginSystemAsyncMethod("revenge.plugins.install") { args ->
        val planMap = args.firstOrNull() as? Map<*, *>
            ?: throw PluginSystemError(PluginErrorCodes.INVALID_ARGUMENT, "Expected an install plan")
        val actions = (planMap["actions"] as? List<*>).orEmpty().map(::parseRepoInstallAction)

        RepoStore.ensureLoaded(appInfo.dataDir)
        SourcesStore.ensureLoaded(appInfo.dataDir)

        for (action in actions) requireReplaceable(action.id)

        repoInstallMutex.withLock {
            val todo = mutableListOf<RepoInstallAction>()
            val skipped = mutableListOf<String>()
            for (action in actions) {
                val source = SourcesStore[action.id]
                val onDisk = pluginRegistry.pendingUpdates[action.id]
                    ?: pluginRegistry.factories[action.id]?.manifest?.version

                if (onDisk != action.version || source?.hash != action.sha256) {
                    todo += action
                    continue
                }

                // The exact artifact is on disk already (an overlapping plan installed it), so change the source only.
                val moved = source.repo != action.repo || source.channel != action.channel ||
                        (action.hold != null && source.held != action.hold)
                if (moved) runCatching {
                    SourcesStore.record(action.id, action.repo, action.channel, action.sha256, action.hold)
                }.onFailure { pluginLog.e("Failed to record plugin source for ${action.id}", it) }
                skipped += action.id
            }

            val result = executeInstallPlan(
                todo,
                appInfo.dataDir,
                pluginRegistry.installedManifests(),
                isUpdate = { it in pluginRegistry.factories },
                isPendingReload = { id ->
                    pluginRegistry.loaded[id]?.let { PluginFlags.PENDING_RELOAD in it.scope.flags.value } ?: false
                            || id in pluginRegistry.pendingUpdates
                },
                onProgress = { p ->
                    emitPluginEvent(
                        PluginEvents.DOWNLOAD_PROGRESS,
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
                pluginRegistry.add(factory)
                // Default state for fresh installs.
                clearBootSlotFlags(factory.manifest.id)
                runCatching {
                    callJSMethod(
                        PluginEvents.PLUGIN_INSTALL_RESULT,
                        listOf(
                            mapOf(
                                "error" to false,
                                "plugin" to factory.toJSPayload(source = SourcesStore[factory.manifest.id]),
                            ),
                        ),
                    )
                }.onFailure { pluginLog.e("Failed to notify JS of plugin install", it) }
            }

            for (action in result.pending) {
                pluginRegistry.pendingUpdates[action.id] = action.version
                runCatching {
                    callJSMethod(
                        PluginEvents.PLUGIN_UPDATED,
                        listOf(mapOf("id" to action.id, "version" to action.version.toString())),
                    )
                }.onFailure { pluginLog.e("Failed to notify JS of pending update", it) }
            }

            // Update the dependency graph if needed.
            if (result.fresh.isNotEmpty() || result.pending.isNotEmpty()) emitDependencyGraphUpdate()

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
     * Checks a repo's cached index against the plugins pinned to it.
     * An update exists when the pinned channel points to something newer. Held plugins are skipped.
     */
    pluginSystemAsyncMethod("revenge.plugins.repos.listUpdates") { args ->
        val url = args.firstOrNull() as? String
            ?: throw PluginSystemError(PluginErrorCodes.INVALID_ARGUMENT, "Expected a repository URL")

        RepoStore.ensureLoaded(appInfo.dataDir)
        SourcesStore.ensureLoaded(appInfo.dataDir)

        // Internal plugins update with the loader itself, never through repos.
        if (url == INTERNAL_REPO_URL) return@pluginSystemAsyncMethod emptyList<Any?>()

        if (RepoStore.list().none { it.url == url })
            throw PluginSystemError(PluginErrorCodes.NOT_FOUND, "Unknown repository: '$url'")
        val index = RepoStore.cachedIndex(url)
            ?: throw PluginSystemError(PluginErrorCodes.NOT_FOUND, "No cached index for '$url'; refresh it first")

        buildList {
            for ((id, source) in SourcesStore.all()) {
                if (source.repo != url || source.held) continue
                val installedVersion = pluginRegistry.factories[id]?.manifest?.version ?: continue
                val plugin = index.plugins[id] ?: continue
                val target = plugin.channels[source.channel] ?: continue
                val available = runCatching { Version.parse(target) }.getOrNull() ?: continue

                // Compare against a pending on-disk update if there is one, so it isn't re-offered.
                val current = pluginRegistry.pendingUpdates[id] ?: installedVersion
                val republished = available.compareTo(current) == 0 &&
                        source.hash != null && plugin.versions[target]?.sha256 != source.hash
                if (available > current || republished) add(
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
}

/** Rejects replacing an internal plugin without provenance. Stubs have one, so they can update. */
private fun requireReplaceable(id: String) {
    val factory = pluginRegistry.factories[id] ?: return
    if (InternalPluginFlags.INTERNAL in factory.internalFlags && SourcesStore[id] == null)
        throw PluginSystemError(
            PluginErrorCodes.NOT_ALLOWED,
            "Plugin $id is built into Revenge and can't be replaced",
        )
}

context(host: HostScope)
private fun handleInstallResult(result: InstallResult) {
    when (result) {
        is InstallResult.New -> {
            val factory = result.factory
            pluginRegistry.add(factory)
            // Default states for fresh installs.
            clearBootSlotFlags(factory.manifest.id)
            val source = PluginSource(repo = null)
            runCatching { SourcesStore.set(factory.manifest.id, source) }
                .onFailure { pluginLog.e("Failed to record plugin source", it) }

            emitPluginEvent(
                PluginEvents.PLUGIN_INSTALL_RESULT,
                mapOf("error" to false, "plugin" to factory.toJSPayload(source = source)),
            )
        }

        is InstallResult.Updated -> {
            pluginRegistry.pendingUpdates[result.manifest.id] = result.version
            runCatching { SourcesStore.set(result.manifest.id, PluginSource(repo = null, hash = null)) }
                .onFailure { pluginLog.e("Failed to record plugin source", it) }

            emitPluginEvent(
                PluginEvents.PLUGIN_UPDATED,
                mapOf("id" to result.manifest.id, "version" to result.manifest.version),
            )
        }
    }
}
