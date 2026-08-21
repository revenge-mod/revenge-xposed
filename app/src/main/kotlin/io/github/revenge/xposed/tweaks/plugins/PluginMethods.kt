package io.github.revenge.xposed.tweaks.plugins

import io.github.revenge.bridge.asDelegate
import io.github.revenge.xposed.tweak
import io.github.revenge.xposed.tweaks.plugins.external.requireValidPluginId
import io.github.revenge.xposed.tweaks.plugins.internal.InternalPluginFlags
import io.github.revenge.xposed.tweaks.plugins.repos.SourcesStore
import java.io.File

val pluginMethods by tweak {
    pluginSystemMethod("revenge.plugins.getConstants") {
        mapOf(
            "storageRootPath" to pluginStorageRoot(appInfo.dataDir).absolutePath,
        )
    }

    pluginSystemMethod("revenge.plugins.list") {
        val sources = SourcesStore.ensureLoaded(appInfo.dataDir)
        pluginRegistry.factories.values.map { factory ->
            factory.toJSPayload(
                pluginRegistry.loaded[factory.manifest.id],
                pluginRegistry.bootErrors[factory.manifest.id],
                sources[factory.manifest.id],
            )
        } + pluginRegistry.discoveryFailures.mapNotNull { (id, failure) ->
            // Only failures with validated manifests
            val manifest = failure.manifest ?: return@mapNotNull null
            failure.toJSPayload(manifest, sources[id])
        }
    }

    pluginSystemMethod("revenge.plugins.startNative") { args ->
        val argv = args.asDelegate()
        val pluginId by argv.string()

        val entry = pluginRegistry.loaded[pluginId]
        when {
            entry != null -> {
                // Already built this session but stopped, so start it again.
                if (!entry.started) {
                    entry.scope.flags.value += PluginFlags.STARTED_LATE
                    try {
                        entry.plugin.start(entry.scope)
                    } catch (e: Throwable) {
                        entry.scope.errors.tryEmit(e)
                        pluginLog.e("Plugin $pluginId threw in start()", e)
                    }
                    entry.started = true
                }
            }

            else -> pluginRegistry.factories[pluginId]?.let { factory ->
                // Not loaded at boot (disabled, or freshly installed).
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
            // Unknown ID: a JS-side plugin with no native counterpart
        }
        null
    }

    pluginSystemMethod("revenge.plugins.uninstall") { args ->
        val argv = args.asDelegate()
        val pluginId by argv.string()

        val factory = pluginRegistry.factories[pluginId]
        if (factory != null && InternalPluginFlags.INTERNAL in factory.internalFlags)
            throw PluginSystemError(
                PluginErrorCodes.NOT_ALLOWED,
                "Plugin $pluginId is internal and cannot be uninstalled",
            )

        // Disable first (cascading to linked dependents), then remove.
        // Disabling also stops the native side and cancels the flag persistence job, so it can't re-persist.
        disablePlugin(pluginId)
        pluginRegistry.forget(pluginId)

        requireValidPluginId(pluginId)

        File(externalPluginsRoot(appInfo.dataDir), pluginId).deleteRecursively()
        File(pluginStorageRoot(appInfo.dataDir), pluginId).deleteRecursively()

        clearPersistedState(pluginId)
        runCatching { SourcesStore.remove(pluginId) }
            .onFailure { pluginLog.e("Failed to remove plugin source for $pluginId", it) }

        pluginLog.i("Uninstalled plugin: $pluginId")
        null
    }

    pluginSystemMethod("revenge.plugins.setEnabled") { args ->
        val argv = args.asDelegate()
        val pluginId by argv.string()
        val enabled by argv.boolean()
        val requiredByUser by argv.booleanOrNull()

        val factory = pluginRegistry.factories[pluginId]
        val entry = pluginRegistry.loaded[pluginId]

        if (enabled) {
            // Required deps must be installed, satisfied and enabled first. JS must handle the resolution UX.
            val problems = factory?.dependencyProblems(pluginRegistry.factories).orEmpty()
            if (problems.isNotEmpty()) throw PluginSystemError(
                PluginErrorCodes.DEPENDENCIES_UNSATISFIED,
                unsatisfiedDependenciesMessage(pluginId, problems),
                details = mapOf("problems" to problems),
            )

            val requiredByUser = requiredByUser == true

            if (entry != null) {
                entry.scope.flags.value += pluginEnablementFlags(requiredByUser)
            } else {
                // Not loaded this session (or not managed natively)
                enablePlugin(pluginId, requiredByUser)
            }
        } else {
            // Note that essential plugins will be rejected by disablePlugin itself.
            // Required dependents get automatically disabled with it, linked optionals just stop.
            disablePlugin(pluginId)
        }
        null
    }

    /**
     * `revenge.plugins.setHeld(id, held) -> Source`
     *
     * Holds a plugin at its installed version, or resumes following its channel.
     * A hold skips update checks and stops the resolver from moving the plugin to satisfy something else.
     */
    pluginSystemAsyncMethod("revenge.plugins.setHeld") { args ->
        val pluginId = args.getOrNull(0) as? String
            ?: throw PluginSystemError(PluginErrorCodes.INVALID_ARGUMENT, "Expected a plugin ID")
        val held = args.getOrNull(1) as? Boolean
            ?: throw PluginSystemError(PluginErrorCodes.INVALID_ARGUMENT, "Expected a boolean")

        SourcesStore.ensureLoaded(appInfo.dataDir)

        val factory = pluginRegistry.factories[pluginId]
            ?: throw PluginSystemError(PluginErrorCodes.NOT_FOUND, "Unknown plugin: '$pluginId'")
        if (InternalPluginFlags.INTERNAL in factory.internalFlags)
            throw PluginSystemError(
                PluginErrorCodes.NOT_ALLOWED,
                "Plugin $pluginId is internal and updates with Revenge itself",
            )

        SourcesStore.setHeld(pluginId, held).toJSPayload()
    }
}
