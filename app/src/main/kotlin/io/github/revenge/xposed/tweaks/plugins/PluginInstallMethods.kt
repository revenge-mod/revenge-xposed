package io.github.revenge.xposed.tweaks.plugins

import io.github.revenge.plugins.Version
import io.github.revenge.xposed.api.HostScope
import io.github.revenge.xposed.api.callJSMethod
import io.github.revenge.xposed.tweak
import io.github.revenge.xposed.tweaks.plugins.external.InstallResult
import io.github.revenge.xposed.tweaks.plugins.external.confirmPluginInstall
import io.github.revenge.xposed.tweaks.plugins.external.promptInstallPlugin
import io.github.revenge.xposed.tweaks.plugins.external.validatedPluginIcon
import io.github.revenge.xposed.tweaks.plugins.repos.*
import kotlinx.coroutines.sync.withLock

val pluginInstallMethods by tweak {
    pluginSystemMethod("revenge.plugins.installFile") {
        promptInstallPlugin({ id -> pluginRegistry.factories[id]?.manifest?.version }) { result ->
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
            pluginRegistry.installedVersions(),
        ) { it in pluginRegistry.factories }

        val result = when (outcome) {
            null -> "cancelled"
            is InstallResult.New -> "installed"
            is InstallResult.Updated -> "pending"
        }
        outcome?.let { handleInstallResult(it) }
        result
    }

    /**
     * `revenge.plugins.planInstall(id, version?, channel?, filteredRepos?) -> InstallPlan`
     *
     * Resolves an install against cached indexes and returns a plan for JS to confirm and pass to `install`.
     */
    pluginSystemAsyncMethod("revenge.plugins.planInstall") { args ->
        val id = args.getOrNull(0) as? String
            ?: throw PluginSystemError(PluginErrorCodes.INVALID_ARGUMENT, "Expected a plugin ID")
        val version = args.getOrNull(1) as? String
        val channel = args.getOrNull(2) as? String ?: REPO_CHANNEL_LATEST

        @Suppress("UNCHECKED_CAST")
        val filteredRepos = try {
            args.getOrNull(3) as ArrayList<String>?
        } catch (_: Throwable) {
            throw PluginSystemError(PluginErrorCodes.INVALID_ARGUMENT, "Expected a list of repository URLs or null")
        }

        RepoStore.ensureLoaded(appInfo.dataDir)
        SourcesStore.ensureLoaded(appInfo.dataDir)

        val repos =
            RepoStore.list().filter { it.enabled && (filteredRepos?.contains(it.url) ?: true) }.mapNotNull { repo ->
                RepoStore.cachedIndex(repo.url)?.let { repo.url to it }
            }
        val sources = SourcesStore.all()

        val plan = resolveInstall(
            ResolveRequest(id, version, channel),
            repos,
            pluginRegistry.installedVersions(),
            sources,
            pluginRegistry.installedDependencies(),
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
     * Download, verify, and apply on disk. New plugins load immediately. Updates run after a reload.
     * A manifest not matching the plan aborts the whole plan without committing.
     *
     * Concurrent calls get queued and run one by one, re-checking after each.
     */
    pluginSystemAsyncMethod("revenge.plugins.install") { args ->
        val planMap = args.firstOrNull() as? Map<*, *>
            ?: throw PluginSystemError(PluginErrorCodes.INVALID_ARGUMENT, "Expected an install plan")
        val actions = (planMap["actions"] as? List<*>).orEmpty().map(::parseRepoInstallAction)

        RepoStore.ensureLoaded(appInfo.dataDir)
        SourcesStore.ensureLoaded(appInfo.dataDir)

        repoInstallMutex.withLock {
            // An overlapping plan may have already satisfied some of these actions.
            val todo = actions.filter { action ->
                pluginRegistry.factories[action.id]?.manifest?.version != action.version &&
                        pluginRegistry.pendingUpdates[action.id] != action.version
            }
            val skipped = actions.map { it.id } - todo.map { it.id }.toSet()

            val result = executeInstallPlan(
                todo,
                appInfo.dataDir,
                pluginRegistry.installedVersions(),
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
                clearSavedFlags(factory.manifest.id)
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
}

context(host: HostScope)
private fun handleInstallResult(result: InstallResult) {
    when (result) {
        is InstallResult.New -> {
            val factory = result.factory
            pluginRegistry.add(factory)
            // Default states for fresh installs.
            clearSavedFlags(factory.manifest.id)
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
            runCatching { SourcesStore.set(result.manifest.id, PluginSource(repo = null)) }
                .onFailure { pluginLog.e("Failed to record plugin source", it) }

            emitPluginEvent(
                PluginEvents.PLUGIN_UPDATED,
                mapOf("id" to result.manifest.id, "version" to result.manifest.version),
            )
        }
    }
}
