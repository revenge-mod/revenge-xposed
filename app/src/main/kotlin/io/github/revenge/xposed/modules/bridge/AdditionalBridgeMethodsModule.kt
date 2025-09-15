package io.github.revenge.xposed.modules.bridge

import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.widget.Toast
import io.github.revenge.xposed.Module

class AdditionalBridgeMethodsModule : Module() {
    override fun onContext(context: Context) = with(context) {
        BridgeModule.registerMethod("revenge.alertError") {
            val (error, version) = it
            val app = getAppInfo()
            val errorString = "$error"

            val clipboard = applicationContext.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("Stack Trace", errorString)

            AlertDialog.Builder(this)
                .setTitle("Revenge Error")
                .setMessage(
                    """
                    Revenge: $version
                    ${app.name}: ${app.version} (${app.versionCode})
                    Device: ${Build.MANUFACTURER} ${Build.MODEL}
                    
                    
                """.trimIndent() + errorString
                )
                .setPositiveButton("OK") { dialog, _ ->
                    dialog.dismiss()
                }
                .setNeutralButton("Copy") { dialog, _ ->
                    clipboard.setPrimaryClip(clip)
                    Toast.makeText(applicationContext, "Copied stack trace", Toast.LENGTH_SHORT).show()
                    dialog.dismiss()
                }
                .show()

            null
        }
    }
}