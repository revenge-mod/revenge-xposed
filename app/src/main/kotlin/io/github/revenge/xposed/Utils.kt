package io.github.revenge.xposed

import android.app.AlertDialog
import android.app.AndroidAppHelper
import android.content.Context
import android.content.Intent
import io.github.revenge.xposed.modules.UpdaterModule
import kotlinx.serialization.json.Json
import java.io.File
import kotlin.system.exitProcess

class Utils {
    companion object {
        val JSON = Json { ignoreUnknownKeys = true }

        fun reloadApp() {
            val application = AndroidAppHelper.currentApplication()
            val intent = application.packageManager.getLaunchIntentForPackage(application.packageName)
            application.startActivity(Intent.makeRestartActivityTask(intent!!.component))
            exitProcess(0)
        }

        fun showRecoveryAlert(context: Context) {
            AlertDialog.Builder(context).setTitle("Revenge Recovery Options")
                .setItems(arrayOf("Reload", "Delete Script", "Reset Loader Config")) { _, which ->
                    when (which) {
                        0 -> {
                            reloadApp()
                        }

                        1 -> {
                            val bundleFile = File(
                                context.dataDir, "${Constants.CACHE_DIR}/${Constants.MAIN_SCRIPT_FILE}"
                            )

                            if (bundleFile.exists()) bundleFile.delete()

                            reloadApp()
                        }

                        2 -> {
                            UpdaterModule.resetLoaderConfig(context)
                            reloadApp()
                        }
                    }
                }.show()
        }
    }

    object Log {
        fun e(msg: String) = android.util.Log.e(Constants.LOG_TAG, msg)
        fun e(msg: String, throwable: Throwable) = android.util.Log.e(Constants.LOG_TAG, msg, throwable)
        fun i(msg: String) = android.util.Log.i(Constants.LOG_TAG, msg)
        fun i(msg: String, throwable: Throwable) = android.util.Log.i(Constants.LOG_TAG, msg, throwable)
        fun w(msg: String) = android.util.Log.w(Constants.LOG_TAG, msg)
        fun w(msg: String, throwable: Throwable) = android.util.Log.w(Constants.LOG_TAG, msg, throwable)
    }
}