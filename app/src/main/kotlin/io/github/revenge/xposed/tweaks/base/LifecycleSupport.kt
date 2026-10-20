package io.github.revenge.xposed.tweaks.base

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Bundle
import io.github.revenge.xposed.Tweak
import io.github.revenge.xposed.hook
import io.github.revenge.xposed.method
import io.github.revenge.xposed.tweak
import java.lang.ref.WeakReference
import java.lang.reflect.Method
import java.util.concurrent.atomic.AtomicBoolean

@Volatile
private var contextRef = WeakReference<Context>(null)

@Volatile
private var activityRef = WeakReference<Activity>(null)

/** Guards both subscriber lists so a concurrent subscribe can't be lost mid-drain. */
private val subscriberLock = Any()
private val contextSubscribers = mutableListOf<(Context) -> Unit>()
private val activitySubscribers = mutableListOf<(Activity) -> Unit>()

/**
 * Captures [Context] (from `ContextWrapper.attachBaseContext`) and [Activity] (from the launcher activity's `onCreate`),
 * so other tweaks can use [withAppContext]/[withAppActivity] to await them.
 *
 * The launcher activity is resolved from the package manager, since its class name isn't stable across Discord versions.
 */
val lifecycleSupport by tweak {
    val activityHooked = AtomicBoolean(false)

    ContextWrapper::class.java.method("attachBaseContext", Context::class.java).hook {
        after {
            val ctx = args[0] as Context
            if (activityHooked.compareAndSet(false, true)) hookLauncherActivity(ctx)

            contextRef = WeakReference(ctx)
            log.i("Got Context")
            drainContextSubscribers(ctx)
        }
    }
}

private fun Tweak.hookLauncherActivity(ctx: Context) {
    val name = try {
        launcherActivityName(ctx)
    } catch (e: Throwable) {
        log.e("Unable to resolve the launcher activity", e)
        return
    }

    // The nearest declaration may belong to a superclass, so filter by the exact class.
    var cls: Class<*>? = classLoader.loadClass(name)
    var onCreate: Method? = null
    while (cls != null && onCreate == null) {
        val current = cls
        onCreate = runCatching { current.method("onCreate", Bundle::class.java) }.getOrNull()
        cls = current.superclass
    }

    if (onCreate == null) {
        log.e("No onCreate found for launcher activity $name")
        return
    }

    log.i("Hooking launcher activity $name (onCreate in ${onCreate.declaringClass.name})")
    onCreate.hook {
        after {
            if (thisObject?.javaClass?.name != name) return@after

            val act = thisObject as Activity
            activityRef = WeakReference(act)
            log.i("Got Activity")

            if (contextRef.get() == null) {
                contextRef = WeakReference(act)
                log.w("Activity created before Context; process may have been recreated")
                drainContextSubscribers(act)
            }
            drainActivitySubscribers(act)
        }
    }
}

/** Returns the class name of the activity that the launcher intent resolves to, following activity aliases. */
private fun launcherActivityName(ctx: Context): String {
    val pm = ctx.packageManager
    val intent = pm.getLaunchIntentForPackage(ctx.packageName)
        ?: error("No launch intent for ${ctx.packageName}")
    val info = pm.resolveActivity(intent, 0)?.activityInfo
        ?: error("Launch intent for ${ctx.packageName} doesn't resolve")
    return info.targetActivity ?: info.name
}

private fun drainContextSubscribers(ctx: Context) {
    val pending = synchronized(subscriberLock) {
        val copy = contextSubscribers.toList()
        contextSubscribers.clear()
        copy
    }
    for (s in pending) s(ctx)
}

private fun drainActivitySubscribers(act: Activity) {
    val pending = synchronized(subscriberLock) {
        val copy = activitySubscribers.toList()
        activitySubscribers.clear()
        copy
    }
    for (s in pending) s(act)
}

/** Tweak or plugin code should use [io.github.revenge.xposed.api.HostScope.withAppContext] instead. */
fun withAppContext(block: (Context) -> Unit) {
    val ctx = synchronized(subscriberLock) {
        contextRef.get() ?: run {
            contextSubscribers += block
            return
        }
    }
    block(ctx)
}

/** Tweak or plugin code should use [io.github.revenge.xposed.api.HostScope.withAppActivity] instead. */
fun withAppActivity(block: (Activity) -> Unit) {
    val act = synchronized(subscriberLock) {
        activityRef.get() ?: run {
            activitySubscribers += block
            return
        }
    }
    block(act)
}
