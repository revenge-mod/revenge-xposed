package io.github.revenge.xposed.plugins

import android.content.pm.ApplicationInfo
import io.github.revenge.xposed.modules.bridge.BridgeMethodCallback

/**
 * A plugin that does nothing.
 *
 * If your plugin only has a JS implementation, you may use this class as a stub.
 */
class EmptyPlugin(manifest: PluginManifest) : Plugin(manifest) {
    override fun init(applicationInfo: ApplicationInfo, classLoader: ClassLoader) {}
    override fun getMethods(
        applicationInfo: ApplicationInfo,
        classLoader: ClassLoader
    ) = mapOf<String, BridgeMethodCallback>()

    override fun stop(applicationInfo: ApplicationInfo, classLoader: ClassLoader) {}
}