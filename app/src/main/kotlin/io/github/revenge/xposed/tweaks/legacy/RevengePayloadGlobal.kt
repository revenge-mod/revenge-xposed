@file:Suppress("DEPRECATION")

package io.github.revenge.xposed.tweaks.legacy

import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import io.github.revenge.Logger
import io.github.revenge.xposed.*
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File

@Deprecated(
    "Payloads will be replaced by synchronous Revenge bridge methods.",
    level = DeprecationLevel.WARNING,
)
object RevengePayloadBuilder {
    private val contributors: MutableList<JsonObjectBuilder.() -> Unit> = mutableListOf()

    fun contribute(block: JsonObjectBuilder.() -> Unit) {
        contributors += block
    }

    internal fun build(): String = RevengeJson.encodeToString(
        buildJsonObject {
            put("loaderName", RevengeConstants.LOADER_NAME)
            put("loaderVersion", RevengeConstants.LOADER_VERSION)
            for (c in contributors) c()
        },
    )
}

/**
 * Sets the `__PYON_LOADER__` JS global before the bundle runs.
 */
@Deprecated(
    "Payloads will be replaced by synchronous Revenge bridge methods.",
    level = DeprecationLevel.WARNING,
)
val revengePayloadGlobal by tweak {
    val log: Logger = this.log
    val dataDir = appInfo.dataDir
    // Bridge
    val catalystInstance = classLoader.loadClassOrNull("com.facebook.react.bridge.CatalystInstanceImpl")
    // Bridgeless
    val scriptLoader = classLoader.loadClassOrNull("com.facebook.react.runtime.ReactInstance\$loadJSBundle$1")
        ?: classLoader.loadClassOrNull("com.facebook.react.runtime.ReactInstance$1")

    val assetManagerClass = Class.forName("android.content.res.AssetManager")

    val setGlobalVariable: (param: XC_MethodHook.MethodHookParam, key: String, json: String) -> Unit =
        { param, key, json ->
            runCatching {
                catalystInstance!!.method(
                    "setGlobalVariable", String::class.java, String::class.java,
                ).invoke(param.thisObject, key, json)
            }.onFailure {
                // Bridgeless
                val preloadsDir = File("$dataDir/${RevengeConstants.FILES_DIR}", RevengeConstants.PRELOADS_DIR)
                if (!preloadsDir.isDirectory) {
                    preloadsDir.delete()
                    preloadsDir.mkdirs()
                }
                File(preloadsDir, "rv_globals_$key.js").apply {
                    writeText("this[${JsonPrimitive(key)}]=$json")
                    try {
                        XposedBridge.invokeOriginalMethod(
                            scriptLoader!!.method(
                                "loadScriptFromFile",
                                String::class.java,
                                String::class.java,
                                Boolean::class.javaPrimitiveType,
                            ),
                            param.thisObject,
                            arrayOf<Any?>(absolutePath, absolutePath, param.args[2]),
                        )
                    } catch (e: Throwable) {
                        log.e("rv_globals_$key.js preload injection failed", e)
                    } finally {
                        delete()
                    }
                }
            }
        }

    val builder = methodHook {
        before {
            @Suppress("DEPRECATION")
            setGlobalVariable(param, "__PYON_LOADER__", RevengePayloadBuilder.build())
        }
    }

    listOf(catalystInstance, scriptLoader).forEach { cls ->
        if (cls == null) return@forEach
        runCatching {
            cls.method(
                "loadScriptFromAssets",
                assetManagerClass,
                String::class.java,
                Boolean::class.javaPrimitiveType,
            ).hook(builder.build())
        }.onFailure { log.d("loadScriptFromAssets not present on ${cls.name}; skipping") }

        runCatching {
            cls.method(
                "loadScriptFromFile",
                String::class.java,
                String::class.java,
                Boolean::class.javaPrimitiveType,
            ).hook(builder.build())
        }.onFailure { log.d("loadScriptFromFile not present on ${cls.name}; skipping") }
    }
}
