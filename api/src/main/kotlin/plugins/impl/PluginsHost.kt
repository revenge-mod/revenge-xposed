package io.github.revenge.plugins.impl

import InternalApi
import io.github.revenge.plugins.Plugin
import io.github.revenge.plugins.PluginFlags
import io.github.revenge.plugins.PluginManifest
import io.github.revenge.plugins.PluginView
import java.util.*
import java.util.concurrent.CopyOnWriteArrayList

@Suppress("UNUSED")
@InternalApi
/**
 * Host for the plugin system to communicate with the application. For example, a Xposed module.
 */
object PluginsHost {
    private val flagsUpdateListeners = CopyOnWriteArrayList<(PluginView) -> Unit>()

    /**
     * Register a listener for plugin flags updates.
     */
    fun onFlagsUpdate(listener: (PluginView) -> Unit) {
        flagsUpdateListeners.add(listener)
    }

    /**
     * Unregister a listener for plugin flags updates.
     */
    fun offFlagsUpdate(listener: (PluginView) -> Unit) {
        flagsUpdateListeners.remove(listener)
    }

    internal fun notifyFlagsUpdate(plugin: Plugin) {
        val view = object : PluginView {
            override val manifest: PluginManifest = plugin.manifest
            override val flags: Set<PluginFlags> = Collections.unmodifiableSet(plugin.flags)
        }

        flagsUpdateListeners.forEach { runCatching { it(view) } }
    }
}