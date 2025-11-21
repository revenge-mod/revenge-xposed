package io.github.revenge.xposed.modules.no_track

import android.content.Context
import de.robv.android.xposed.callbacks.XC_LoadPackage
import io.github.revenge.xposed.Module
import io.github.revenge.xposed.Utils.Log

/**
 * Hooks Discord's crash reporting to disable Sentry initialization.
 * While Discord doesn't have Sentry auto-initialization on by default, we still hook the content provider just to be safe.
 */
object BlockCrashReportingModule : Module() {
    override fun onLoad(packageParam: XC_LoadPackage.LoadPackageParam) = with(packageParam) {
        val crashReportingClass = classLoader.safeLoadClass("com.discord.crash_reporting.CrashReporting")
        crashReportingClass?.apply {
            // Hooking this will result in a crash after a few seconds, since Discord expects initialization to complete when setting a Sentry tag.
            // So we also hook isDisabled to make sure those calls are no-ops.
            hookMethod(
                "init", Context::class.java, String::class.java
            ) {
                before {
                    Log.i("Blocked CrashReporting initialization")
                    result = null
                }
            }

            // This only exists on 30720x and above
            runCatching {
                hookMethod("isDisabled") {
                    before {
                        Log.i("Forced CrashReporting.isDisabled() to true")
                        result = true
                    }
                }
            }
        }

        val sentryInitProviderClass = classLoader.safeLoadClass("io.sentry.android.core.SentryInitProvider")
        sentryInitProviderClass?.hookMethod("onCreate") {
            before {
                Log.i("Blocked SentryInitProvider initialization")
                result = true
            }
        }

        return@with
    }
}