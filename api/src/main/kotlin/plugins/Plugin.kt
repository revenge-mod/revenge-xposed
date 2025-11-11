package io.github.revenge.plugins

import InternalApi
import android.content.Context
import android.content.pm.ApplicationInfo
import io.github.revenge.plugins.impl.PluginsHost
import java.util.*
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

@OptIn(InternalApi::class)
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
     *
     * See [PluginFlags] for all possible flags.
     */
    var flags: Set<PluginFlags>
        get() = flagsLock.read { Collections.unmodifiableSet(_flags) }
        set(value) = flagsLock.write {
            val oldValue = _flags.toSet()
            if (oldValue != value) {
                _flags.clear()
                _flags.addAll(value)
                PluginsHost.notifyFlagsUpdate(this)
            }
        }

    /**
     * Runs when the plugin is loaded, before [start].
     */
    open fun init(applicationInfo: ApplicationInfo, classLoader: ClassLoader) {}

    /**
     * Runs when the plugin is able to access [Context].
     */
    open fun start(context: Context) {}

    /**
     * Runs when the plugin is being stopped.
     */
    open fun stop(context: Context) {}

    /**
     * Returns a map of method names to their corresponding bridge method callbacks.
     * These methods will be exposed to the JavaScript side of the application.
     *
     * This method is called immediately after [init]. If you need [Context], return a lambda that captures it from [start].
     *
     * See [MethodCallback] for possible return types.
     */
    open fun getMethods(
        applicationInfo: ApplicationInfo,
        classLoader: ClassLoader
    ): Map<String, MethodCallback> = mapOf()
}
