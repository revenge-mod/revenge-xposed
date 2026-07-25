package io.github.revenge.xposed.tweaks.bridge

import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.widget.Toast
import io.github.revenge.bridge.asDelegate
import io.github.revenge.xposed.openFileGuarded
import io.github.revenge.xposed.tweak
import io.github.revenge.xposed.versionCode
import io.github.revenge.xposed.versionName
import java.io.File

/**
 * `revenge.fs.*` + `revenge.alertError` bridge methods.
 */
val additionalBridgeMethods by tweak {
    with(RevengeBridgeRegistry) {
        withAppActivity { act ->
            registerMethod("revenge.alertError") {
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
                        clipboard.setPrimaryClip(clip)
                        Toast.makeText(act, "Copied stack trace", Toast.LENGTH_SHORT).show()
                        dialog.dismiss()
                    }
                    .show()

                null
            }
        }

        withAppContext { ctx ->
            registerMethod("revenge.fs.getConstants") {
                mapOf(
                    "data" to ctx.dataDir.absolutePath,
                    "files" to ctx.filesDir.absolutePath,
                    "cache" to ctx.cacheDir.absolutePath,
                )
            }

            registerMethod("revenge.fs.delete") { args ->
                val argv = args.asDelegate()
                val path by argv.string()
                val f = File(path)
                if (f.isDirectory) f.deleteRecursively() else f.delete()
            }

            registerMethod("revenge.fs.exists") { args ->
                val argv = args.asDelegate()
                val path by argv.string()
                File(path).exists()
            }

            registerMethod("revenge.fs.read") { args ->
                val argv = args.asDelegate()
                val path by argv.string()
                val file = File(path).also { it.openFileGuarded() }
                file.bufferedReader().use { it.readText() }
            }

            registerMethod("revenge.fs.write") { args ->
                val argv = args.asDelegate()
                val path by argv.string()
                val contents by argv.string()
                File(path).apply {
                    if (isDirectory) throw Error("Path is a directory: $path")
                    parentFile?.mkdirs()
                    writeText(contents)
                }

                null
            }
        }
    }
}
