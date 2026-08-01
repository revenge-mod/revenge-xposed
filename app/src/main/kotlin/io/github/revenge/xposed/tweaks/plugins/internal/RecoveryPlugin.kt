package io.github.revenge.xposed.tweaks.plugins.internal

import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.widget.Toast
import io.github.revenge.plugins.API_VERSION
import io.github.revenge.plugins.PluginManifest
import io.github.revenge.reloadApp
import io.github.revenge.xposed.RevengeConstants
import io.github.revenge.xposed.api.registerNativeMethod
import io.github.revenge.xposed.tweaks.RevengeUpdater
import io.github.revenge.xposed.tweaks.plugins.InternalPluginFlags
import io.github.revenge.xposed.tweaks.plugins.PluginStatesStore
import io.github.revenge.xposed.versionCode
import io.github.revenge.xposed.versionName
import java.io.File

private val manifest = PluginManifest(
    id = "revenge.recovery",
    name = "Recovery",
    description = "Handles errors and provides troubleshooting options for Revenge.",
    author = "Revenge",
    icon = "ShieldIcon",
    version = API_VERSION,
)

internal val recoveryPlugin =
    internalPlugin(manifest, setOf(InternalPluginFlags.INTERNAL, InternalPluginFlags.ESSENTIAL)) {
        start {
            withAppActivity { act ->
                registerNativeMethod("revenge.showRecoveryAlert") {
                    showRecoveryAlert(act)
                    null
                }

                registerNativeMethod("revenge.alertError") {
                    val (error, version) = it
                    val errorString = "$error"

                    val clipboard = act.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    val clip = ClipData.newPlainText("Stack Trace", errorString)

                    AlertDialog.Builder(act)
                        .setTitle("Revenge Error")
                        .setMessage(
                            """
                        Revenge: $version
                        Discord: ${act.versionName()} (${act.versionCode()})
                        Device: ${Build.MANUFACTURER} ${Build.MODEL}
                        
                        
                    """.trimIndent() + errorString
                        )
                        .setPositiveButton(android.R.string.ok) { dialog, _ -> dialog.dismiss() }
                        .setNeutralButton(android.R.string.copy) { dialog, _ ->
                            @Suppress("UsePropertyAccessSyntax")
                            clipboard.setPrimaryClip(clip)
                            Toast.makeText(act, "Copied stack trace", Toast.LENGTH_SHORT).show()
                            dialog.dismiss()
                        }
                        .setNegativeButton("Recovery") { dialog, _ ->
                            showRecoveryAlert(act)
                            dialog.dismiss()
                        }
                        .show()

                    null
                }
            }
        }
    }

/**
 * For the actual shake-gesture hook, you should be looking at [io.github.revenge.xposed.tweaks.discordDevSupport].
 */
fun showRecoveryAlert(context: Context) {
    AlertDialog.Builder(context)
        .setTitle("Revenge Recovery Options")
        .setItems(
            arrayOf("Reload", "Enter Recovery Mode", "Delete Script", "Reset Loader Config"),
        ) { _, which ->
            when (which) {
                0 -> reloadApp()

                1 -> {
                    PluginStatesStore.requestDefaultsOnlyBoot(context.dataDir.absolutePath)
                    reloadApp()
                }

                2 -> {
                    val bundleFile = File(
                        context.dataDir,
                        "${RevengeConstants.CACHE_DIR}/${RevengeConstants.MAIN_SCRIPT_FILE}",
                    )
                    if (bundleFile.exists()) bundleFile.delete()
                    reloadApp()
                }

                3 -> {
                    RevengeUpdater.resetLoaderConfig()
                    reloadApp()
                }
            }
        }
        .show()
}