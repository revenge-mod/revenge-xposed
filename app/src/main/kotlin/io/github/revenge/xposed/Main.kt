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

class Main : IXposedHookLoadPackage {

    private val modules = listOf(
        ThemeModule(),
        SysColorsModule(),
        FontsModule(),
        LogBoxModule()
    )

    private fun buildLoaderJsonString(): String = Json.encodeToString(
        buildJsonObject {
            put("loaderName", "RevengeXposed")
            put("loaderVersion", BuildConfig.VERSION_NAME)
            modules.forEach { it.buildJson(this) }
        }
    )

    override fun handleLoadPackage(param: XC_LoadPackage.LoadPackageParam) = with(param) {
        val reactActivity = classLoader.safeLoadClass("com.discord.react_activities.ReactActivity")
            ?: return

        var activity: Activity? = null
        val onActivityCreateCallbacks = mutableSetOf<(Activity) -> Unit>()

        reactActivity.hookMethod("onCreate", Bundle::class.java) {
            before {
                activity = it.thisObject as Activity
                onActivityCreateCallbacks.forEach { cb -> cb(activity) }
                onActivityCreateCallbacks.clear()
            }
        }

        init(param) { callback ->
            activity?.let(callback) ?: onActivityCreateCallbacks.add(callback)
        }
    }

    private fun init(
        param: XC_LoadPackage.LoadPackageParam,
        onActivityCreate: ((Activity) -> Unit) -> Unit
    ) = with(param) {
        listOf(
            "com.facebook.react.runtime.ReactInstance$1",
            "com.facebook.react.bridge.CatalystInstanceImpl"
        ).mapNotNull { classLoader.safeLoadClass(it) }
            .forEach { hookLoadScript(it, appInfo, onActivityCreate) }

        modules.forEach { it.onInit(param) }

        // Fix resource package name mismatch
        if (packageName != "com.discord") {
            Resources::class.java.hookMethod(
                "getIdentifier",
                String::class.java,
                String::class.java,
                String::class.java
            ) {
                before { mh ->
                    if (mh.args[2] == packageName) mh.args[2] = "com.discord"
                }
            }
        }
    }

    private fun hookLoadScript(
        instance: Class<*>,
        appInfo: ApplicationInfo,
        onActivityCreate: ((Activity) -> Unit) -> Unit
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

        val cacheDir = File(appInfo.dataDir, "cache/pyoncord").apply { mkdirs() }
        val filesDir = File(appInfo.dataDir, "files/pyoncord").apply { mkdirs() }
        val preloadsDir = File(filesDir, "preloads").apply { mkdirs() }
        val bundle = File(cacheDir, "bundle.js")
        val etag = File(cacheDir, "etag.txt")
        val configFile = File(filesDir, "loader.json")

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
                        writeText("this[${Json.encodeToString(key)}]=$json")
                        XposedBridge.invokeOriginalMethod(
                            loadScriptFromFile,
                            param.thisObject,
                            arrayOf(absolutePath, absolutePath, param.args[2])
                        )
                        delete()
                    }
                }
            }

        val config = runCatching {
            Json { ignoreUnknownKeys = true }
                .decodeFromString<LoaderConfig>(configFile.readText())
        }.getOrDefault(LoaderConfig())

        val scope = MainScope()
        val httpJob = scope.async(Dispatchers.IO) {
            runCatching {
                val client = HttpClient(CIO) {
                    expectSuccess = true
                    install(HttpTimeout) {
                        requestTimeoutMillis = if (bundle.exists()) 5000 else 10000
                    }
                    install(UserAgent) { agent = "RevengeXposed" }
                }

                val url = config.customLoadUrl.takeIf { it.enabled }?.url
                    ?: "https://github.com/revenge-mod/revenge-bundle/releases/latest/download/revenge.min.js"

                Log.e("Revenge", "Fetching JS bundle from $url")

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
                    Log.e("Revenge", "Server responded with 304 - no changes")
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
                    Log.e("Revenge", "Failed to download bundle", e)
                }
            }
        }

        val patch = object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                runBlocking { httpJob.join() }
                setGlobalVariable(param, "__PYON_LOADER__", buildLoaderJsonString())

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

        listOf(loadScriptFromAssets, loadScriptFromFile).forEach { it.hook(patch) }
    }

    private fun ClassLoader.safeLoadClass(name: String): Class<*>? =
        runCatching { loadClass(name) }.getOrNull()

    private fun Class<*>.method(
        name: String,
        vararg params: Class<*>?
    ): java.lang.reflect.Method =
        getDeclaredMethod(name, *params).apply { isAccessible = true }

    private fun java.lang.reflect.Method.hook(hook: XC_MethodHook) =
        XposedBridge.hookMethod(this, hook)

    private fun Class<*>.hookMethod(
        name: String,
        vararg params: Class<*>?,
        block: MethodHookBuilder.() -> Unit
    ) = method(name, *params).hook(MethodHookBuilder().apply(block).build())

    private class MethodHookBuilder {
        private var beforeHook: ((XC_MethodHook.MethodHookParam) -> Unit)? = null
        fun before(block: (XC_MethodHook.MethodHookParam) -> Unit) {
            beforeHook = block
        }

        fun build() = object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                beforeHook?.invoke(param)
            }
        }
    }
}