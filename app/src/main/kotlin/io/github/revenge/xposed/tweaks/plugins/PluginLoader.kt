package io.github.revenge.xposed.tweaks.plugins

import io.github.revenge.xposed.tweak
import io.github.revenge.xposed.tweaks.bundleManifest
import io.github.revenge.xposed.tweaks.plugins.external.discoverExternalPlugins
import io.github.revenge.xposed.tweaks.plugins.internal.InternalPluginFlags
import io.github.revenge.xposed.tweaks.plugins.internal.bundledPlugins
import io.github.revenge.xposed.tweaks.plugins.internal.internalPlugins

val pluginLoader by tweak {
    val errors = mutableListOf<String>()

    // Load states before anything reads or writes a slot.
    PluginStatesStore.ensureLoaded(appInfo.dataDir)

    val bundled = bundledPlugins(bundleManifest)
        // Not an existing internal plugin
        .filterNot { b -> internalPlugins.any { it.manifest.id == b.manifest.id } }

    val known = internalPlugins + bundled

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
