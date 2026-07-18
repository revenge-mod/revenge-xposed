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

            classLoader.loadClassOrNull("com.discord.crash_reporting.CrashReporting")?.let { cls ->
                runCatching {
                    cls.method("init", Context::class.java, String::class.java).hook {
                        before {
                            log.i("Blocked CrashReporting initialization")
                            result = null
                        }
                    }
                }

                // This only exists on 30720x and above.
                runCatching {
                    cls.method("isDisabled").hook {
                        before {
                            log.i("Forced CrashReporting.isDisabled() to true")
                            result = true
                        }
                    }
                }
            }

            classLoader.loadClassOrNull("io.sentry.android.core.SentryInitProvider")?.let { cls ->
                runCatching {
                    cls.method("onCreate").hook {
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

            classLoader.loadClassOrNull("com.discord.deep_link.DeepLinks")?.let { cls ->
                runCatching {
                    cls.method("init", Context::class.java).hook {
                        before {
                            log.i("Blocked DeepLinks tracking initialization")
                            result = null
                        }
                    }
                }
            }
        }
    }