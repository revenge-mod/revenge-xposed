package io.github.revenge.xposed.modules

import android.content.res.AssetManager
import android.content.res.XModuleResources
import de.robv.android.xposed.IXposedHookZygoteInit
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.callbacks.XC_LoadPackage
import io.github.revenge.xposed.Constants
import io.github.revenge.xposed.Module
import io.github.revenge.xposed.Utils.Log
import io.github.revenge.xposed.modules.HookScriptLoaderModule.Companion.PRELOADS_DIR
import kotlinx.coroutines.runBlocking
import java.io.File

/**
 * Hooks React Native's script loading methods to load custom scripts and bundles.
 *
 * Preload scripts should be placed in the [PRELOADS_DIR] directory inside the module's files directory.
 *
 * The main bundle should be placed in the [Constants.CACHE_DIR] directory named [Constants.MAIN_SCRIPT_FILE].
 * If the bundle file does not exist, it will attempt to load `assets://revenge.bundle` from the module's assets.
 */
class HookScriptLoaderModule : Module() {
    private lateinit var preloadsDir: File
    private lateinit var mainScript: File

    private lateinit var modulePath: String
    private lateinit var resources: XModuleResources

    companion object {
        const val PRELOADS_DIR = "preloads"
    }

    override fun onInit(startupParam: IXposedHookZygoteInit.StartupParam) {
        this@HookScriptLoaderModule.modulePath = startupParam.modulePath
    }

    override fun onLoad(packageParam: XC_LoadPackage.LoadPackageParam) = with(packageParam) {
        val cacheDir = File(appInfo.dataDir, Constants.CACHE_DIR).apply { asDir() }
        val filesDir = File(appInfo.dataDir, Constants.FILES_DIR).apply { asDir() }

        preloadsDir = File(filesDir, PRELOADS_DIR).apply { asFile() }
        mainScript = File(cacheDir, Constants.MAIN_SCRIPT_FILE).apply { asFile() }

        listOf(
            "com.facebook.react.runtime.ReactInstance$1",
            // TODO: Remove once Discord fully switches to Bridgeless
            "com.facebook.react.bridge.CatalystInstanceImpl"
        ).mapNotNull { classLoader.safeLoadClass(it) }
            .forEach { hook(it) }
    }

    private fun hook(instance: Class<*>) {
        val loadScriptFromAssets = instance.method(
            "loadScriptFromAssets",
            AssetManager::class.java,
            String::class.java,
            Boolean::class.javaPrimitiveType
        )

        val loadScriptFromFile = instance.method(
            "loadScriptFromFile",
            String::class.java,
            String::class.java,
            Boolean::class.javaPrimitiveType
        )

        loadScriptFromAssets.hook {
            before {
                Log.i("Received call to loadScriptFromAssets, running our scripts first... (asset: ${args[1]}, sync: ${args[2]})")

                // TODO: Is there a better way to do this?
                runBlocking { UpdaterModule.job?.join() }

                val loadSynchronously = args[2]
                val runScriptFile = { file: File ->
                    Log.i("Loading script: ${file.absolutePath}")

                    XposedBridge.invokeOriginalMethod(
                        loadScriptFromFile,
                        thisObject,
                        arrayOf(file.absolutePath, file.absolutePath, loadSynchronously)
                    )

                    Unit
                }

                try {
                    preloadsDir.walk()
                        .filter { it.isFile }
                        .forEach(runScriptFile)

                    if (mainScript.exists()) runScriptFile(mainScript)
                    else {
                        Log.i("Main script does not exist, falling back")

                        if (!::resources.isInitialized) resources =
                            XModuleResources.createInstance(modulePath, null)

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
    }
}