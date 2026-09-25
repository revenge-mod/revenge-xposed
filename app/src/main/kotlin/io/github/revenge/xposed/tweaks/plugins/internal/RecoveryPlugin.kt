package io.github.revenge.xposed.tweaks.plugins.internal

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.widget.Toast
import io.github.revenge.plugins.API_VERSION
import io.github.revenge.plugins.PluginManifest
import io.github.revenge.plugins.PluginScope
import io.github.revenge.reloadApp
import io.github.revenge.xposed.*
import io.github.revenge.xposed.api.registerNativeMethod
import io.github.revenge.xposed.tweaks.RevengeUpdater
import io.github.revenge.xposed.tweaks.plugins.PluginStatesStore
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
            // User stuck in Recovery mode. It is only ever intended to be one-shot.
            if (PluginStatesStore.activeSlotId == PluginStatesStore.DEFAULTS_SLOT) {
                PluginStatesStore.setActiveSlot(appInfo.dataDir, PluginStatesStore.PRIMARY_SLOT, false)
            }

            addBridgeMethods()
            recoveryGestureHook()
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
                    PluginStatesStore.setActiveSlot(
                        context.dataDir.absolutePath,
                        PluginStatesStore.DEFAULTS_SLOT,
                        oneShot = true,
                    )
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

fun PluginScope.addBridgeMethods() {
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

fun recoveryGestureHook() {
    var holdRunnable: Runnable? = null
    val handler = Handler(Looper.getMainLooper())

    val hook = Activity::class.java.method("dispatchTouchEvent", MotionEvent::class.java).hook {
        before {
            val event = param.args[0] as MotionEvent
            val activity = param.thisObject as Activity

            if (event.pointerCount == 2) {
                when (event.actionMasked) {
                    // 3s hold timer when 2nd finger touches screen
                    MotionEvent.ACTION_POINTER_DOWN -> {
                        handler.postDelayed({
                            Toast.makeText(activity, "Keep holding to trigger Recovery Options...", Toast.LENGTH_SHORT)
                                .show()
                        }, 1500)

                        holdRunnable = { showRecoveryAlert(activity) }
                        handler.postDelayed(holdRunnable, 3000)
                    }

                    // Lifted finger early
                    MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_CANCEL -> {
                        holdRunnable?.let { handler.removeCallbacks(it) }
                    }
                }
            }
        }
    }

    // Stop listening after 3s
    handler.postDelayed({
        hook.unhook()
    }, 3000)
}
