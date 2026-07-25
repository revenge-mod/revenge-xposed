@file:JvmName("Utils")

package io.github.revenge

import android.app.AndroidAppHelper
import android.content.Intent
import android.util.Log
import kotlin.system.exitProcess

/**
 * Restart the host app by re-launching its main activity and killing the current process.
 *
 * Throws if the host package has no launcher activity.
 */
fun reloadApp() {
    val app = AndroidAppHelper.currentApplication()
    val intent = app.packageManager.getLaunchIntentForPackage(app.packageName)
        ?: error("No launch intent for ${app.packageName}; cannot reload.")
    app.startActivity(Intent.makeRestartActivityTask(intent.component))
    exitProcess(0)
}

/**
 * Namespaced [android.util.Log] facade. Create an instance via [logger].
 *
 * ```kotlin
 * val log = logger("myTweak")
 * log.i { "hello $expensive" }
 * log.e(throwable) { "failed: $msg" }
 * ```
 */
@Suppress("UNUSED")
class Logger(@PublishedApi internal val namespace: String?) {
    @PublishedApi
    internal fun fmt(msg: String): String = if (namespace != null) "$namespace - $msg" else msg

    inline fun v(t: Throwable? = null, msg: () -> String) = Log.v(TAG, fmt(msg()), t)
    inline fun d(t: Throwable? = null, msg: () -> String) = Log.d(TAG, fmt(msg()), t)
    inline fun i(t: Throwable? = null, msg: () -> String) = Log.i(TAG, fmt(msg()), t)
    inline fun w(t: Throwable? = null, msg: () -> String) = Log.w(TAG, fmt(msg()), t)
    inline fun e(t: Throwable? = null, msg: () -> String) = Log.e(TAG, fmt(msg()), t)

    fun v(msg: String, t: Throwable? = null) = Log.v(TAG, fmt(msg), t)
    fun d(msg: String, t: Throwable? = null) = Log.d(TAG, fmt(msg), t)
    fun i(msg: String, t: Throwable? = null) = Log.i(TAG, fmt(msg), t)
    fun w(msg: String, t: Throwable? = null) = Log.w(TAG, fmt(msg), t)
    fun e(msg: String, t: Throwable? = null) = Log.e(TAG, fmt(msg), t)

    companion object {
        const val TAG = "RevengeXposed"
    }
}

/** Returns a [Logger] tagged with [namespace]. */
fun logger(namespace: String): Logger = Logger(namespace)
