package io.github.revenge.xposed.tweaks.base

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Bundle
import io.github.revenge.xposed.RevengeConstants
import io.github.revenge.xposed.hook
import io.github.revenge.xposed.method
import io.github.revenge.xposed.tweak
import java.lang.ref.WeakReference

@Volatile
private var contextRef = WeakReference<Context>(null)

@Volatile
private var activityRef = WeakReference<Activity>(null)

/** Guards both subscriber lists so a concurrent subscribe can't be lost mid-drain. */
private val subscriberLock = Any()
private val contextSubscribers = mutableListOf<(Context) -> Unit>()
private val activitySubscribers = mutableListOf<(Activity) -> Unit>()

/**
 * Captures [Context] (from `ContextWrapper.attachBaseContext`) and [Activity] (from the target `ReactActivity.onCreate`),
 * so other tweaks can use [withAppContext]/[withAppActivity] to await them.
 */
val lifecycleSupport by tweak {
    val reactActivity = classLoader.loadClass(RevengeConstants.TARGET_ACTIVITY)

    ContextWrapper::class.java.method("attachBaseContext", Context::class.java).hook {
        after {
            val ctx = args[0] as Context
            contextRef = WeakReference(ctx)
            log.i("Got Context")
            drainContextSubscribers(ctx)
        }
    }

    reactActivity.method("onCreate", Bundle::class.java).hook {
        after {
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
