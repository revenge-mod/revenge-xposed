package io.github.revenge.xposed.plugins

import android.content.Context
import android.content.pm.ApplicationInfo
import io.github.revenge.plugins.MethodCallback
import io.github.revenge.plugins.Plugin
import io.github.revenge.plugins.PluginManifest
import io.github.revenge.xposed.Utils.Log
import io.github.revenge.xposed.modules.plugins.InternalPluginFlags

internal class ExampleCorePluginProvider :
    InternalPluginProvider(setOf(InternalPluginFlags.INTERNAL, InternalPluginFlags.ESSENTIAL)) {
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

    override fun stop(context: Context) {
        Log.i("ExampleCorePlugin stopped: ${context.applicationInfo.packageName}")
    }

    override fun getMethods(
        applicationInfo: ApplicationInfo, classLoader: ClassLoader
    ) = buildMap<String, MethodCallback> {
        put("plugin.test") {
            Log.i("plugin.test called")
        }
    }
}