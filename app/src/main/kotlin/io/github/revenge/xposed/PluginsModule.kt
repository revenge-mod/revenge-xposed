package io.github.revenge.xposed

import dalvik.system.DexClassLoader
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam
import io.github.revenge.plugin.*
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.*
import java.io.File
import java.lang.reflect.Member
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.lang.reflect.Proxy
import java.util.ArrayList
import java.util.jar.JarFile

internal fun LoadPackageParam.toPluginContext() = PluginContext(appInfo, classLoader)

// Use a proxy because ReactPackage is not available at plugin build time.
internal fun pluginsReactPackage(pluginModules: List<*>, classLoader: ClassLoader) = Proxy.newProxyInstance(
    classLoader,
    arrayOf(classLoader.loadClass("com.facebook.react.ReactPackage")),
    { _, method, args ->
        when (method.name) {
            "createNativeModules" -> pluginModules
            "createViewManagers" -> emptyList<Object>()
            else -> throw UnsupportedOperationException("Method ${method.name} is not supported")
        }
    }
)

internal fun LoadPackageParam.initPlugins(pluginFiles: List<File>) =
    toPluginContext().initPlugins(pluginFiles)

internal fun PluginContext.initPlugins(
    pluginFiles: List<File>,
): Pair<List<*>, List<String>> {
    val errors = mutableListOf<String>()

    val pluginPaths = pluginFiles
        .filter { pluginFile ->
            pluginFile.exists().also { exists ->
                if (!exists) errors += "Plugin file does not exist: ${pluginFile.absolutePath}"
            }
        }
        .joinToString(File.pathSeparator) { it.absolutePath }

    val pluginClassNames = pluginFiles.mapNotNull { pluginFile ->
        val jarFile = JarFile(pluginFile)
        val manifest = jarFile.manifest

        if (manifest == null) {
            errors += "Manifest not found in plugin file: ${pluginFile.absolutePath}"
            return@mapNotNull null
        }

        manifest.mainAttributes.getValue("Revenge-Plugin-Class").also { attribute ->
            if (attribute == null) {
                errors += "Revenge-Plugin-Class not found in manifest: ${pluginFile.absolutePath}"
                return@mapNotNull null
            }
        }
    }

    val pluginClassLoader =
        DexClassLoader(
            pluginPaths,
            File(appInfo.dataDir, "revenge_plugin_dex_cache").absolutePath,
            null,
            classLoader // Provide plugin APIs.
        )

    return pluginClassNames.mapNotNull {
        val pluginClass = try {
            pluginClassLoader.loadClass(it)
        } catch (_: ClassNotFoundException) {
            errors += "Plugin class not found: $it"
            return@mapNotNull null
        }

        try {
            val pluginBuilderFromField =
                pluginClass.fields.find { field -> field.type.isPluginBuilder && field.canAccess() }
                    ?.get(null) as? PluginBuilder

            if (pluginBuilderFromField != null) {
                return@mapNotNull with(pluginBuilderFromField) { build() }
            }

            val pluginBuilderFromMethod = pluginClass.methods.find { method ->
                method.returnType.isPluginBuilder && method.parameterTypes.isEmpty() && method.canAccess()
            }?.invoke(null) as? PluginBuilder

            if (pluginBuilderFromMethod != null) {
                return@mapNotNull with(pluginBuilderFromMethod) { build() }
            }
        } catch (e: Exception) {
            errors += "Failed to initialize plugin ${it::class.java.name}: ${e.message}"
        }

        errors += "Plugin class $it does not have a valid plugin field or method"

        return@mapNotNull null
    } to errors
}

private val Class<*>.isPluginBuilder get() = PluginBuilder::class.java.isAssignableFrom(this)

private fun Member.canAccess(): Boolean {
    if (this is Method && parameterTypes.size != 0) return false

    return Modifier.isStatic(modifiers) && Modifier.isPublic(modifiers)
}

class PluginsModule : Module() {
    private val pluginErrors: MutableList<String> = mutableListOf()

    @OptIn(ExperimentalSerializationApi::class)
    override fun buildJson(builder: JsonObjectBuilder) {
        builder.put("pluginErrors", buildJsonArray { addAll(pluginErrors) })
    }

    override fun init(packageParam: LoadPackageParam) = with(packageParam) {
        val pluginFilesJson = File(appInfo.dataDir, "files/revenge/plugins.json")
        if (!pluginFilesJson.exists()) return@with

        val pluginFiles = try {
            Json.decodeFromString<List<String>>(pluginFilesJson.readText()).map { File(it) }
        } catch (_: Throwable) {
            pluginErrors += "Failed to parse plugins.json"
            return@with
        }

        // Here, plugins are initialized. Errors during initialization are collected below.
        val (plugins, initPluginsErrors) = try {
            initPlugins(pluginFiles)
        } catch (e: Throwable) {
            pluginErrors += "Failed to load plugins: ${e.message}"
            return@with
        }

        // If there are errors during initialization, we add them to the list.
        pluginErrors += initPluginsErrors

        val pluginsReactPackage = pluginsReactPackage(plugins, classLoader)

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
                ) as ArrayList<Any>

                reactPackages.add(pluginsReactPackage)

                param.result = reactPackages
            }
        })

    }
}