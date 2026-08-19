package io.github.revenge.xposed.tweaks.plugins

import io.github.revenge.xposed.tweak
import io.github.revenge.xposed.tweaks.plugins.external.discoverExternalPlugins
import io.github.revenge.xposed.tweaks.plugins.internal.InternalPluginFlags
import io.github.revenge.xposed.tweaks.plugins.internal.internalPlugins

val pluginLoader by tweak {
    val errors = mutableListOf<String>()

    val discovery = discoverExternalPlugins(
        appInfo.dataDir,
        internalPlugins.associate { it.manifest.id to it.manifest.version },
    )
    val external = discovery.factories

    pluginRegistry.discoveryFailures.putAll(discovery.failures)

    // Some discovery errors aren't hard errors (unsatisfied deps), but won't allow the plugins to run in this session.
    // They can run once the issues are resolved (e.g. loader/plugin/Discord update).
    for ((id, failure) in discovery.failures) {
        // Prefer the validated manifest's ID, since [id] could be a directory name instead.
        if (failure.isPluginFault) clearPersistedState(failure.manifest?.id ?: id)
    }

    for (factory in internalPlugins + external) pluginRegistry.add(factory)

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
