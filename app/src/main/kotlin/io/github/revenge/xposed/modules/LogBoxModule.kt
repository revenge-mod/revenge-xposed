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
        val dcdReactNativeHostClass = classLoader.safeLoadClass("com.discord.bridge.DCDReactNativeHost")
        val getUseDeveloperSupportMethod = dcdReactNativeHostClass?.methods?.firstOrNull { it.name == "getUseDeveloperSupport" }
        getUseDeveloperSupportMethod?.hook {
            before {
                result = true
            }
        }

        return@with
    }

    override fun onContext(context: Context) {
        listOf(
            "com.facebook.react.devsupport.BridgeDevSupportManager",
            "com.facebook.react.devsupport.BridgelessDevSupportManager"
        ).mapNotNull { packageParam.classLoader.safeLoadClass(it) }.forEach {
            hookDevSupportManager(it, context)
        }
    }

    private fun hookDevSupportManager(clazz: Class<*>, context: Context) {
        val handleReloadJSMethod = clazz.methods.firstOrNull { it.name == "handleReloadJS" }
        val showDevOptionsDialogMethod = clazz.methods.firstOrNull { it.name == "showDevOptionsDialog" }

        // Replace the method to direct relaunch the app instead of sending reload command to developer server
        handleReloadJSMethod?.hook {
            before {
                reloadApp()
                result = null
            }
        }

         // Triggered on shake
        showDevOptionsDialogMethod?.hook {
            before {
                try {
                    val targetDebuggable = isTargetAppDebuggable()
                    if (targetDebuggable) {
                        return@before
                    }

                    Utils.showRecoveryAlert(context)

                    result = null
                } catch (ex: Exception) {
                    Log.e("LogBoxModule.showDevOptionsDialog.before - unexpected exception: $ex", ex)
                }
            }
        }
    }
}
