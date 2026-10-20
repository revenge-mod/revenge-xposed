package io.github.revenge.xposed.tweaks.plugins.internal

import android.content.Context
import io.github.revenge.plugins.API_VERSION
import io.github.revenge.plugins.PluginManifest
import io.github.revenge.plugins.PluginScope
import io.github.revenge.xposed.DexFinder
import io.github.revenge.xposed.hook
import io.github.revenge.xposed.loadClassOrNull
import io.github.revenge.xposed.method
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.concurrent.atomic.AtomicBoolean

private val manifest = PluginManifest(
    id = "revenge.no-track",
    name = "No Track",
    description = "Disables Discord and Sentry analytics, and other tracking.",
    author = "Revenge",
    icon = "AnalyticsIcon",
    version = API_VERSION,
)

internal val noTrackPlugin =
    internalPlugin(manifest, setOf(InternalPluginFlags.INTERNAL, InternalPluginFlags.ENABLED_BY_DEFAULT)) {
        start {
            /**
             * Disables Discord's crash reporting + Sentry initialization.
             */

            val crashReportingHooked = classLoader.loadClassOrNull("com.discord.crash_reporting.CrashReporting")?.run {
                // This only exists on 30720x (307+) and above.
                runCatching {
                    method("isDisabled").hook {
                        before {
                            log.i("Forced CrashReporting.isDisabled() to true")
                            result = true
                        }
                    }
                }.recoverCatching {
                    // In older versions, this hook works fine.
                    // Hooking this on 30720x (307+) will result in a crash after a few seconds,
                    // since Discord asserts initialization when setting a Sentry tag before checking isDisabled().
                    // On around 33020x (330+), this hook only works because Discord catches the error themselves.
                    // @TODO: We can remove this hook once we drop support for legacy versions.
                    method("init", Context::class.java, String::class.java).hook {
                        before {
                            log.i("Blocked CrashReporting initialization")
                            result = null
                        }
                    }
                }.isSuccess
            } ?: false

            // Since 352.2 (R8), CrashReporting has neither, so block the Sentry SDK itself.
            // Discord catches errors around the call, and Sentry's static API is a no-op until it's initialized.
            if (!crashReportingHooked) {
                findSentryInit()?.hook {
                    before {
                        log.i("Blocked Sentry initialization")
                        result = null
                    }
                } ?: log.e("Unable to find SentryAndroid.init, so crash reporting stays enabled")
            }

            classLoader.loadClassOrNull("io.sentry.android.core.SentryInitProvider")?.apply {
                runCatching {
                    method("onCreate").hook {
                        before {
                            log.i("Blocked SentryInitProvider initialization")
                            result = true
                        }
                    }
                }
            }

            /**
             * Disables Discord's AppsFlyer deep-links tracking.
             */

            val deepLinks = classLoader.loadClassOrNull("com.discord.deep_link.DeepLinks")
            deepLinks?.apply {
                runCatching {
                    method("init", Context::class.java).hook {
                        before {
                            log.i("Blocked DeepLinks tracking initialization")
                            result = null
                        }
                    }
                }
            }

            // Since 352.2 (R8), DeepLinks is gone, so block AppsFlyer's public API instead.
            if (deepLinks == null) {
                classLoader.loadClassOrNull("com.appsflyer.AppsFlyerLib")?.apply {
                    runCatching { hookAppsFlyerStart(this) }
                        .onFailure { log.e("Unable to block AppsFlyer", it) }
                }
            }
        }
    }

/** Finds `SentryAndroid.init(Context, ILogger, OptionsConfiguration)`, which overloads call. */
private fun findSentryInit(): Method? =
    DexFinder.findMethod {
        matcher {
            modifiers(Modifier.STATIC)
            paramTypes("android.content.Context", null, null)
            usingStrings("Fatal error during SentryAndroid.init(...)")
        }
    }

/**
 * Makes every `start` overload of the AppsFlyer implementation a no-op.
 *
 * [appsFlyerLib] is abstract, and its implementation class is obfuscated, so this hooks the class of the instance
 * that `getInstance` returns.
 */
private fun PluginScope.hookAppsFlyerStart(appsFlyerLib: Class<*>) {
    val hooked = AtomicBoolean(false)

    appsFlyerLib.method("getInstance").hook {
        after {
            val impl = result?.javaClass ?: return@after
            if (!hooked.compareAndSet(false, true)) return@after

            for (start in impl.declaredMethods.filter { it.name == "start" }) {
                start.hook {
                    before {
                        log.i("Blocked AppsFlyer tracking")
                        result = null
                    }
                }
            }
        }
    }
}