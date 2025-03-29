package io.github.revenge.xposed

import com.facebook.react.ReactPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.callbacks.XC_LoadPackage
import io.github.revenge.plugin.initPlugins
import io.github.revenge.plugin.pluginsReactPackage
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import java.io.File
import java.util.ArrayList
import java.util.Collections.addAll

class PluginsModule : Module() {
    private val errors: MutableList<String> = mutableListOf()

    @OptIn(ExperimentalSerializationApi::class)
    override fun buildJson(builder: JsonObjectBuilder) {
        builder.apply {
            put("errors", buildJsonArray { 
                errors.forEach { error -> add(
                    JsonPrimitive(error)
                ) }
            })
        }
    }

    override fun init(packageParam: XC_LoadPackage.LoadPackageParam) = with(packageParam) {
        val pluginFilesJson = File(appInfo.dataDir, "files/revenge/plugins.json")
        if (!pluginFilesJson.exists()) return@with

        val pluginFiles = try {
            Json.decodeFromString<List<String>>(pluginFilesJson.readText()).map { File(it) }
        } catch (_: Throwable) {
            errors.add("Failed to parse plugins.json")
            return@with
        }

        // Here, plugins are initialized
        val plugins = try {
            initPlugins(pluginFiles, errors)
        } catch (e: Throwable) {
            errors.add("Failed to load plugins: ${e.message}")
            return@with
        }

        val pluginsReactPackage = pluginsReactPackage(plugins)

        // Hooking the method that returns the list of React packages
        // and adding our own package to it.
        // Our own package is shipping the plugins as NativeModules.

        val getPackagesMethod = classLoader
            .loadClass("com.discord.bridge.DCDPackageList")
            .getDeclaredMethod("getPackages")

        XposedBridge.hookMethod(getPackagesMethod, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                @Suppress("UNCHECKED_CAST")
                val reactPackages = XposedBridge.invokeOriginalMethod(
                    param.method, param.thisObject, param.args
                ) as ArrayList<ReactPackage>

                reactPackages += pluginsReactPackage

                param.result = reactPackages
            }
        })

    }
}