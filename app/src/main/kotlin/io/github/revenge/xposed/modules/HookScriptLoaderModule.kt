package io.github.revenge.xposed.modules

import android.content.res.AssetManager
import android.content.res.XModuleResources
import de.robv.android.xposed.IXposedHookZygoteInit
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.callbacks.XC_LoadPackage
import io.github.revenge.xposed.Constants
import io.github.revenge.xposed.HookStateHolder
import io.github.revenge.xposed.Module
import io.github.revenge.xposed.Utils.Log
import io.github.revenge.xposed.modules.HookScriptLoaderModule.PRELOADS_DIR
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import java.io.File
import java.lang.reflect.Method

/**
 * Hooks React Native's script loading methods to load custom scripts and bundles.
 *
 * Preload scripts should be placed in the [PRELOADS_DIR] directory inside the module's files directory.
 *
 * The main bundle should be placed in the [Constants.CACHE_DIR] directory named [Constants.MAIN_SCRIPT_FILE].
 * If the bundle file does not exist, it will attempt to load `assets://revenge.bundle` from the module's assets.
 */
object HookScriptLoaderModule : Module() {
    private lateinit var preloadsDir: File
    private lateinit var mainScript: File

    private lateinit var modulePath: String
    private lateinit var resources: XModuleResources

    const val PRELOADS_DIR = "preloads"

    override fun onInit(startupParam: IXposedHookZygoteInit.StartupParam) {
        this@HookScriptLoaderModule.modulePath = startupParam.modulePath
    }

    override fun onLoad(packageParam: XC_LoadPackage.LoadPackageParam) = with(packageParam) {
        val cacheDir = File(appInfo.dataDir, Constants.CACHE_DIR).apply { asDir() }
        val filesDir = File(appInfo.dataDir, Constants.FILES_DIR).apply { asDir() }

        preloadsDir = File(filesDir, PRELOADS_DIR).apply { asDir() }
        mainScript = File(cacheDir, Constants.MAIN_SCRIPT_FILE).apply { asFile() }

        listOf(
            "com.facebook.react.runtime.ReactInstance\$loadJSBundle$1",
            "com.facebook.react.runtime.ReactInstance$1",
            // TODO: Remove once Discord fully switches to Bridgeless
            "com.facebook.react.bridge.CatalystInstanceImpl"
        ).mapNotNull { classLoader.safeLoadClass(it) }.forEach { hook(it) }
    }

    private fun hook(instance: Class<*>) = runCatching {
        val loadScriptFromAssets = instance.method(
            "loadScriptFromAssets", AssetManager::class.java, String::class.java, Boolean::class.javaPrimitiveType
        )

        val loadScriptFromFile = instance.method(
            "loadScriptFromFile", String::class.java, String::class.java, Boolean::class.javaPrimitiveType
        )

        loadScriptFromAssets.hook {
            before {
                Log.i("Received call to loadScriptFromAssets: ${args[1]} (sync: ${args[2]})")
                runCustomScripts(loadScriptFromFile, loadScriptFromAssets)
            }
        }

        loadScriptFromFile.hook {
            before {
                Log.i("Received call to loadScriptFromFile: ${args[0]} (sync: ${args[2]})")
                runCustomScripts(loadScriptFromFile, loadScriptFromAssets)
            }
        }
    }.onFailure {
        Log.e("Failed to hook script loading methods in ${instance.name}:", it)
    }

    private fun HookScope.runCustomScripts(loadScriptFromFile: Method, loadScriptFromAssets: Method) {
        Log.i("Running custom scripts...")

        runBlocking {
            val ready = async { HookStateHolder.readyDeferred.join() }
            val download = async { UpdaterModule.downloadScript().join() }

            awaitAll(ready, download)
        }

        val loadSynchronously = args[2]
        val runScriptFile = { file: File ->
            Log.i("Loading script: ${file.absolutePath}")

            XposedBridge.invokeOriginalMethod(
                loadScriptFromFile, thisObject, arrayOf(file.absolutePath, file.absolutePath, loadSynchronously)
            )

            Unit
        }

        try {
            preloadsDir.walk().filter { it.isFile }.forEach(runScriptFile)

            if (mainScript.exists()) runScriptFile(mainScript)
            else {
                Log.i("Main script does not exist, falling back")

                if (!::resources.isInitialized) resources = XModuleResources.createInstance(modulePath, null)

                XposedBridge.invokeOriginalMethod(
                    loadScriptFromAssets,
                    thisObject,
                    arrayOf(resources.assets, "assets://revenge.bundle", loadSynchronously)
                )
            }
        } catch (e: Throwable) {
            Log.e("Unable to run scripts:", e)
        }
    }
}
