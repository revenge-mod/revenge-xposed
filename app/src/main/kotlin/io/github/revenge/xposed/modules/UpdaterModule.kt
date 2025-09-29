package io.github.revenge.xposed.modules

import android.app.Activity
import android.app.AlertDialog
import android.util.AtomicFile
import android.widget.Toast
import androidx.core.util.writeBytes
import de.robv.android.xposed.callbacks.XC_LoadPackage
import io.github.revenge.xposed.Constants
import io.github.revenge.xposed.Module
import io.github.revenge.xposed.Utils.Companion.JSON
import io.github.revenge.xposed.Utils.Companion.reloadApp
import io.github.revenge.xposed.Utils.Log
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.coroutines.*
import kotlinx.serialization.Serializable
import java.io.File

@Serializable
data class CustomLoadUrl(
    val enabled: Boolean = false, val url: String = ""
)

@Serializable
data class LoaderConfig(
    val customLoadUrl: CustomLoadUrl = CustomLoadUrl()
)

/**
 * Module that updates the JS bundle by downloading it from a remote URL.
 *
 * Shows dialogs when failed allowing retry.
 */
object UpdaterModule : Module() {
    private lateinit var config: LoaderConfig
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var error: Throwable? = null
    private var lastActivity: Activity? = null

    private lateinit var cacheDir: File
    private lateinit var bundle: File
    private lateinit var etag: File

    var job: Job? = null

    private const val TIMEOUT_CACHED = 5000L
    private const val TIMEOUT = 10000L
    private const val ETAG_FILE = "etag.txt"
    private const val CONFIG_FILE = "loader.json"

    private const val DEFAULT_BUNDLE_URL =
        "https://github.com/revenge-mod/revenge-bundle/releases/latest/download/revenge.min.js"

    override fun onLoad(packageParam: XC_LoadPackage.LoadPackageParam) = with(packageParam) {
        cacheDir = File(appInfo.dataDir, Constants.CACHE_DIR).apply { mkdirs() }
        val filesDir = File(appInfo.dataDir, Constants.FILES_DIR).apply { mkdirs() }

        bundle = File(cacheDir, Constants.MAIN_SCRIPT_FILE)
        etag = File(cacheDir, ETAG_FILE)

        val configFile = File(filesDir, CONFIG_FILE)

        config = runCatching {
            if (configFile.exists()) {
                JSON.decodeFromString<LoaderConfig>(configFile.readText())
            } else LoaderConfig()
        }.getOrDefault(LoaderConfig())
    }

    fun downloadScript(activity: Activity? = null) {
        job = scope.launch {
            try {
                HttpClient(CIO) {
                    expectSuccess = false
                    install(UserAgent) { agent = Constants.USER_AGENT }
                    install(HttpRedirect) {}
                }.use { client ->
                    val url = config.customLoadUrl.takeIf { it.enabled }?.url ?: DEFAULT_BUNDLE_URL
                    Log.i("Fetching JS bundle from: $url")

                    val response: HttpResponse = client.get(url) {
                        headers {
                            if (etag.exists() && bundle.exists()) {
                                append(HttpHeaders.IfNoneMatch, etag.readText())
                            }
                        }

                        // Retries don't need timeout
                        if (activity == null) {
                            timeout {
                                requestTimeoutMillis = if (!bundle.exists()) TIMEOUT else TIMEOUT_CACHED
                            }
                        }
                    }

                    when (response.status) {
                        HttpStatusCode.OK -> {
                            val bytes: ByteArray = response.body()
                            AtomicFile(bundle).writeBytes(bytes)

                            val newTag = response.headers[HttpHeaders.ETag]
                            if (!newTag.isNullOrEmpty()) etag.writeText(newTag) else etag.delete()

                            Log.i("Bundle updated (${bytes.size} bytes)")

                            // This is a retry, so we show a dialog
                            if (activity != null) {
                                withContext(Dispatchers.Main) {
                                    AlertDialog.Builder(activity).setTitle("Revenge Update Successful")
                                        .setMessage("A reload is required for changes to take effect.")
                                        .setPositiveButton("Reload") { dialog, _ ->
                                            reloadApp()
                                            dialog.dismiss()
                                        }.setCancelable(false).show()
                                }
                            }
                        }

                        HttpStatusCode.NotModified -> {
                            Log.i("Server responded with 304, no changes")
                        }

                        else -> {
                            throw ResponseException(response, "Received status: ${response.status}")
                        }
                    }
                }
            } catch (e: Throwable) {
                Log.e("Failed to download script", e)
                error = e
                showErrorDialog()
            }
        }
    }

    override fun onActivity(activity: Activity) {
        lastActivity = activity
    }

    fun showErrorDialog() {
        val activity = lastActivity ?: return
        val currentError = error ?: return

        error = null

        activity.runOnUiThread {
            AlertDialog.Builder(activity).setTitle("Revenge Update Failed").setMessage(
                """
                Unable to download the latest version of Revenge.
                This is usually caused by bad network connection.
            
                Error: ${currentError.message ?: currentError.toString()}
                """.trimIndent()
            ).setNegativeButton("Dismiss") { dialog, _ ->
                dialog.dismiss()
            }.setPositiveButton("Retry Update") { dialog, _ ->
                downloadScript(activity)
                Toast.makeText(activity, "Retrying download in background...", Toast.LENGTH_SHORT).show()
                dialog.dismiss()
            }.show()
        }
    }
}