package io.github.revenge.xposed

import dalvik.system.DexClassLoader
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.*
import java.io.File
import java.lang.reflect.Proxy
import java.util.ArrayList
import java.util.jar.JarFile

// Use a proxy because ReactPackage is not available at plugin build time.
private fun pluginsReactPackage(pluginModules: List<*>, classLoader: ClassLoader) = Proxy.newProxyInstance(
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

/**
 * Loads a plugin from a class according to the following rules:
 * 1. The class must have a public static method [Plugin.Builder] named "plugin".
 * 2. The class must have a public static method with no parameters that returns [Plugin.Builder].
 * 3. If both are present, the field takes precedence.
 * 4. If neither is present, the plugin is not loaded and an error is logged.
 *
 * @param clazz The class to load the plugin from.
 * @param errors A mutable list to collect errors during plugin loading.
 * @return The loaded plugin or null if loading failed.
 */
private fun LoadPackageParam.loadPlugin(
    clazz: Class<*>,
    errors: MutableList<String>,
) = try {
    XposedHelpers.newInstance(clazz, appInfo, classLoader)
} catch (e: Exception) {
    errors += "Failed to load plugin from class ${clazz.canonicalName}:\n${e.stackTraceToString()}"

    null
}


/**
 * Loads plugins from the specified class names according to the rules from [loadPlugin] for each class.
 *
 * @param classNames The list of class names to load plugins from.
 * @param errors A mutable list to collect errors during plugin loading.
 * @param classLoader A function that takes a class name and returns the corresponding Class object.
 * @return A list of loaded plugins.
 */
private fun LoadPackageParam.loadPlugins(
    classNames: List<String>,
    errors: MutableList<String>,
    classLoader: (String) -> Class<*>,
) = classNames.mapNotNull { className ->
    loadPlugin(
        try {
            classLoader(className)
        } catch (_: ClassNotFoundException) {
            errors += "Plugin class not found: $className"
            return@mapNotNull null
        },
        errors
    )
}

/**
 * Loads plugins from the specified files according to the following rules:
 * 1. Each file must exist, be a file, and be readable.
 * 2. Each file must have a manifest with the "Revenge-Plugin-Class" attribute.
 * 3. All rules from [loadPlugin] apply to the class specified in the manifest.
 *
 * @param pluginFiles The list of plugin files to load.
 * @param errors A mutable list to collect errors during plugin loading.
 * @return A list of loaded plugins.
 */
private fun LoadPackageParam.loadPluginsFromFiles(
    pluginFiles: List<File>,
    errors: MutableList<String>
): List<*> {
    val pluginFilePaths = pluginFiles
        .filter { pluginFile ->
            val exists = pluginFile.exists() && pluginFile.isFile && pluginFile.canRead()
            if (!exists) errors += "Plugin file does not exist, is not a file or unreadable: ${pluginFile.absolutePath}"

            return@filter exists
        }.joinToString(File.pathSeparator) { it.absolutePath }

    val pluginClassNames = pluginFiles.mapNotNull { pluginFile ->
        JarFile(pluginFile).use { jarFile ->
            val asset = jarFile.getJarEntry("assets/Revenge-Plugin-Class")

            if (asset == null) {
                errors += "'Revenge-Plugin-Class' not found in plugin file under 'assets/': ${pluginFile.absolutePath}"
                return@mapNotNull null
            }

            jarFile.getInputStream(asset).bufferedReader().readLine().trim()
        }
    }

    val pluginClassLoader =
        DexClassLoader(
            pluginFilePaths,
            File(appInfo.dataDir, "revenge_plugin_dex_cache").absolutePath,
            null,
            object : ClassLoader() {
                override fun loadClass(name: String?) =
                    runCatching { classLoader.loadClass(name) }.getOrNull()
                        ?: runCatching { this::class.java.classLoader!!.loadClass(name) }.getOrNull()
                        ?: super.loadClass(name)
            },
        )

    return loadPlugins(pluginClassNames, errors) { pluginClassLoader.loadClass(it) }
}

/**
 * Loads internal plugins that are part of the Xposed module according to the rules
 * from [loadPlugin] for each class.
 *
 * @param errors A mutable list to collect errors during plugin loading.
 * @return A list of loaded internal plugins.
 */
private fun LoadPackageParam.loadPluginInternal(errors: MutableList<String>): List<*> {
    val internalPluginClassNames = listOf(
        "io.github.revenge.CorePluginKt"
    )

    val moduleAndAppClassLoader = object : ClassLoader() {
        override fun loadClass(name: String?) = runCatching { classLoader.loadClass(name) }.getOrNull()
            ?: runCatching { this::class.java.classLoader!!.loadClass(name) }.getOrNull()
            ?: super.loadClass(name)
    }

    return loadPlugins(internalPluginClassNames, errors) { moduleAndAppClassLoader.loadClass(it) }
}

class PluginsModule : Module() {
    private val pluginErrors: MutableList<String> = mutableListOf()

    @OptIn(ExperimentalSerializationApi::class)
    override fun buildJson(builder: JsonObjectBuilder) {
        builder.put("pluginErrors", buildJsonArray { addAll(pluginErrors) })
    }

    override fun init(packageParam: LoadPackageParam) {
        val pluginFilesJson = File(packageParam.appInfo.dataDir, "files/revenge/plugins.json")
        if (!pluginFilesJson.exists()) return

        val pluginFiles = try {
            Json.decodeFromString<List<String>>(pluginFilesJson.readText()).map { File(it) }
        } catch (_: Throwable) {
            pluginErrors += "Failed to parse plugins.json"
            return
        }

        // Here, plugins are loaded. Errors during loading are collected below.
        val plugins = try {
            packageParam.loadPluginInternal(pluginErrors) + packageParam.loadPluginsFromFiles(pluginFiles, pluginErrors)
        } catch (e: Throwable) {
            pluginErrors += "Failed to load plugins: ${e.message}"
            return
        }

        val pluginsReactPackage = pluginsReactPackage(plugins, packageParam.classLoader)

        // Hooking the method that returns the list of React packages
        // and adding our own package to it.
        // Our own package is shipping the plugins as NativeModules.
        val getPackagesMethod = packageParam.classLoader
            .loadClass("com.discord.bridge.DCDReactNativeHost")
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
