package io.github.revenge.xposed.tweaks.plugins.internal

import android.app.AlertDialog
import io.github.revenge.plugins.API_VERSION
import io.github.revenge.plugins.PluginManifest
import io.github.revenge.xposed.hook
import io.github.revenge.xposed.method
import io.github.revenge.xposed.tweaks.plugins.InternalPluginFlags

private val manifest = PluginManifest(
    id = "revenge.discord.prevent-ota-updates",
    name = "Prevent OTA Updates",
    description = "Prevents Discord from downloading and running OTA updates.",
    author = "Revenge",
    icon = "DenyIcon",
    version = API_VERSION,
)

internal val preventOtaUpdatesPlugin =
    internalPlugin(manifest, setOf(InternalPluginFlags.INTERNAL)) {
        start {
            if (enabledLate) {
                withAppActivity {
                    it.runOnUiThread {
                        AlertDialog.Builder(it)
                            .setTitle("You may not need this plugin")
                            .setMessage(
                                """
                                OTA updates won't break most plugins and will provide you with bug fixes and new features.
                                Only use this if you have a plugin that can't run with OTA updates.
                                
                                This won't clear downloaded OTA updates, but only prevent them from running.
                            """.trimIndent()
                            )
                            .setPositiveButton("Enable anyway") { d, _ -> d.dismiss(); requireReload() }
                            .setNegativeButton("Cancel") { d, _ -> d.dismiss(); disable() }
                            .setOnCancelListener { d -> d.dismiss(); disable() }
                            .show()
                    }
                }

                return@start
            }

            val function0Class = classLoader.loadClass("kotlin.jvm.functions.Function0")
            val bundleUpdaterClass = classLoader.loadClass("com.discord.bundle_updater.BundleUpdater")

            bundleUpdaterClass.method("checkForUpdate", Int::class.javaPrimitiveType, function0Class).hook {
                before {
                    log.i("OTA update check prevented")
                    result = null
                }
            }

            bundleUpdaterClass.method("getBundle").hook {
                after {
                    if (result != null) log.i("Prevented OTA update from running")
                    result = null
                }
            }
        }

        stop {
            requireReload()
        }
    }