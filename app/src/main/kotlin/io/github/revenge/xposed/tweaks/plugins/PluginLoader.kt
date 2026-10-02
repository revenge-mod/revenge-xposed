package io.github.revenge.xposed.tweaks.plugins

import io.github.revenge.xposed.tweak
import io.github.revenge.xposed.tweaks.bundleManifest
import io.github.revenge.xposed.tweaks.plugins.external.MANIFEST_FILE
import io.github.revenge.xposed.tweaks.plugins.external.discoverExternalPlugins
import io.github.revenge.xposed.tweaks.plugins.internal.*
import io.github.revenge.xposed.tweaks.plugins.repos.SourcesStore
import java.io.File

val pluginLoader by tweak {
    val errors = mutableListOf<String>()

    // Load states before anything reads or writes a slot.
    PluginStatesStore.ensureLoaded(appInfo.dataDir)

    val bundled = bundledPlugins(bundleManifest)
        // Not an existing internal plugin
        .filterNot { b -> internalPlugins.any { it.manifest.id == b.manifest.id } }

    // Register default provenances.
    val sources = SourcesStore.ensureLoaded(appInfo.dataDir).toMutableMap()
    for ((id, source) in defaultSourcesToSeed(internalPlugins + bundled, sources)) {
        runCatching { SourcesStore.set(id, source) }
            .onSuccess { sources[id] = source }
            .onFailure { pluginLog.e("Failed to record stub source for $id", it) }
    }

    // Installed copies of internals with provenance win, and load as external plugins.
    val distRoot = externalPluginsRoot(appInfo.dataDir)
    val shadowed = shadowedInternalIds(internalPlugins + bundled, sources) { id ->
        File(File(distRoot, id), MANIFEST_FILE).isFile
    }
    for (id in shadowed) pluginLog.i("Using installed copy of internal plugin: $id")

    val known = (internalPlugins + bundled).filterNot { it.manifest.id in shadowed }

    val discovery = discoverExternalPlugins(appInfo.dataDir, known.associate { it.manifest.id to it.manifest })
    val external = discovery.factories

    pluginRegistry.recordDiscoveryFailures(discovery.failures)

    // Some discovery errors aren't hard errors (unsatisfied deps), but won't allow the plugins to run in this session.
    // They can run once the issues are resolved (e.g. loader/plugin/Discord update).
    for ((id, failure) in discovery.failures) {
        // Prefer the validated manifest's ID, since [id] could be a directory name instead.
        if (failure.isPluginFault) clearBootSlotFlags(failure.manifest?.id ?: id)
    }

    for (factory in known + external) pluginRegistry.add(factory)

    PluginStatesStore.batchSave {
        val slot = PluginStatesStore.boot

        for (factory in known + external) {
            val manifest = factory.manifest

            try {
                val essential = InternalPluginFlags.ESSENTIAL in factory.internalFlags
                val enabledByDefault = InternalPluginFlags.ENABLED_BY_DEFAULT in factory.internalFlags

                val shouldLoad = slot.isPluginEnabled(manifest.id) ||
                        essential ||
                        (enabledByDefault && !slot.hasPlugin(manifest.id))

                if (!shouldLoad) {
                    pluginLog.i("Skipping disabled plugin: ${manifest.id}")
                    continue
                }
                loadPlugin(factory)
            } catch (e: Throwable) {
                val err = "Failed to load plugin ${manifest.id}: ${e.message}"
                errors += err
                pluginRegistry.bootErrors[manifest.id] = e.toPluginError(PluginErrorCodes.LOAD_FAILED)
                pluginLog.e(err, e)
            }
        }
    }

    pluginLog.i("Loaded ${pluginRegistry.loaded.size} plugins with ${errors.size} errors")
    for (err in errors) pluginLog.e(err)
}
