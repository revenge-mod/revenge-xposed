package io.github.revenge.xposed.modules

import android.content.Context
import de.robv.android.xposed.callbacks.XC_LoadPackage
import io.github.revenge.xposed.BuildConfig
import io.github.revenge.xposed.Module
import io.github.revenge.xposed.Utils
import io.github.revenge.xposed.Utils.Companion.reloadApp
import io.github.revenge.xposed.Utils.Log

object LogBoxModule : Module() {
    lateinit var packageParam: XC_LoadPackage.LoadPackageParam

    private fun isTargetAppDebuggable(): Boolean {
        return try {
            val flags = packageParam.appInfo?.flags ?: 0
            (flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
        } catch (e: Exception) {
            BuildConfig.DEBUG
        }
    }

    override fun onLoad(packageParam: XC_LoadPackage.LoadPackageParam) = with(packageParam) {
        this@LogBoxModule.packageParam = packageParam

        // enable bridgeless dev menu only in debug builds
        if (BuildConfig.DEBUG) {
            try {
                val dcdReactNativeHostClass = classloaderSafeLoad("com.discord.bridge.DCDReactNativeHost")
                val getUseDeveloperSupportMethod = dcdReactNativeHostClass?.methods?.firstOrNull { it.name == "getUseDeveloperSupport" }
                getUseDeveloperSupportMethod?.hook {
                    before {
                        result = true
                    }
                }
            } catch (ex: Exception) {
                Log.e("LogBoxModule.onLoad - failed to hook DCDReactNativeHost/getUseDeveloperSupport: $ex", ex)
            }
        }

        return@with
    }

    override fun onContext(context: Context) {
        val devManagers = listOf(
            "com.facebook.react.devsupport.BridgeDevSupportManager",
            "com.facebook.react.devsupport.BridgelessDevSupportManager",
            "com.facebook.react.devsupport.DevSupportManagerImpl",
            "com.facebook.react.devsupport.DevSupportManagerBase"
        )

        devManagers.mapNotNull { packageParam.classLoader.safeLoadClass(it) }.forEach {
            hookDevSupportManager(it, context)
        }
    }

    private fun hookDevSupportManager(clazz: Class<*>, context: Context) {
        val handleReloadJSMethod = clazz.methods.firstOrNull { it.name == "handleReloadJS" }
        val showDevOptionsDialogMethod = clazz.methods.firstOrNull { it.name == "showDevOptionsDialog" }

        handleReloadJSMethod?.hook {
            before {
                try {
                    reloadApp()
                } catch (ex: Exception) {
                    Log.e("LogBoxModule.handleReloadJS.before - error while reloading app: $ex", ex)
                }
                result = null
            }
        }

        if (showDevOptionsDialogMethod == null) return

        showDevOptionsDialogMethod.hook {
            before {
                try {
                    val targetDebuggable = isTargetAppDebuggable()
                    if (BuildConfig.DEBUG || targetDebuggable) {
                        return@before
                    }

                    try {
                        Utils.showRecoveryAlert(context)
                    } catch (inner: Exception) {
                        Log.e("LogBoxModule.showDevOptionsDialog.before - failed to show recovery alert: $inner", inner)
                    }

                    result = null
                } catch (ex: Exception) {
                    Log.e("LogBoxModule.showDevOptionsDialog.before - unexpected exception: $ex", ex)
                }
            }
        }
    }

    // Helper: safe load using reflection-compatible name resolution
    private fun classloaderSafeLoad(name: String): Class<*>? = runCatching { packageParam.classLoader.loadClass(name) }.getOrNull()
}
