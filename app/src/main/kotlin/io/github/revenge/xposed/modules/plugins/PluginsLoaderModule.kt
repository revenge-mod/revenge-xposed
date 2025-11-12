package io.github.revenge.xposed.modules.plugins

import dalvik.system.DexClassLoader
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam
import io.github.revenge.plugins.*
import io.github.revenge.xposed.Module
import io.github.revenge.xposed.Utils.Log
import io.github.revenge.xposed.modules.plugins.PluginsLoaderModule.loadPlugin
import io.github.revenge.xposed.plugins.myPlugin
import java.io.File
import java.util.jar.JarFile

class PluginProvider(val name: String, val plugin: Plugin)

object PluginsLoaderModule : Module() {
    private val errors: MutableList<String> = mutableListOf()

    private val plugins = mutableSetOf<PluginProvider>()

    override fun onLoad(packageParam: LoadPackageParam) = with(packageParam) {
        try {
            loadInternalPlugins()

            // Load external plugins...
            // loadPluginsFromFiles(pluginFiles)

            Log.i("Loaded ${plugins.size} plugins with ${errors.size} errors")
            for (err in errors) {
                Log.e(err)
            }
        } catch (e: Throwable) {
            Log.e("Error during plugin loading: ${e.stackTraceToString()}")
            return@with
        }
    }

    /**
     * Loads internal plugins that are part of the Xposed module according to the rules
     * from [loadPlugin] for each class.
     *
     * @return A list of loaded internal plugins.
     */
    private fun LoadPackageParam.loadInternalPlugins(): List<PluginProvider> {
        val internalPluginClassNames = listOf(
            myPlugin::class.java.name,
        )

        return loadPluginsFromClassNames(
            internalPluginClassNames, pluginClassLoader()
        )
    }

    /**
     * Returns a [ClassLoader] that uses the app's then the module's.
     */
    private fun LoadPackageParam.pluginClassLoader() =
        object : ClassLoader() {
            override fun loadClass(name: String?) = runCatching { classLoader.loadClass(name) }.getOrNull()
                ?: runCatching { this::class.java.classLoader!!.loadClass(name) }.getOrNull() ?: super.loadClass(name)
        }

    /**
     * Loads plugins from the specified files according to the following rules:
     * 1. Each file must exist, be a file, and be readable.
     * 2. Each file must have a manifest with the "Revenge-Plugin-Class" attribute, which points to a [Plugin] class.
     * 3. Each file must have a manifest with the "Revenge-Plugin-Manifest" attribute, which contains a JSON-encoded [PluginManifest].
     *
     * @param pluginFiles The list of plugin files to load.
     * @return A list of loaded plugins.
     */
    fun LoadPackageParam.loadPluginsFromFiles(
        pluginFiles: List<File>
    ): List<PluginProvider> {
        val pluginFilePaths = pluginFiles.filter { pluginFile ->
            val exists = pluginFile.exists() && pluginFile.isFile && pluginFile.canRead()
            if (!exists) {
                errors += "Plugin file does not exist, is not a file or unreadable: ${pluginFile.absolutePath}"
            }
            return@filter exists
        }.joinToString(File.pathSeparator) { it.absolutePath }

        if (pluginFilePaths.isEmpty()) {
            Log.w("No valid plugin files to load")
            return emptyList()
        }

        // Extend the internal class loader to also include plugin files
        val pluginClassLoader = DexClassLoader(
            pluginFilePaths,
            File(appInfo.dataDir, "revenge_plugin_dex_cache").absolutePath,
            null,
            pluginClassLoader()
        )

        return pluginFiles.mapNotNull { pluginFile ->
            try {
                JarFile(pluginFile).use { jarFile ->
                    val attributes = java.util.jar.Manifest().apply {
                        read(jarFile.getInputStream(jarFile.getJarEntry("assets/Revenge")))
                    }.mainAttributes

                    val className = attributes.getValue("Revenge-Plugin-Class")
                    if (className.isEmpty()) {
                        errors += "Plugin class name is empty"
                        return@mapNotNull null
                    }

                    loadPlugin(pluginClassLoader.loadClass(className))
                }
            } catch (e: Exception) {
                errors += "Failed to read plugin file ${pluginFile.absolutePath}: ${e.message}"
                null
            }
        }
    }

    private fun LoadPackageParam.loadPlugin(
        clazz: Class<*>
    ): PluginProvider? {
        try {
            val getPluginMethod = clazz.declaredMethods.first { it.returnType == Plugin::class.java }

            PluginProvider(
                clazz.simpleName,
                getPluginMethod.invoke(clazz.getField("INSTANCE").get(null), null) as Plugin
            ).also { it.plugin.init?.invoke(appInfo, classLoader) }
        } catch (e: Exception) {
            val err = "Failed to load plugin:\n${e.stackTraceToString()}"
            errors += err
            Log.e(err)
        }

        return null
    }

    private fun LoadPackageParam.loadPluginsFromClassNames(
        classNames: List<String>,
        classLoader: ClassLoader,
    ) = classNames.mapNotNull { className ->
        try {
            loadPlugin(classLoader.loadClass(className))
        } catch (e: Throwable) {
            val err = when (e) {
                is ClassNotFoundException -> "Plugin class not found: $className"
                else -> "Failed to instantiate plugin provider $className: ${e.message}"
            }
            errors += err
            Log.e(err)
            null
        }
    }
}
