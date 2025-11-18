package io.github.revenge.xposed.modules

import android.content.res.AssetManager
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.callbacks.XC_LoadPackage
import io.github.revenge.xposed.BuildConfig
import io.github.revenge.xposed.Constants
import io.github.revenge.xposed.Module
import io.github.revenge.xposed.Utils.Companion.JSON
import io.github.revenge.xposed.modules.HookScriptLoaderModule.PRELOADS_DIR
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File

// TODO: Remove this redundant module and merge its functionality into AdditionalBridgeMethodsModule if needed
class PayloadGlobalModule(private val modules: List<Module>) : Module() {
    private companion object {
        const val GLOBAL_NAME = "__PYON_LOADER__"
    }

    private fun getPayloadString(): String = JSON.encodeToString(
        buildJsonObject {
            put("loaderName", Constants.LOADER_NAME)
            put("loaderVersion", BuildConfig.VERSION_NAME)
            @Suppress("DEPRECATION") for (module in modules) module.buildPayload(this)
        })

    override fun onLoad(packageParam: XC_LoadPackage.LoadPackageParam) = with(packageParam) {
        val catalystInstance = classLoader.safeLoadClass("com.facebook.react.bridge.CatalystInstanceImpl")
        val scriptLoader = classLoader.safeLoadClass("com.facebook.react.runtime.ReactInstance\$loadJSBundle$1")
            ?: classLoader.safeLoadClass("com.facebook.react.runtime.ReactInstance$1")

        val setGlobalVariable: (XC_MethodHook.MethodHookParam, String, String) -> Unit = { param, key, json ->
            runCatching {
                // Attempt old CatalystInstanceImpl method
                catalystInstance!!.method(
                    "setGlobalVariable", String::class.java, String::class.java
                ).invoke(param.thisObject, key, json)
            }.onFailure {
                // Failed (likely because it's a stub), use a workaround
                val preloadsDir = File("${appInfo.dataDir}/${Constants.FILES_DIR}", PRELOADS_DIR)
                File(preloadsDir, "rv_globals_$key.js").apply {
                    writeText("this[${JSON.encodeToString(key)}]=$json")

                    XposedBridge.invokeOriginalMethod(
                        scriptLoader!!.method(
                            "loadScriptFromFile",
                            String::class.java,
                            String::class.java,
                            Boolean::class.javaPrimitiveType
                        ), param.thisObject, arrayOf(absolutePath, absolutePath, param.args[2])
                    )

                    delete()
                }
            }
        }

        val hook = MethodHookBuilder().run {
            before {
                setGlobalVariable(param, GLOBAL_NAME, getPayloadString())
            }

            build()
        }

        listOf(catalystInstance, scriptLoader).forEach { if (it != null) hook(it, hook) }
    }

    private fun hook(instance: Class<*>, hook: XC_MethodHook) {
        instance.method(
            "loadScriptFromAssets", AssetManager::class.java, String::class.java, Boolean::class.javaPrimitiveType,
        ).hook(hook)

        instance.method(
            "loadScriptFromFile", String::class.java, String::class.java, Boolean::class.javaPrimitiveType
        ).hook(hook)
    }
}