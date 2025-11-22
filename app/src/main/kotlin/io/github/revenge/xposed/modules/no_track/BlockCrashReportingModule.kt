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

            // This only exists on 30720x and above
            runCatching {
                hookMethod("isDisabled") {
                    before {
                        Log.i("Forced CrashReporting.isDisabled() to true")
                        result = true
                    }
                }
            }.onFailure {
                // In older versions, this hook works fine.
                // Hooking this on 30720x will result in a crash after a few seconds,
                // since Discord asserts initialization when setting a Sentry tag.
                hookMethod(
                    "init", Context::class.java, String::class.java
                ) {
                    before {
                        Log.i("Blocked CrashReporting initialization")
                        result = null
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