package io.github.revenge.xposed.tweaks

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import io.github.revenge.reloadApp
import io.github.revenge.xposed.RevengeConstants
import io.github.revenge.xposed.tweak
import io.github.revenge.xposed.tweaks.bridge.RevengeBridgeRegistry
import java.io.File

fun showRecoveryAlert(context: Context) {
    AlertDialog.Builder(context)
        .setTitle("Revenge Recovery Options")
        .setItems(arrayOf("Reload", "Delete Script", "Reset Loader Config")) { _, which ->
            when (which) {
                0 -> reloadApp()
                1 -> {
                    val bundleFile = File(
                        context.dataDir,
                        "${RevengeConstants.CACHE_DIR}/${RevengeConstants.MAIN_SCRIPT_FILE}",
                    )
                    if (bundleFile.exists()) bundleFile.delete()
                    reloadApp()
                }

                2 -> {
                    RevengeUpdater.resetLoaderConfig()
                    reloadApp()
                }
            }
        }
        .show()
}

/**
 * Registers `revenge.showRecoveryAlert` once an [Activity] is available.
 *
 * For the actual shake-gesture hook, you should be looking at [discordDevSupport].
 */
val revengeRecovery by tweak {
    withAppActivity { act ->
        RevengeBridgeRegistry.registerMethod("revenge.showRecoveryAlert") {
            showRecoveryAlert(act)
            null
        }
    }
}
