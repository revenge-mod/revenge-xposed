package io.github.revenge.xposed.api

import android.app.Activity
import android.content.Context
import android.content.pm.ApplicationInfo
import io.github.revenge.bridge.RevengeBridge

/**
 * Runtime surface every extension runs against. Carries the basics ([appInfo], [classLoader], [modulePath]),
 * the JS <-> native [bridge], and lifecycle hooks for grabbing the app's [Context] / [Activity].
 *
 * A plugin receives one via [io.github.revenge.plugins.PluginScope], which extends this.
 */
interface HostScope {
    /** Path to the module APK on disk. Use to load bundled assets. */
    val modulePath: String

    /** [ApplicationInfo] of the target app. */
    val appInfo: ApplicationInfo

    /** [ClassLoader] of the target app. */
    val classLoader: ClassLoader

    /** The JS <-> native bridge. Register methods and call JS through it. */
    val bridge: RevengeBridge

    /**
     * Run [block] with the target app's [Context]. Fires immediately if a [Context] is already captured;
     * otherwise queued until one is available.
     */
    fun withAppContext(block: (Context) -> Unit)

    /**
     * Run [block] with the target app's [Activity]. Fires immediately if an [Activity] is already available;
     * otherwise queued until one is created.
     */
    fun withAppActivity(block: (Activity) -> Unit)
}

/**
 * Shorthand for [bridge].`registerMethod`.
 *
 * ```kotlin
 * registerNativeMethod("my.method") { args -> mapOf("echo" to args) }
 * ```
 */
fun HostScope.registerNativeMethod(
    name: String,
    handler: (args: List<Any?>) -> Any?,
) = bridge.registerMethod(name, handler)

/** Shorthand for [bridge].`callJSMethod`. */
suspend fun HostScope.callJSMethod(name: String, args: List<Any?> = emptyList()): Any? =
    bridge.callJSMethod(name, args)
