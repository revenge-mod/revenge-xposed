package io.github.revenge.xposed.plugins.internal

import android.content.pm.ApplicationInfo
import io.github.revenge.xposed.Utils.Log
import io.github.revenge.xposed.modules.bridge.BridgeMethodCallback
import io.github.revenge.xposed.modules.plugins.InternalPluginFlags
import io.github.revenge.xposed.plugins.Plugin
import io.github.revenge.xposed.plugins.PluginManifest

internal class ExampleCorePluginProvider : InternalPluginProvider(setOf(InternalPluginFlags.INTERNAL)) {
    override val manifest = PluginManifest(
        id = "revenge.core.example",
        name = "Example Core Plugin",
        description = "Example core plugin.",
        author = "Revenge Team",
    )

    override fun createPlugin() = ExampleCorePlugin(manifest)
}

class ExampleCorePlugin(manifest: PluginManifest) : Plugin(manifest) {
    override fun init(applicationInfo: ApplicationInfo, classLoader: ClassLoader) {
        Log.i("ExampleCorePlugin initialized: ${applicationInfo.packageName}")
    }

    override fun stop(applicationInfo: ApplicationInfo, classLoader: ClassLoader) {
        Log.i("ExampleCorePlugin stopped: ${applicationInfo.packageName}")
    }

    override fun getMethods(
        applicationInfo: ApplicationInfo, classLoader: ClassLoader
    ) = buildMap<String, BridgeMethodCallback> {
        put("plugin.test") {
            Log.i("plugin.test called")
        }
    }
}