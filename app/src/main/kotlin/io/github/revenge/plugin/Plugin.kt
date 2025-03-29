package io.github.revenge.plugin

import android.content.pm.ApplicationInfo
import com.facebook.react.ReactPackage
import com.facebook.react.bridge.Callback
import com.facebook.react.bridge.NativeModule
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReactContextBaseJavaModule
import com.facebook.react.bridge.ReactMethod
import com.facebook.react.uimanager.ViewManager
import dalvik.system.DexClassLoader
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam
import io.github.revenge.plugin.PluginContext.Companion.toPluginContext
import java.io.File
import java.lang.reflect.Member
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.jar.JarFile

class Plugin internal constructor(
    private val name: String,
    private val callbacks: Map<String, Callback>,
    init: () -> Unit
) : ReactContextBaseJavaModule() {
    init {
        init()
    }

    override fun getName(): String = name

    @ReactMethod
    @Suppress("UNUSED")
    fun callbacks() = callbacks
}

class PluginBuilder internal constructor(private val name: String) {
    private var initBlock: (PluginContext) -> Unit = {}
    private val callbackBlocks = mutableMapOf<String, (PluginContext, Array<Any>) -> Unit>()

    fun init(block: (PluginContext) -> Unit) {
        initBlock = block
    }

    operator fun String.invoke(callbackBlock: (PluginContext, Array<Any>) -> Unit) {
        callbackBlocks[this] = callbackBlock
    }

    internal fun build(context: PluginContext) = Plugin(
        name = name,
        callbacks = callbackBlocks.mapValues { (_, block) -> Callback { args -> block(context, args) } },
        init = { initBlock(context) }
    )
}

fun plugin(name: String, block: PluginBuilder.() -> Unit) =
    PluginBuilder(name).apply(block)

class PluginContext private constructor(
    val appInfo: ApplicationInfo? = null,
    val classLoader: ClassLoader? = null,
    val isFirstApplication: Boolean = false,
    val packageName: String? = null,
    val processName: String? = null
) {
    internal companion object {
        internal fun LoadPackageParam.toPluginContext() = PluginContext(
            appInfo = appInfo,
            classLoader = classLoader,
            isFirstApplication = isFirstApplication,
            packageName = packageName,
            processName = processName
        )
    }
}

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
            CLASS_LOADER // Provide plugin APIs.
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
                method.returnType.isPluginBuilder && method.parameterTypes.size == 0 && method.canAccess()
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

// Class loader providing plugin APIs.
private val CLASS_LOADER = object {}.javaClass.classLoader

private val Class<*>.isPluginBuilder get() = PluginBuilder::class.java.isAssignableFrom(this)

private fun Member.canAccess(): Boolean {
    if (this is Method && parameterTypes.size != 0) return false

    return Modifier.isStatic(modifiers) && Modifier.isPublic(modifiers)
}