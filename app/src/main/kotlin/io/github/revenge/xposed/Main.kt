package io.github.revenge.xposed

import android.app.Activity
import android.content.pm.ApplicationInfo
import android.content.res.AssetManager
import android.content.res.Resources
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.callbacks.XC_LoadPackage
import io.github.revenge.xposed.modules.FontsModule
import io.github.revenge.xposed.modules.LogBoxModule
import io.github.revenge.xposed.modules.SysColorsModule
import io.github.revenge.xposed.modules.ThemeModule
import io.github.revenge.xposed.Utils.Companion.JSON
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
import kotlinx.serialization.json.*
import java.io.File

@Serializable
data class CustomLoadUrl(
    val enabled: Boolean = false,
    val url: String = ""
)

@Serializable
data class LoaderConfig(
    val customLoadUrl: CustomLoadUrl = CustomLoadUrl()
)

class Main : Module(), IXposedHookLoadPackage {
    private lateinit var preloadsDir: File
    private lateinit var bundle: File
    private lateinit var onActivityCreate: ((Activity) -> Unit) -> Unit
    private lateinit var httpJob: Deferred<Unit>

    val PRELOADS_DIR = "preloads"

    val ETAG_FILE = "etag.txt"
    val CONFIG_FILE = "loader.json"

    val LOADER_NAME = "RevengeXposed"

    val DEFAULT_BUNDLE_URL = "https://github.com/revenge-mod/revenge-bundle/releases/latest/download/revenge.min.js"

    val TARGET_PACKAGE = "com.discord"
    val TARGET_ACTIVITY = "$TARGET_PACKAGE.react_activities.ReactActivity"

    private val modules = listOf(
        ThemeModule(),
        SysColorsModule(),
        FontsModule(),
        LogBoxModule()
    )

    private fun getPayloadString(): String = JSON.encodeToString(
        buildJsonObject {
            put("loaderName", LOADER_NAME)
            put("loaderVersion", BuildConfig.VERSION_NAME)
            for (module in modules) module.buildPayload(this)
        }
    )

    override fun handleLoadPackage(param: XC_LoadPackage.LoadPackageParam) = with(param) {
        val reactActivity = classLoader.safeLoadClass(TARGET_ACTIVITY)
            ?: return

        var activity: Activity? = null
        val onActivityCreateCallbacks = mutableSetOf<(Activity) -> Unit>()

        reactActivity.hookMethod("onCreate", Bundle::class.java) {
            before {
                activity = thisObject as Activity
                for (cb in onActivityCreateCallbacks) cb(activity)
                onActivityCreateCallbacks.clear()
            }
        }

        onActivityCreate = { callback ->
            activity?.let(callback) ?: onActivityCreateCallbacks.add(callback)
        }

        init(param)
    }

    private fun init(
        param: XC_LoadPackage.LoadPackageParam,
    ) = with(param) {
        val cacheDir = File(appInfo.dataDir, Constants.CACHE_DIR).apply { mkdirs() }
        val filesDir = File(appInfo.dataDir, Constants.FILES_DIR).apply { mkdirs() }

        preloadsDir = File(filesDir, PRELOADS_DIR).apply { mkdirs() }
        bundle = File(cacheDir, Constants.BUNDLE_FILE)

        val etag = File(cacheDir, ETAG_FILE)
        val configFile = File(filesDir, CONFIG_FILE)

        val config = runCatching {
            JSON.decodeFromString<LoaderConfig>(configFile.readText())
        }.getOrDefault(LoaderConfig())

        httpJob = MainScope().async(Dispatchers.IO) {
            runCatching {
                val client = HttpClient(CIO) {
                    expectSuccess = true
                    install(HttpTimeout) {
                        requestTimeoutMillis = if (bundle.exists()) 5000 else 10000
                    }
                    install(UserAgent) { agent = Constants.USER_AGENT }
                }

                val url = config.customLoadUrl.takeIf { it.enabled }?.url
                    ?: DEFAULT_BUNDLE_URL

                Log.i("Fetching JS bundle from $url")

                val response: HttpResponse = client.get(url) {
                    headers {
                        if (etag.exists() && bundle.exists()) {
                            append(HttpHeaders.IfNoneMatch, etag.readText())
                        }
                    }
                }

                bundle.writeBytes(response.body())
                response.headers["Etag"]?.let(etag::writeText) ?: etag.delete()

            }.onFailure { e ->
                if (e is RedirectResponseException &&
                    e.response.status == HttpStatusCode.NotModified
                ) {
                    Log.i("Server responded with 304 - no changes")
                } else {
                    onActivityCreate { activity ->
                        activity.runOnUiThread {
                            Toast.makeText(
                                activity.applicationContext,
                                "Failed to fetch JS bundle, Revenge may not load!",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                    Log.e("Failed to download bundle", e)
                }
            }
        }

        listOf(
            "com.facebook.react.runtime.ReactInstance$1",
            "com.facebook.react.bridge.CatalystInstanceImpl"
        ).mapNotNull { classLoader.safeLoadClass(it) }
            .forEach { hookLoadScript(it) }

        for (module in modules) module.onInit(param)

        // Fix resource package name mismatch
        if (packageName != TARGET_PACKAGE) {
            Resources::class.java.hookMethod(
                "getIdentifier",
                String::class.java,
                String::class.java,
                String::class.java
            ) {
                before {
                    if (args[2] == packageName) args[2] = TARGET_PACKAGE
                }
            }
        }
    }

    private fun hookLoadScript(
        instance: Class<*>
    ) {
        val loadScriptFromAssets = instance.method(
            "loadScriptFromAssets",
            AssetManager::class.java,
            String::class.java,
            Boolean::class.javaPrimitiveType
        )
        val loadScriptFromFile = instance.method(
            "loadScriptFromFile",
            String::class.java,
            String::class.java,
            Boolean::class.javaPrimitiveType
        )

        val setGlobalVariable: (XC_MethodHook.MethodHookParam, String, String) -> Unit =
            { param, key, json ->
                runCatching {
                    instance.method(
                        "setGlobalVariable",
                        String::class.java,
                        String::class.java
                    ).invoke(param.thisObject, key, json)
                }.onFailure {
                    // Bridgeless compatibility
                    File(preloadsDir, "rv_globals_$key.js").apply {
                        writeText("this[${JSON.encodeToString(key)}]=$json")
                        XposedBridge.invokeOriginalMethod(
                            loadScriptFromFile,
                            param.thisObject,
                            arrayOf(absolutePath, absolutePath, param.args[2])
                        )
                        delete()
                    }
                }
            }

        val patch = object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                runBlocking { httpJob.join() }
                setGlobalVariable(param, "__PYON_LOADER__", getPayloadString())

                preloadsDir.walk()
                    .filter { it.isFile && it.extension == "js" }
                    .forEach { file ->
                        XposedBridge.invokeOriginalMethod(
                            loadScriptFromFile,
                            param.thisObject,
                            arrayOf(file.absolutePath, file.absolutePath, param.args[2])
                        )
                    }

                XposedBridge.invokeOriginalMethod(
                    loadScriptFromFile,
                    param.thisObject,
                    arrayOf(bundle.absolutePath, bundle.absolutePath, param.args[2])
                )
            }
        }

        for (method in listOf(loadScriptFromAssets, loadScriptFromFile)) method.hook(patch)
    }

    private fun ClassLoader.safeLoadClass(name: String): Class<*>? =
        runCatching { loadClass(name) }.getOrNull()
}