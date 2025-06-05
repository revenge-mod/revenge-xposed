package io.github.revenge.xposed

import dalvik.system.DexClassLoader
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam
import io.github.revenge.Plugin
import io.github.revenge.Plugin.Companion.plugin
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.*
import java.io.File
import java.lang.reflect.Member
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.lang.reflect.Proxy
import java.util.ArrayList
import java.util.jar.JarFile
import kotlin.jvm.java

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

private val Class<*>.isPluginBuilder
    get() = Plugin.Builder::class.java.isAssignableFrom(this)

private val Member.accessible: Boolean
    get() = (this is Method && parameterTypes.isEmpty())
            ||
            (Modifier.isStatic(modifiers) && Modifier.isPublic(modifiers))

/**
 * Loads a plugin from a class according to the following rules:
 * 1. The class must have a public static field of type [Plugin.Builder] named "plugin".
 * 2. The class must have a public static method with no parameters that returns [Plugin.Builder].
 * 3. If both are present, the field takes precedence.
 * 4. If neither is present, the plugin is not loaded and an error is logged.
 * 
 * @param clazz The class to load the plugin from.
 * @param errors A mutable list to collect errors during plugin loading.
 * @return The loaded plugin or null if loading failed.
 */
private fun Plugin.Context.loadPlugin(
    clazz: Class<*>,
    errors: MutableList<String>,
): Plugin? {
    try {
        val pluginBuilderFromField =
            clazz.fields.find { field -> field.type.isPluginBuilder && field.accessible }?.get(null) as? Plugin.Builder

        if (pluginBuilderFromField != null) {
            return with(pluginBuilderFromField) { build() }
        }

        val pluginBuilderFromMethod = clazz.methods.find { method ->
            method.returnType.isPluginBuilder && method.parameterTypes.isEmpty() && method.accessible
        }?.invoke(null) as? Plugin.Builder

        if (pluginBuilderFromMethod != null) {
            return with(pluginBuilderFromMethod) { build() }
        }

        errors += "Plugin class ${clazz.canonicalName} does not have a public plugin field or public parameterless method"

        return null
    } catch (e: Exception) {
        errors += "Failed to load plugin from class ${clazz.canonicalName}: ${e.message}"
    }

    return null
}


/**
 * Loads plugins from the specified class names according to the rules from [loadPlugin] for each class.
 *
 * @param classNames The list of class names to load plugins from.
 * @param errors A mutable list to collect errors during plugin loading.
 * @param classLoader A function that takes a class name and returns the corresponding Class object.
 * @return A list of loaded plugins.
 */
private fun Plugin.Context.loadPlugins(
    classNames: List<String>,
    errors: MutableList<String>,
    classLoader: (String) -> Class<*>,
) = classNames.mapNotNull { className ->
    try {
        loadPlugin(classLoader(className), errors)
    } catch (_: ClassNotFoundException) {
        errors += "Plugin class not found: $className"
        null
    } catch (e: Exception) {
        errors += "Failed to load plugin from class $className: ${e.message}"
        null
    }
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
private fun Plugin.Context.loadPluginsFromFiles(pluginFiles: List<File>, errors: MutableList<String>): List<Plugin> {
    val pluginFilePaths = pluginFiles
        .filter { pluginFile ->
            val exists = pluginFile.exists() && pluginFile.isFile && pluginFile.canRead()
            if (!exists) errors += "Plugin file does not exist, is not a file or unreadable: ${pluginFile.absolutePath}"

            return@filter exists
        }.joinToString(File.pathSeparator) { it.absolutePath }

    val pluginClassNames = pluginFiles.mapNotNull { pluginFile ->
        val jarFile = JarFile(pluginFile)
        val manifest = jarFile.manifest

        if (manifest == null) {
            errors += "Manifest not found in plugin file: ${pluginFile.absolutePath}"
            return@mapNotNull null
        }

        manifest.mainAttributes.getValue("Revenge-Plugin-Class").also { attribute ->
            if (attribute == null) {
                errors += "'Revenge-Plugin-Class' not found in manifest: ${pluginFile.absolutePath}"
                return@mapNotNull null
            }
        }
    }

    val pluginClassLoader =
        DexClassLoader(
            pluginFilePaths,
            File(applicationInfo.dataDir, "revenge_plugin_dex_cache").absolutePath,
            null,
            classLoader // Has required classes from React Native or plugins.
        )

    return loadPlugins(pluginClassNames, errors) { pluginClassLoader.loadClass(it) }
}

/**
 * Loads internal plugins that are part of the Revenge Xposed module according to the rules
 * from [loadPlugin] for each class.
 * 
 * @param errors A mutable list to collect errors during plugin loading.
 * @return A list of loaded internal plugins.
 */
private fun Plugin.Context.loadPluginInternal(errors: MutableList<String>): List<Plugin> {
    val internalPluginClassNames = listOf(
        PluginsModule::class.java.canonicalName!!
    )

    return loadPlugins(internalPluginClassNames, errors) { Class.forName(it, true, classLoader) }
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
            with(Plugin.Context(packageParam.appInfo, packageParam.classLoader)) {
                loadPluginInternal(pluginErrors) + loadPluginsFromFiles(pluginFiles, pluginErrors)
            }
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

@Suppress("unused")
val corePlugin by plugin {
    init {
        println("Init corePlugin")
    }
}