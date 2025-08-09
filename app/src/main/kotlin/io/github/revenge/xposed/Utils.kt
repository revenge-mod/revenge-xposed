package io.github.revenge.xposed

import kotlinx.serialization.json.Json

class Utils {
    companion object {
        val JSON = Json { ignoreUnknownKeys = true }
    }

    object Log {
        fun e(msg: String) = android.util.Log.e(Constants.LOG_TAG, msg)
        fun e(msg: String, throwable: Throwable) = android.util.Log.e(Constants.LOG_TAG, msg, throwable)
        fun i(msg: String) = android.util.Log.i(Constants.LOG_TAG, msg)
        fun i(msg: String, throwable: Throwable) = android.util.Log.i(Constants.LOG_TAG, msg, throwable)
    }
}