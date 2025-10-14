package io.github.revenge.xposed.modules

import android.content.Context
import de.robv.android.xposed.callbacks.XC_LoadPackage
import io.github.revenge.xposed.Constants
import io.github.revenge.xposed.Module
import io.github.revenge.xposed.Utils
import io.github.revenge.xposed.Utils.Companion.reloadApp
import io.github.revenge.xposed.Utils.Log

class LogBoxModule : Module() {
    lateinit var packageParam: XC_LoadPackage.LoadPackageParam

    override fun onLoad(packageParam: XC_LoadPackage.LoadPackageParam) = with(packageParam) {
        this@LogBoxModule.packageParam = packageParam

        val dcdReactNativeHostClass = classLoader.loadClass("com.discord.bridge.DCDReactNativeHost")
        val getUseDeveloperSupportMethod = dcdReactNativeHostClass.methods.first { it.name == "getUseDeveloperSupport" }

        // This enables the LogBox and opens dev option on shake
        getUseDeveloperSupportMethod.hook {
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
        ).mapNotNull { packageParam.classLoader.safeLoadClass(it) }.forEach { hookDevSupportManager(it, context) }
    }

    private fun hookDevSupportManager(clazz: Class<*>, context: Context) {
        val handleReloadJSMethod = clazz.methods.first { it.name == "handleReloadJS" }
        val showDevOptionsDialogMethod = clazz.methods.first { it.name == "showDevOptionsDialog" }

        // Replace the method to direct relaunch the app instead of sending reload command to developer server
        handleReloadJSMethod.hook {
            before {
                reloadApp()
                result = null
            }
        }

        // Triggered on shake
        showDevOptionsDialogMethod.hook {
            before {
                try {
                    Utils.showRecoveryAlert(context)
                } catch (ex: Exception) {
                    Log.e("Failed to show dev options dialog: $ex")
                }

                // Ignore the original dev menu
                param.result = null
            }
        }
    }
}