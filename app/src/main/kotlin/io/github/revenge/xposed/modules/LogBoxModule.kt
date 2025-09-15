package io.github.revenge.xposed.modules

import android.app.AlertDialog
import android.content.Context
import de.robv.android.xposed.callbacks.XC_LoadPackage
import io.github.revenge.xposed.Constants
import io.github.revenge.xposed.Module
import io.github.revenge.xposed.Utils.Companion.reloadApp
import io.github.revenge.xposed.Utils.Log
import java.io.File

class LogBoxModule : Module() {
    lateinit var packageParam: XC_LoadPackage.LoadPackageParam
    lateinit var bridgeDevSupportManagerClass: Class<*>

    override fun onLoad(packageParam: XC_LoadPackage.LoadPackageParam) = with(packageParam) {
        this@LogBoxModule.packageParam = packageParam

        val dcdReactNativeHostClass = classLoader.loadClass("com.discord.bridge.DCDReactNativeHost")
        bridgeDevSupportManagerClass = classLoader.loadClass("com.facebook.react.devsupport.BridgeDevSupportManager")

        val getUseDeveloperSupportMethod = dcdReactNativeHostClass.methods.first { it.name == "getUseDeveloperSupport" }
        val handleReloadJSMethod = bridgeDevSupportManagerClass.methods.first { it.name == "handleReloadJS" }

        // This enables the LogBox and opens dev option on shake
        getUseDeveloperSupportMethod.hook {
            before {
                result = true
            }
        }

        // Replace the method to direct relaunch the app instead of sending reload command to developer server
        handleReloadJSMethod.hook {
            before {
                reloadApp()
                result = null
            }
        }

        return@with
    }

    override fun onContext(context: Context) {
        val showDevOptionsDialogMethod =
            bridgeDevSupportManagerClass.methods.first { it.name == "showDevOptionsDialog" }

        // Triggered on shake
        showDevOptionsDialogMethod.hook {
            before {
                try {
                    showRecoveryAlert(context)
                } catch (ex: Exception) {
                    Log.e("Failed to show dev options dialog: $ex")
                }

                // Ignore the original dev menu
                param.result = null
            }
        }
    }

    private fun showRecoveryAlert(context: Context) {
        AlertDialog.Builder(context).setTitle("Revenge Recovery Options")
            .setItems(arrayOf("Reload", "Delete bundle.js")) { _, which ->
                when (which) {
                    0 -> {
                        reloadApp()
                    }

                    1 -> {
                        val bundleFile =
                            File(packageParam.appInfo.dataDir, "${Constants.CACHE_DIR}/${Constants.MAIN_SCRIPT_FILE}")
                        if (bundleFile.exists()) bundleFile.delete()

                        reloadApp()
                    }
                }
            }.show()
    }
}