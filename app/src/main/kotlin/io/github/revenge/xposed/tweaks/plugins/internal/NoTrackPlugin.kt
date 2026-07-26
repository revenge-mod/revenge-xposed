package io.github.revenge.xposed.tweaks.plugins.internal

import android.content.Context
import io.github.revenge.plugins.API_VERSION
import io.github.revenge.plugins.PluginManifest
import io.github.revenge.xposed.hook
import io.github.revenge.xposed.loadClassOrNull
import io.github.revenge.xposed.method
import io.github.revenge.xposed.tweaks.plugins.InternalPluginFlags

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

            classLoader.loadClassOrNull("com.discord.crash_reporting.CrashReporting")?.apply {
                // This only exists on 30720x (307+) and above.
                runCatching {
                    method("isDisabled").hook {
                        before {
                            log.i("Forced CrashReporting.isDisabled() to true")
                            result = true
                        }
                    }
                }.onFailure {
                    // In older versions, this hook works fine.
                    // Hooking this on 30720x (307+) will result in a crash after a few seconds,
                    // since Discord asserts initialization when setting a Sentry tag before checking isDisabled().
                    // On around 33020x (330+), this hook only works because Discord catches the error themselves.
                    // @TODO: We can remove this hook once we drop support for legacy versions.
                    runCatching {
                        method("init", Context::class.java, String::class.java).hook {
                            before {
                                log.i("Blocked CrashReporting initialization")
                                result = null
                            }
                        }
                    }
                }
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

            classLoader.loadClassOrNull("com.discord.deep_link.DeepLinks")?.apply {
                runCatching {
                    method("init", Context::class.java).hook {
                        before {
                            log.i("Blocked DeepLinks tracking initialization")
                            result = null
                        }
                    }
                }
            }
        }
    }