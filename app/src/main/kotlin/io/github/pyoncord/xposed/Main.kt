package io.github.pyoncord.xposed

import android.app.Activity
import android.content.res.AssetManager
import android.content.res.Resources
import android.util.Log
import android.os.Bundle
import android.widget.Toast
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.callbacks.XC_LoadPackage
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.client.engine.cio.*
import io.ktor.client.statement.*
import io.ktor.client.plugins.*
import io.ktor.http.*
import kotlinx.coroutines.*
import kotlinx.serialization.*
import kotlinx.serialization.json.*
import java.io.File

@Serializable
data class CustomLoadUrl(
    val enabled: Boolean,
    val url: String
)

@Serializable
data class LoaderConfig(
    val customLoadUrl: CustomLoadUrl
)

class Main : IXposedHookLoadPackage {
    private val pyonModules: Array<PyonModule> = arrayOf(
        ThemeModule(),
        SysColorsModule(),
        FontsModule(),
        LogBoxModule()
    )

    fun buildLoaderJsonString(): String {
        val obj = buildJsonObject {
            put("loaderName", "BunnyXposed")
            put("loaderVersion", BuildConfig.VERSION_NAME)

            for (module in pyonModules) {
                module.buildJson(this)
            }
        }

        return Json.encodeToString(obj)
    }

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        val reactActivity = runCatching {
            lpparam.classLoader.loadClass("com.discord.react_activities.ReactActivity")
        }.getOrElse { return } // Package is not our the target app, return

        var activity: Activity? = null
        val onActivityCreateCallback = mutableSetOf<(activity: Activity) -> Unit>()

        XposedBridge.hookMethod(reactActivity.getDeclaredMethod("onCreate", Bundle::class.java), object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                activity = param.thisObject as Activity
                onActivityCreateCallback.forEach { cb -> cb(activity) }
                onActivityCreateCallback.clear()
            }
        })

        init(lpparam) { cb ->
            if (activity != null) cb(activity)
            else onActivityCreateCallback.add(cb)
        }
    }

    private fun init(
        param: XC_LoadPackage.LoadPackageParam,
        onActivityCreate: ((activity: Activity) -> Unit) -> Unit
    ) = with (param) {
        val catalystInstanceImpl = classLoader.loadClass("com.facebook.react.bridge.CatalystInstanceImpl")

        for (module in pyonModules) module.onInit(param)

        val loadScriptFromAssets = catalystInstanceImpl.getDeclaredMethod(
            "loadScriptFromAssets",
            AssetManager::class.java,
            String::class.java,
            Boolean::class.javaPrimitiveType
        ).apply { isAccessible = true }

        val loadScriptFromFile = catalystInstanceImpl.getDeclaredMethod(
            "loadScriptFromFile",
            String::class.java,
            String::class.java,
            Boolean::class.javaPrimitiveType
        ).apply { isAccessible = true }

        val setGlobalVariable = catalystInstanceImpl.getDeclaredMethod(
            "setGlobalVariable",
            String::class.java,
            String::class.java
        ).apply { isAccessible = true }

        val cacheDir = File(appInfo.dataDir, "cache/pyoncord").apply { mkdirs() }
        val filesDir = File(appInfo.dataDir, "files/pyoncord").apply { mkdirs() }

        val preloadsDir = File(filesDir, "preloads").apply { mkdirs() }
        val bundle = File(cacheDir, "bundle.js")
        val etag = File(cacheDir, "etag.txt")

        val configFile = File(filesDir, "loader.json")

        val config = try {
            if (!configFile.exists()) throw Exception()
            val json = Json { ignoreUnknownKeys = true }
            json.decodeFromString(configFile.readText())
        } catch (_: Exception) {
            LoaderConfig(
                customLoadUrl = CustomLoadUrl(
                    enabled = false,
                    url = "" // Not used
                )
            )
        }

        val scope = MainScope()
        val httpJob = scope.async(Dispatchers.IO) {
            try {
                val client = HttpClient(CIO) {
                    expectSuccess = true
                    install(HttpTimeout) {
                        requestTimeoutMillis = if (bundle.exists()) 5000 else 10000
                    }
                    install(UserAgent) { agent = "BunnyXposed" }
                }

                val url = 
                    if (config.customLoadUrl.enabled) config.customLoadUrl.url 
                    else "https://raw.githubusercontent.com/pyoncord/detta-builds/main/bunny.min.js"

                Log.e("Bunny", "Fetching JS bundle from $url")
                
                val response: HttpResponse = client.get(url) {
                    headers { 
                        if (etag.exists() && bundle.exists()) {
                            append(HttpHeaders.IfNoneMatch, etag.readText())
                        }
                    }
                }

                bundle.writeBytes(response.body())
                if (response.headers["Etag"] != null) {
                    etag.writeText(response.headers["Etag"]!!)
                }
                else if (etag.exists()) {
                    // This is called when server does not return an E-tag, so clear em
                    etag.delete()
                }

                return@async
            } catch (e: RedirectResponseException) {
                if (e.response.status != HttpStatusCode.NotModified) throw e
                Log.e("Bunny", "Server responded with status code 304 - no changes to file")
            } catch (e: Throwable) {
                onActivityCreate { activity ->
                    activity.runOnUiThread {
                        Toast.makeText(
                            activity.applicationContext,
                            "Failed to fetch JS bundle, Bunny may not load!",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }

                Log.e("Bunny", "Failed to download bundle", e)
            }
        }

        val patch = object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                runBlocking { httpJob.join() }

                XposedBridge.invokeOriginalMethod(
                    setGlobalVariable, 
                    param.thisObject, 
                    arrayOf("__PYON_LOADER__", buildLoaderJsonString())
                )

                preloadsDir
                    .walk()
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

        XposedBridge.hookMethod(loadScriptFromAssets, patch)
        XposedBridge.hookMethod(loadScriptFromFile, patch)

        // Fighting the side effects of changing the package name
        if (packageName != "com.discord") {
            val getIdentifier = Resources::class.java.getDeclaredMethod(
                "getIdentifier", 
                String::class.java,
                String::class.java,
                String::class.java
            )

            XposedBridge.hookMethod(getIdentifier, object: XC_MethodHook() {
                override fun beforeHookedMethod(mhparam: MethodHookParam) = with(mhparam) {
                    if (args[2] == param.packageName) args[2] = "com.discord"
                }
            })
        }
    }
}