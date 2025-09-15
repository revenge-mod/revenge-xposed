package io.github.revenge.xposed

import android.app.AndroidAppHelper
import android.content.Intent
import kotlinx.serialization.json.Json
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