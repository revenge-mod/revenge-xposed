package io.github.revenge.xposed

import com.facebook.react.ReactPackage
import com.facebook.react.bridge.NativeModule
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.uimanager.ViewManager
import dalvik.system.DexClassLoader
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.callbacks.XC_LoadPackage
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam
import io.github.revenge.plugin.*
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.*
import java.io.File
import java.lang.reflect.Member
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.ArrayList
import java.util.jar.JarFile


internal fun LoadPackageParam.toPluginContext() = PluginContext(appInfo, classLoader)

internal fun pluginsReactPackage(pluginModules: List<NativeModule>) = object : ReactPackage {
    override fun createNativeModules(context: ReactApplicationContext): List<NativeModule> = pluginModules
    override fun createViewManagers(context: ReactApplicationContext) = emptyList<ViewManager<*, *>>()
}

internal fun LoadPackageParam.initPlugins(pluginFiles: List<File>, errors: MutableList<String> = mutableListOf()) =
    toPluginContext().initPlugins(pluginFiles, errors)

internal fun PluginContext.initPlugins(
    pluginFiles: List<File>,
    errors: MutableList<String> = mutableListOf()
): List<Plugin> {
    val pluginPaths = pluginFiles
        .filter { pluginFile ->
            pluginFile.exists().also { exists ->
                if (!exists) errors.add("Plugin file does not exist: ${pluginFile.absolutePath}")
            }
        }
        .joinToString(File.pathSeparator) { it.absolutePath }

    val pluginClassNames = pluginFiles.mapNotNull { pluginFile ->
        val jarFile = JarFile(pluginFile)
        val manifest = jarFile.manifest

        if (manifest == null) {
            errors.add("Manifest not found in plugin file: ${pluginFile.absolutePath}")
            return@mapNotNull null
        }

        manifest.mainAttributes.getValue("Revenge-Plugin-Class").also { attribute ->
            if (attribute == null) {
                errors.add("Revenge-Plugin-Class not found in manifest: ${pluginFile.absolutePath}")
                return@mapNotNull null
            }
        }
    }

    val pluginClassLoader =
        DexClassLoader(
            pluginPaths,
            File(appInfo!!.dataDir, "revenge_plugin_dex_cache").absolutePath,
            null,
            classLoader // Provide plugin APIs.
        )

    return pluginClassNames.mapNotNull {
        val pluginClass = try {
            pluginClassLoader.loadClass(it)
        } catch (_: ClassNotFoundException) {
            errors.add("Plugin class not found: $it")
            return@mapNotNull null
        }

        try {
            val pluginBuilderField =
                pluginClass.fields.find { field -> field.type.isPluginBuilder && field.canAccess() }
                    ?.get(null) as? PluginBuilder


            if (pluginBuilderField != null) {
                return@mapNotNull pluginBuilderField.build(this)
            }

            val pluginBuilderMethod = pluginClass.methods.find { method ->
                method.returnType.isPluginBuilder && method.parameterTypes.isEmpty() && method.canAccess()
            }?.invoke(null) as? PluginBuilder

            if (pluginBuilderMethod != null) {
                return@mapNotNull pluginBuilderMethod.build(this)
            }
        } catch (e: Exception) {
            errors.add("Failed to initialize plugin ${it::class.java.name}: ${e.message}")
        }

        errors.add("Plugin class $it does not have a valid plugin field or method")
        return@mapNotNull null
    }
}

private val Class<*>.isPluginBuilder get() = PluginBuilder::class.java.isAssignableFrom(this)

private fun Member.canAccess(): Boolean {
    if (this is Method && parameterTypes.size != 0) return false

    return Modifier.isStatic(modifiers) && Modifier.isPublic(modifiers)
}

class PluginsModule : Module() {
    private val errors: MutableList<String> = mutableListOf()

    @OptIn(ExperimentalSerializationApi::class)
    override fun buildJson(builder: JsonObjectBuilder) {
        builder.put("pluginErrors", buildJsonArray { addAll(errors) })
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

        // Here, plugins are initialized.
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