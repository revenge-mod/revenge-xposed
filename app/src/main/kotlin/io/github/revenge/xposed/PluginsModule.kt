package io.github.revenge.xposed

import android.util.Log
import dalvik.system.DexClassLoader
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam
import io.github.revenge.Context
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.addAll
import kotlinx.serialization.json.buildJsonArray
import java.io.File
import java.lang.reflect.Modifier
import java.lang.reflect.Proxy
import java.util.jar.JarFile
import java.util.jar.Manifest

// Use a proxy because ReactPackage is not available at plugin build time.
private fun pluginsReactPackage(pluginModules: List<*>, classLoader: ClassLoader) = Proxy.newProxyInstance(
    classLoader,
    arrayOf(classLoader.loadClass("com.facebook.react.ReactPackage")),
    { _, method, _ ->
        when (method.name) {
            "createNativeModules" -> pluginModules
            "createViewManagers" -> emptyList<Any>()
            else -> throw UnsupportedOperationException("Method ${method.name} is not supported")
        }
    }
)

private const val PLUGIN_BUILDER_CLASS_NAME = "io.github.revenge.Builder"
private const val PLUGIN_CLASS_NAME = "io.github.revenge.Plugin"

private fun isClassByName(clazz: Class<*>, targetClassName: String): Boolean {
    return clazz.name == targetClassName
}

/**
 * Loads a plugin from a class reflectively.
 * The plugin class, its builder, and the Plugin API (Plugin.kt, Context.kt)
 * are expected to be loaded by the pluginProviderClass's classloader (e.g., DexClassLoader).
 *
 * @param pluginProviderClass The class (loaded by DexClassLoader) that provides the plugin builder.
 * @param errors A mutable list to collect errors.
 * @param pluginContext The application context (ApplicationInfo and app's ClassLoader) from PluginsModule.
 * @return The loaded plugin instance (as Object) or null if loading failed.
 */
private fun loadPluginReflectively(
    pluginProviderClass: Class<*>,
    errors: MutableList<String>,
    pluginContext: Context
): Any? {
    try {
        var pluginBuilderInstance: Any? = null

        // Try to find a public static field of type Plugin.Builder
        pluginProviderClass.fields.find { field ->
            isClassByName(field.type, PLUGIN_BUILDER_CLASS_NAME) &&
                    Modifier.isStatic(field.modifiers) && Modifier.isPublic(field.modifiers)

        }?.let { field ->
            field.isAccessible = true
            pluginBuilderInstance = field.get(null)
        }

        // If not found, try to find a public static parameterless method returning Plugin.Builder
        if (pluginBuilderInstance == null) {
            pluginProviderClass.methods.find { method ->
                isClassByName(method.returnType, PLUGIN_BUILDER_CLASS_NAME) &&
                        method.parameterTypes.isEmpty() &&
                        Modifier.isStatic(method.modifiers) && Modifier.isPublic(method.modifiers)
            }?.let { method ->
                method.isAccessible = true
                pluginBuilderInstance = method.invoke(null)
            }
        }

        if (pluginBuilderInstance != null) {
            val pluginBuilderClass = pluginBuilderInstance.javaClass
            val buildMethod = pluginBuilderClass.getMethod("build", pluginContext::class.java)
            buildMethod.isAccessible = true
            val pluginInstance = buildMethod.invoke(pluginBuilderInstance, pluginContext)

            if (pluginInstance == null || !isClassByName(
                    pluginInstance.javaClass.superclass!!,
                    PLUGIN_CLASS_NAME
                ) && !isClassByName(pluginInstance.javaClass, PLUGIN_CLASS_NAME)
            ) {
                var currentClass: Class<*>? = pluginInstance?.javaClass
                var isPluginType = false
                while (currentClass != null) {
                    if (isClassByName(currentClass, PLUGIN_CLASS_NAME)) {
                        isPluginType = true
                        break
                    }
                    currentClass = currentClass.superclass
                }
                if (!isPluginType) {
                    errors += "Builder for ${pluginProviderClass.canonicalName} did not return " +
                            "a valid instance of $PLUGIN_CLASS_NAME. Got ${pluginInstance?.javaClass?.name}"
                    return null
                }
            }
            return pluginInstance
        }

        errors += "Plugin class ${pluginProviderClass.canonicalName} does not have a public static plugin field " +
                "or method returning $PLUGIN_BUILDER_CLASS_NAME"
        return null
    } catch (e: Exception) {
        errors += "Failed to load plugin from class ${pluginProviderClass.canonicalName}: ${e.message}\n${
            Log.getStackTraceString(
                e
            )
        }"
    }
    return null
}


private fun Context.loadPlugins(
    classNames: List<String>,
    errors: MutableList<String>,
    classLoaderProvider: (String) -> Class<*>
): List<Any> = classNames.mapNotNull { className ->
    try {
        val pluginProviderClass = classLoaderProvider(className)
        loadPluginReflectively(pluginProviderClass, errors, this)
    } catch (_: ClassNotFoundException) {
        errors += "Plugin provider class not found: $className"
        null
    } catch (e: Exception) {
        errors += "Failed to load plugin from provider class $className: ${e.message}\n${Log.getStackTraceString(e)}"
        null
    }
}

private fun Context.loadPluginsFromFiles(errors: MutableList<String>): List<Any> {
    val pluginFilesJson = File(applicationInfo.dataDir, "files/revenge/plugins.json")
    if (!pluginFilesJson.exists()) {
        Log.w("RevengeXposed", "plugins.json not found at ${pluginFilesJson.absolutePath}")
        return emptyList()
    }

    val pluginFilePathsStrings = try {
        Json.decodeFromString<List<String>>(pluginFilesJson.readText())
    } catch (e: Throwable) {
        errors += "Failed to parse plugins.json: ${e.message}"
        return emptyList()
    }

    val validPluginFiles = pluginFilePathsStrings.map { File(it) }.filter { pluginFile ->
        val exists = pluginFile.isFile && pluginFile.canRead()
        if (!exists) errors += "Plugin file does not exist, is not a file or unreadable: ${pluginFile.absolutePath}"
        exists
    }

    if (validPluginFiles.isEmpty()) {
        Log.w("RevengeXposed", "No valid plugin files found after filtering.")
        return emptyList()
    }

    val dexPath = validPluginFiles.joinToString(File.pathSeparator) { it.absolutePath }
    val optimizedDirectory = File(applicationInfo.dataDir, "revenge_plugin_dex_cache")
    optimizedDirectory.mkdirs()

    val pluginClassNames = validPluginFiles.mapNotNull { pluginFile ->
        try {
            JarFile(pluginFile).use { jarFile ->
                jarFile.getInputStream(jarFile.getEntry("assets/Revenge-Plugin-Class"))?.bufferedReader()
                    ?.use { reader ->
                        reader.readLine()?.trim()
                    }
            }
        } catch (e: Exception) {
            errors += "Failed to read manifest from ${pluginFile.absolutePath}: ${e.stackTraceToString()}"
            null
        }
    }.filter { it.isNotBlank() }

    if (pluginClassNames.isEmpty()) {
        Log.w("RevengeXposed", "No plugin provider classes found in manifests.")
    }
    
    val thisClassLoader = this::class.java.classLoader!!

    val pluginClassLoader = DexClassLoader(
        dexPath,
        optimizedDirectory.absolutePath,
        null,
        object: ClassLoader(classLoader) {
            override fun loadClass(name: String): Class<*> {
                return try {
                    thisClassLoader.loadClass(name)
                } catch (e: ClassNotFoundException) {
                    super.loadClass(name)
                }
            }
        }
    )
    return loadPlugins(pluginClassNames, errors) { pluginClassName -> pluginClassLoader.loadClass(pluginClassName) }
}

class PluginsModule : Module() {
    private val pluginErrors: MutableList<String> = mutableListOf()

    @OptIn(ExperimentalSerializationApi::class)
    override fun buildJson(builder: JsonObjectBuilder) {
        if (pluginErrors.isNotEmpty())
            builder.put("pluginErrors", buildJsonArray { addAll(pluginErrors) })
    }

    override fun init(packageParam: LoadPackageParam) {
        val pluginContext = Context(packageParam.appInfo, packageParam.classLoader)

        val plugins = try {
            // Load plugins using the initial context
            pluginContext.loadPluginsFromFiles(pluginErrors)
        } catch (e: Throwable) {
            pluginErrors += "Failed to load plugins:\n${Log.getStackTraceString(e)}"
            emptyList() // Return empty list on catastrophic failure
        }

        if (plugins.isEmpty() && pluginErrors.isEmpty()) {
            Log.i("RevengeXposed", "No plugins loaded and no errors reported.")
        } else if (plugins.isEmpty() && pluginErrors.isNotEmpty()) {
            Log.w("RevengeXposed", "No plugins loaded. Errors occurred: ${pluginErrors.joinToString()}")
        }


        val pluginsReactPackage = pluginsReactPackage(plugins, packageParam.classLoader)

        try {
            val dcdReactNativeHostClass = packageParam.classLoader
                .loadClass("com.discord.bridge.DCDReactNativeHost")
            val getPackagesMethod = dcdReactNativeHostClass
                .getDeclaredMethod("getPackages") // This should be public in React Native host

            XposedBridge.hookMethod(getPackagesMethod, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) { // Use afterHookedMethod to modify result
                    @Suppress("UNCHECKED_CAST")
                    val reactPackages =
                        param.result as? ArrayList<Any> ?: ArrayList() // Get existing list or create new

                    // Check if our package is already added to prevent duplicates if method is called multiple times
                    val alreadyAdded = reactPackages.any { it === pluginsReactPackage } // Instance equality check
                    if (!alreadyAdded) {
                        reactPackages.add(pluginsReactPackage)
                    }
                    param.result = reactPackages
                }
            })
            Log.i("RevengeXposed", "Successfully hooked getPackages and added plugin ReactPackage.")
        } catch (e: ClassNotFoundException) {
            pluginErrors += "DCDReactNativeHost class not found. Cannot inject plugins. ${e.message}"
            Log.e("RevengeXposed", "DCDReactNativeHost class not found.", e)
        } catch (e: NoSuchMethodException) {
            pluginErrors += "getPackages method not found in DCDReactNativeHost. Cannot inject plugins. ${e.message}"
            Log.e("RevengeXposed", "getPackages method not found.", e)
        } catch (e: Throwable) {
            pluginErrors += "Failed to hook getPackages: ${e.message}\n${Log.getStackTraceString(e)}"
            Log.e("RevengeXposed", "Failed to hook getPackages.", e)
        }
    }
}
