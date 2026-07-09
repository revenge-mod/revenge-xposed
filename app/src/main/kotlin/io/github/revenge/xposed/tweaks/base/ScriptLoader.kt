package io.github.revenge.xposed.tweaks.base

import android.content.res.AssetManager
import android.content.res.XModuleResources
import de.robv.android.xposed.XposedBridge
import io.github.revenge.xposed.*
import java.lang.reflect.Method
import java.util.concurrent.CopyOnWriteArrayList

lateinit var resources: XModuleResources

fun interface ScriptInjector {
    fun inject(scope: InjectorScope)
}

class InjectorScope internal constructor(
    private val tweak: Tweak,
    private val hookScope: HookScope,
    private val loadScriptFromAssets: Method,
    private val loadScriptFromFile: Method,
) {
    val tweakLog get() = tweak.log

    /**
     * Load a Hermes bytecode asset from this module's APK at `assets://<name>`.
     */
    fun runAsset(name: String) {
        if (!::resources.isInitialized) {
            resources = XModuleResources.createInstance(tweak.modulePath, null)
        }
        XposedBridge.invokeOriginalMethod(
            loadScriptFromAssets,
            hookScope.thisObject,
            arrayOf(resources.assets, name, loadSynchronously),
        )
    }

    /**
     * Load a JS or Hermes bytecode file by absolute path.
     */
    fun runFile(absolutePath: String) {
        XposedBridge.invokeOriginalMethod(
            loadScriptFromFile,
            hookScope.thisObject,
            arrayOf(absolutePath, absolutePath, loadSynchronously),
        )
    }

    /** The `loadSynchronously` flag from the intercepted call. */
    val loadSynchronously: Any? get() = hookScope.args[2]
}

private val injectors = CopyOnWriteArrayList<ScriptInjector>()

/**
 * Register a [ScriptInjector] to be invoked by the [scriptLoader] tweak before the host app's bundle is loaded.
 *
 * Injectors run in registration order.
 */
fun registerScriptInjector(injector: ScriptInjector) {
    injectors += injector
}

/**
 * Built-in injector that iterates [scriptAssets] and loads each from the module APK.
 */
private val scriptAssetsInjector = ScriptInjector { scope ->
    for (asset in scriptAssets) scope.runAsset(asset)
}

/**
 * A tweak that hooks React Native's script loading methods to load custom scripts and bundles.
 *
 * Configure [scriptAssets] to load from bundled assets, or register a custom [ScriptInjector] via [registerScriptInjector].
 */
val scriptLoader by tweak {
    // scriptAssets must be first
    if (!injectors.contains(scriptAssetsInjector)) injectors.add(0, scriptAssetsInjector)

    // @Target: This may change between versions
    hookLoader(classLoader.loadClass($$"com.facebook.react.runtime.ReactInstance$loadJSBundle$1"))
}

/**
 * Hooks `loadScriptFromAssets` and `loadScriptFromFile` to run injectors before the host app's bundle.
 */
private fun Tweak.hookLoader(instance: Class<*>) {
    try {
        val loadScriptFromAssets = instance.method(
            "loadScriptFromAssets",
            AssetManager::class.java,
            String::class.java,
            Boolean::class.javaPrimitiveType,
        )

        val loadScriptFromFile = instance.method(
            "loadScriptFromFile",
            String::class.java,
            String::class.java,
            Boolean::class.javaPrimitiveType,
        )

        loadScriptFromAssets.hook {
            before {
                log.d("Received call to loadScriptFromAssets: ${args[1]} (sync: ${args[2]})")
                runInjectors(this@hookLoader, loadScriptFromAssets, loadScriptFromFile)
            }
        }

        loadScriptFromFile.hook {
            before {
                log.d("Received call to loadScriptFromFile: ${args[0]} (sync: ${args[2]})")
                runInjectors(this@hookLoader, loadScriptFromAssets, loadScriptFromFile)
            }
        }
    } catch (e: Exception) {
        log.e("Failed to hook script loading methods in ${instance.name}", e)
    }
}

fun HookScope.runInjectors(
    tweak: Tweak,
    loadScriptFromAssets: Method,
    loadScriptFromFile: Method,
) {
    val scope = InjectorScope(tweak, this, loadScriptFromAssets, loadScriptFromFile)
    for (injector in injectors) {
        try {
            injector.inject(scope)
        } catch (e: Throwable) {
            tweak.log.e("ScriptInjector threw: ${injector::class.java.name}", e)
        }
    }
}