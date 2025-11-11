package io.github.revenge.xposed.modules.plugins

import InternalApi
import android.content.Context
import dalvik.system.DexClassLoader
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam
import io.github.revenge.plugins.*
import io.github.revenge.plugins.impl.PluginsHost
import io.github.revenge.xposed.Module
import io.github.revenge.xposed.Utils
import io.github.revenge.xposed.Utils.Log
import io.github.revenge.xposed.modules.bridge.BridgeModule
import io.github.revenge.xposed.modules.bridge.asDelegate
import io.github.revenge.xposed.modules.plugins.PluginsLoaderModule.loadPlugin
import io.github.revenge.xposed.modules.plugins.PluginsStatesModule.readStatesOrNull
import io.github.revenge.xposed.plugins.ExampleCorePluginProvider
import io.github.revenge.xposed.plugins.InternalPluginProvider
import java.io.File
import java.lang.ref.WeakReference
import java.util.jar.JarFile

object PluginsLoaderModule : Module() {
    private val errors: MutableList<String> = mutableListOf()

    private val plugins = mutableMapOf<PluginManifest, Plugin>()
    private val manifests = mutableMapOf<String, PluginManifest>()

    private lateinit var contextRef: WeakReference<Context>

    override fun onLoad(packageParam: LoadPackageParam) = with(packageParam) {
        initHost()
        registerMethods()

        try {
            PluginsStatesModule.batchSave {
                loadInternalPlugins()

                // Load external plugins...
                // loadPluginsFromFiles(pluginFiles)
            }

            Log.i("Loaded ${plugins.size} plugins with ${errors.size} errors")
            for (err in errors) {
                Log.e(err)
            }
        } catch (e: Throwable) {
            Log.e("Error during plugin loading: ${e.stackTraceToString()}")
            return@with
        }
    }

    override fun onContext(context: Context) {
        contextRef = WeakReference(context)
        for (p in plugins.values) runCatching { p.start(context) }
    }

    @OptIn(InternalApi::class)
    fun initHost() {
        PluginsHost.onFlagsUpdate {
            PluginsStatesModule.updatePluginFlags(it)
        }
    }

    private fun LoadPackageParam.registerMethods() {
        BridgeModule.registerMethod("revenge.plugins.loader.getManifests") {
            manifests.values.map { manifest ->
                mapOf(
                    "id" to manifest.id,
                    "name" to manifest.name,
                    "description" to manifest.description,
                    "author" to manifest.author,
                    "icon" to manifest.icon,
                    "dependencies" to manifest.dependencies.map { dep ->
                        mapOf(
                            "id" to dep.id, "url" to dep.url
                        )
                    })
            }
        }

        BridgeModule.registerMethod("revenge.plugins.loader.registerJSOnlyInternalPlugin") {
            val args = it.asDelegate()
            val data by args.hashMap()

            val id = data.requireString("id")
            val name = data.requireString("name")
            val description = data.requireString("description")
            val author = data.requireString("author")
            val icon = data.optionalString("icon")
            val dependencies = data.parseDependencies()

            val provider = object : PluginProvider {
                override val manifest: PluginManifest = PluginManifest(
                    id = id,
                    name = name,
                    description = description,
                    author = author,
                    icon = icon,
                    dependencies = dependencies
                )

                override fun createPlugin() = object : Plugin(manifest) {}
            }

            loadPlugin(provider, getInternalClassLoader())
        }
    }

    private fun Map<String, Any?>.requireString(key: String): String =
        this[key] as? String ?: throw IllegalArgumentException("Plugin $key is required")

    private fun Map<String, Any?>.optionalString(key: String): String? =
        this[key] as? String

    private fun Map<String, Any?>.parseDependencies(): ArrayList<PluginDependency> {
        val raw = this["dependencies"] as? ArrayList<*> ?: return arrayListOf()
        return ArrayList(raw.mapIndexed { index, item ->
            val depMap =
                item as? Map<*, *> ?: throw IllegalArgumentException("Dependency at index $index must be a Map")
            val depId =
                (depMap["id"] as? String) ?: throw IllegalArgumentException("Dependency id is required at index $index")
            val url = depMap["url"] as? String
            PluginDependency(depId, url)
        })
    }

    /**
     * Loads internal plugins that are part of the Xposed module according to the rules
     * from [loadPlugin] for each class.
     *
     * @return A list of loaded internal plugins.
     */
    private fun LoadPackageParam.loadInternalPlugins(): List<Plugin> {
        val internalPluginClassNames = listOf(
            ExampleCorePluginProvider::class.java.name,
        )

        return loadPluginsFromClassNames(
            internalPluginClassNames, getInternalClassLoader()
        )
    }

    /**
     * Returns a [ClassLoader] that uses the app's then the module's.
     */
    private fun LoadPackageParam.getInternalClassLoader() =
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
    ): List<Plugin> {
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
            getInternalClassLoader()
        )

        return pluginFiles.mapNotNull { pluginFile ->
            try {
                JarFile(pluginFile).use { jarFile ->
                    val className = jarFile.readEntryText("assets/Revenge-Plugin-Class")!!
                    val manifest = jarFile.readEntryText("assets/Revenge-Plugin-Manifest")?.let {
                        Utils.JSON.decodeFromString<PluginManifest>(it)
                    }!!

                    if (className.isEmpty()) {
                        errors += "Plugin class name is empty"
                        return@mapNotNull null
                    }

                    val clazz = pluginClassLoader.loadClass(className)
                    if (!Plugin::class.java.isAssignableFrom(clazz)) {
                        errors += "Plugin class $className does not implement Plugin"
                        return@mapNotNull null
                    }

                    val provider = object : PluginProvider {
                        override val manifest: PluginManifest = manifest
                        override fun createPlugin(): Plugin =
                            clazz.getDeclaredConstructor().newInstance() as Plugin
                    }

                    loadPlugin(provider, pluginClassLoader)
                }
            } catch (e: Exception) {
                errors += "Failed to read plugin file ${pluginFile.absolutePath}: ${e.message}"
                null
            }
        }
    }

    private fun JarFile.readEntryText(entryName: String): String? =
        getJarEntry(entryName)?.let { entry ->
            getInputStream(entry).bufferedReader().use { it.readText() }.trim()
        }

    /**
     * Loads and runs a [Plugin] from a [PluginProvider].
     * If the provider could not create a plugin instance, or an exception occurs during [Plugin.init], an error is recorded.
     *
     * @return The loaded [Plugin] instance, or null if loading failed.
     */
    private fun LoadPackageParam.loadPlugin(
        provider: PluginProvider, classLoader: ClassLoader
    ): Plugin? {
        try {
            val plugin = provider.createPlugin()

            // Load saved flags
            PluginsStatesModule.loadPluginFlags(plugin)

            // Always enable essential plugins/enabled-by-default plugins
            if (provider is InternalPluginProvider && (provider.isEssential() || provider.isEnabledByDefault())) {
                plugin.flags += PluginFlags.ENABLED
            }

            plugin.init(appInfo, classLoader)
            plugin.getMethods(appInfo, classLoader).forEach { (methodName, callback) ->
                BridgeModule.registerMethod(methodName, callback)
            }

            plugins[provider.manifest] = plugin
            manifests[provider.manifest.id] = provider.manifest

            Log.i("Loaded plugin: ${provider.manifest.id}")
            return plugin
        } catch (e: Exception) {
            val err = "Failed to load plugin ${provider.manifest.id}:\n${e.stackTraceToString()}"
            errors += err
            Log.e(err)
        }

        return null
    }

    /**
     * Loads plugins from the specified class names, if they are enabled.
     * The given class names must implement the [PluginProvider] interface.
     *
     * @return A list of loaded plugins.
     */
    private fun LoadPackageParam.loadPluginsFromClassNames(
        classNames: List<String>,
        classLoader: ClassLoader,
    ): List<Plugin> {
        val states = readStatesOrNull()
        if (states == null) {
            errors += "Plugin states could not be read, cannot load plugins"
            return emptyList()
        }

        return classNames.mapNotNull { className ->
            try {
                @Suppress("UNCHECKED_CAST") val providerClass =
                    classLoader.loadClass(className) as Class<PluginProvider>
                val provider = providerClass.getDeclaredConstructor().newInstance()

                if (
                    !states.isPluginEnabled(provider.manifest.id) &&
                    // Not an essential plugin, or if no state set, a plugin that's enabled by default
                    !(provider is InternalPluginProvider && (
                            provider.isEssential() ||
                                    (!states.hasPlugin(provider.manifest.id) && provider.isEnabledByDefault())
                            ))
                ) {
                    Log.i("Skipping disabled plugin: ${provider.manifest.id}")
                    return@mapNotNull null
                }

                loadPlugin(provider, classLoader)
            } catch (e: Throwable) {
                val err = when (e) {
                    is ClassNotFoundException -> "Plugin class not found: $className"
                    is ClassCastException -> "Plugin class $className does not implement PluginProvider"
                    else -> "Failed to instantiate plugin provider $className: ${e.message}"
                }

                errors += err
                Log.e(err)
                null
            }
        }
    }
}
