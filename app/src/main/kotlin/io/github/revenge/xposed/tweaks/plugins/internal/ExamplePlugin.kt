package io.github.revenge.xposed.tweaks.plugins.internal

import io.github.revenge.plugins.API_VERSION
import io.github.revenge.plugins.PluginManifest
import io.github.revenge.xposed.api.registerNativeMethod
import io.github.revenge.xposed.tweaks.plugins.InternalPluginFlags

private val manifest = PluginManifest(
    id = "revenge.example",
    name = "Example Plugin",
    description = "Example plugin.",
    author = "Revenge",
    version = API_VERSION,
)

internal val examplePlugin =
    internalPlugin(manifest, setOf(InternalPluginFlags.INTERNAL, InternalPluginFlags.ESSENTIAL)) {
        start {
            log.i("started in ${appInfo.packageName}")
            registerNativeMethod("revenge.example.test") { args ->
                log.i("revenge.example.test($args)")
                null
            }

            registerNativeMethod("revenge.example.test.error") {
                errors.tryEmit(Exception("Test exception!"))
            }
        }
    }