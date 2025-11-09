package io.github.revenge.xposed.plugins

import android.content.pm.ApplicationInfo
import io.github.revenge.xposed.modules.bridge.BridgeMethodCallback
import io.github.revenge.xposed.modules.plugins.PluginFlags
import io.github.revenge.xposed.modules.plugins.PluginsStatesModule
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

abstract class Plugin(val manifest: PluginManifest) {
    private val flagsLock = ReentrantReadWriteLock()
    private val _flags: MutableSet<PluginFlags> = mutableSetOf()

    /**
     * The current flags set for the plugin.
     *
     * The returned set is a copy and modifying it does not affect the plugin's flags.
     * To update the flags, assign a new set to this property.
     *
     * Example:
     *
     * ```kotlin
     * plugin.flags += + PluginFlags.RELOAD_REQUIRED
     * ```
     */
    var flags: Set<PluginFlags>
        get() = flagsLock.read { _flags.toSet() }
        set(value) = flagsLock.write {
            val oldValue = _flags.toSet()
            if (oldValue != value) {
                _flags.clear()
                _flags.addAll(value)
                PluginsStatesModule.updatePluginFlags(this)
            }
        }

    /**
     * Runs when the plugin is being initialized.
     */
    abstract fun init(applicationInfo: ApplicationInfo, classLoader: ClassLoader)

    /**
     * Runs when the plugin is being stopped.
     */
    abstract fun stop(applicationInfo: ApplicationInfo, classLoader: ClassLoader)

    /**
     * Returns a map of method names to their corresponding bridge method callbacks.
     */
    abstract fun getMethods(
        applicationInfo: ApplicationInfo,
        classLoader: ClassLoader
    ): Map<String, BridgeMethodCallback>
}
