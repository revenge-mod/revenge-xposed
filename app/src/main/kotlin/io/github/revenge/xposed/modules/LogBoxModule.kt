package io.github.revenge.xposed.modules

import android.app.AlertDialog
import android.app.AndroidAppHelper
import android.content.Context
import android.content.Intent
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import io.github.revenge.xposed.Constants
import io.github.revenge.xposed.Module
import io.github.revenge.xposed.Utils
import java.io.File
import kotlin.system.exitProcess

class LogBoxModule: Module() {
    lateinit var packageParam: XC_LoadPackage.LoadPackageParam

    override fun onLoad(packageParam: XC_LoadPackage.LoadPackageParam) = with (packageParam) {
        this@LogBoxModule.packageParam = packageParam

        val dcdReactNativeHostClass = classLoader.loadClass("com.discord.bridge.DCDReactNativeHost")
        val bridgeDevSupportManagerClass = classLoader.loadClass("com.facebook.react.devsupport.BridgeDevSupportManager")

        val mReactInstanceDevHelperField = XposedHelpers.findField(bridgeDevSupportManagerClass, "mReactInstanceDevHelper")

        val getUseDeveloperSupportMethod = dcdReactNativeHostClass.methods.first { it.name == "getUseDeveloperSupport" }
        val handleReloadJSMethod = bridgeDevSupportManagerClass.methods.first { it.name == "handleReloadJS" }
        val showDevOptionsDialogMethod = bridgeDevSupportManagerClass.methods.first { it.name == "showDevOptionsDialog" }

        var alertDialog: AlertDialog? = null

        // This enables the LogBox and opens dev option on shake
        XposedBridge.hookMethod(
            getUseDeveloperSupportMethod,
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    param.result = true
                }
            }
        )

        // Triggered on shake
        XposedBridge.hookMethod(
            showDevOptionsDialogMethod,
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    try {
                        if (alertDialog == null) {
                            val mReactInstanceDevHelper =
                                mReactInstanceDevHelperField.get(param.thisObject)
                            val getCurrentActivityMethod =
                                mReactInstanceDevHelper.javaClass.methods.first { it.name == "getCurrentActivity" }

                            val context =
                                getCurrentActivityMethod.invoke(mReactInstanceDevHelper) as Context

                            alertDialog = showRecoveryAlert(context) {
                                alertDialog = null
                            }
                        }
                    } catch (ex: Exception) {
                        Utils.Log.e("Failed to show dev options dialog: $ex")
                        alertDialog = null
                    }

                    // Ignore the original dev menu
                    param.result = null
                }
            }
        )

        // Replace the method to direct relaunch the app instead of sending reload command to developer server
        XposedBridge.hookMethod(
            handleReloadJSMethod,
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) = reloadApp()
            }
        )

        return@with
    }

    private fun showRecoveryAlert(context: Context, onClose: () -> Unit): AlertDialog {
        return AlertDialog.Builder(context)
            .setTitle("Revenge Recovery Options")
            .setItems(arrayOf("Reload", "Delete bundle.js")) { _, which ->
                when (which) {
                    0 -> {
                        reloadApp()
                    }
                    1 -> {
                        val bundleFile = File(packageParam.appInfo.dataDir, "${Constants.CACHE_DIR}/${Constants.BUNDLE_FILE}")
                        if (bundleFile.exists()) bundleFile.delete()

                        reloadApp()
                    }
                }

                onClose()
            }
            .setOnDismissListener {
                onClose()
            }
            .show()
    }

    private fun reloadApp() {
        val application = AndroidAppHelper.currentApplication()
        val intent = application.packageManager.getLaunchIntentForPackage(application.packageName)
        application.startActivity(Intent.makeRestartActivityTask(intent!!.component))
        exitProcess(0)
    }
}