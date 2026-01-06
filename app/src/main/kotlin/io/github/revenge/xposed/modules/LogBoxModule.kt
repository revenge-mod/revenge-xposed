package io.github.revenge.xposed.modules

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.content.res.ColorStateList
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import de.robv.android.xposed.XC_MethodReplacement
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import io.github.revenge.xposed.Module
import io.github.revenge.xposed.Utils.Companion.reloadApp
import io.github.revenge.xposed.Utils.Companion.JSON
import io.github.revenge.xposed.Utils.Log
import io.github.revenge.xposed.Constants
import kotlinx.serialization.encodeToString
import org.json.JSONObject
import java.io.File

object LogBoxModule : Module() {
    lateinit var packageParam: XC_LoadPackage.LoadPackageParam
    private var lastKnownContext: Context? = null

    private const val COLOR_SURFACE = "#1A1111"
    private const val COLOR_ON_SURFACE = "#F1E0E0"
    private const val COLOR_PRIMARY_CONTAINER = "#8C1D1D"
    private const val COLOR_ON_PRIMARY_CONTAINER = "#FFFFFF"
    private const val COLOR_ON_SURFACE_VARIANT = "#A69090"
    private const val COLOR_ERROR = "#FFB4AB"

    override fun onLoad(packageParam: XC_LoadPackage.LoadPackageParam) = with(packageParam) {
        this@LogBoxModule.packageParam = packageParam
        try {
            val dcdReactNativeHostClass = classLoader.loadClass("com.discord.bridge.DCDReactNativeHost")
            val getUseDeveloperSupportMethod = dcdReactNativeHostClass.methods.first { it.name == "getUseDeveloperSupport" }
            getUseDeveloperSupportMethod.hook { before { result = true } }
        } catch (e: Exception) {
            Log.e("RevengeXposed: Failed to hook DCDReactNativeHost")
        }
        return@with
    }

    override fun onContext(context: Context) {
        lastKnownContext = context
        val devManagers = listOf(
            "com.facebook.react.devsupport.BridgeDevSupportManager",
            "com.facebook.react.devsupport.BridgelessDevSupportManager",
            "com.facebook.react.devsupport.DevSupportManagerImpl",
            "com.facebook.react.devsupport.DevSupportManagerBase"
        )
        devManagers.forEach { className ->
            try {
                val clazz = packageParam.classLoader.loadClass(className)
                hookDevSupportManager(clazz)
            } catch (ignored: Exception) {}
        }
    }

    private fun hookDevSupportManager(clazz: Class<*>) {
        val handleReloadJSMethod = clazz.methods.firstOrNull { it.name == "handleReloadJS" }
        val showDevOptionsDialogMethod = clazz.methods.firstOrNull { it.name == "showDevOptionsDialog" }

        handleReloadJSMethod?.let {
            XposedBridge.hookMethod(it, object : XC_MethodReplacement() {
                override fun replaceHookedMethod(param: MethodHookParam): Any? {
                    reloadApp()
                    return null
                }
            })
        }

        showDevOptionsDialogMethod?.let {
            XposedBridge.hookMethod(it, object : XC_MethodReplacement() {
                override fun replaceHookedMethod(param: MethodHookParam): Any? {
                    val activityContext = getContextFromDevSupport(clazz, param.thisObject)
                    if (activityContext is Activity) showRecoveryMenu(activityContext)
                    return null
                }
            })
        }
    }

    private fun getContextFromDevSupport(clazz: Class<*>, instance: Any?): Context? {
        if (instance == null) return null
        val helpers = listOf(
            "mReactInstanceDevHelper",
            "reactInstanceDevHelper",
            "mReactInstanceManager",
            "mApplicationContext"
        )
        for (fieldName in helpers) {
            try {
                val field = XposedHelpers.findFieldIfExists(clazz, fieldName) ?: continue
                val helper = field.get(instance) ?: continue
                if (helper is Activity) return helper
                val getCurrentActivityMethod = helper.javaClass.methods.firstOrNull { it.name == "getCurrentActivity" }
                val act = getCurrentActivityMethod?.invoke(helper) as? Activity
                if (act != null) return act
            } catch (ignored: Exception) {}
        }
        return lastKnownContext
    }

    private fun showRecoveryMenu(context: Context) {
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(context, 20), dp(context, 20), dp(context, 20), dp(context, 20))
            background = createShape(context, Color.parseColor(COLOR_SURFACE), 24f)
        }

        // Header with icon
        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(context, 16))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }

        try {
            // Logo
            header.addView(LogoView(context).apply {
                layoutParams = LinearLayout.LayoutParams(dp(context, 31), dp(context, 31)).apply {
                    marginEnd = dp(context, 10)
                }
            })

            header.addView(TextView(context).apply {
                text = "Revenge Recovery"
                textSize = 22f
                setTextColor(Color.parseColor(COLOR_ON_SURFACE))
                typeface = try {
                    android.graphics.Typeface.create("GoogleSans", android.graphics.Typeface.NORMAL)
                } catch (e: Exception) {
                    android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
                }
            })

            container.addView(header)
        } catch (e: Exception) {
            // Log the error so we can see the failure cause in logcat/logfox
            Log.e("RevengeXposed: Failed to create header", e)

            // Fallback
            container.addView(TextView(context).apply {
                text = "Revenge Recovery"
                textSize = 22f
                setTextColor(Color.parseColor(COLOR_ON_SURFACE))
                typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
                setPadding(0, 0, 0, dp(context, 16))
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            })
        }

        // Reload app option
        container.addView(createMenuButton(context, "Reload App") { reloadApp() })

        // Safe Mode
        val safeModeEnabled = isSafeModeEnabled(context)
        val safeModeText = if (safeModeEnabled) "Disable Safe Mode" else "Enable Safe Mode"
        container.addView(createMenuButton(context, safeModeText) {
            showConfirmationDialog(context, "Safe Mode", "Toggle Safe Mode and restart?") {
                toggleSafeMode(context)
            }
        })

        container.addView(createMenuButton(context, "Custom Bundle URL") { showBundleUrlDialog(context) })

        // Clear Bundle option
        container.addView(createMenuButton(context, "Clear Bundle") {
            showConfirmationDialog(context, "Clear Bundle", "Clear cached bundle and reset to default?") {
                clearBundle(context)
            }
        })

        val dialog = AlertDialog.Builder(context).setView(container).create()
        dialog.window?.setBackgroundDrawable(createShape(context, Color.TRANSPARENT, 0f))

        dialog.show()

        val displayMetrics = context.resources.displayMetrics
        val screenWidth = displayMetrics.widthPixels
        val targetPercent = 0.90f
        val maxWidth = (screenWidth * targetPercent).toInt()
        val horizontalPadding = dp(context, 24)
        val finalWidth = (screenWidth - horizontalPadding).coerceAtMost(maxWidth)
        dialog.window?.setLayout(finalWidth, WindowManager.LayoutParams.WRAP_CONTENT)
        dialog.window?.setGravity(Gravity.CENTER)
    }

    private fun showBundleUrlDialog(context: Context) {
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(context, 20), dp(context, 20), dp(context, 20), dp(context, 20))
            background = createShape(context, Color.parseColor(COLOR_SURFACE), 24f)
        }

        root.addView(TextView(context).apply {
            text = "Bundle Settings"
            textSize = 17f
            setTextColor(Color.parseColor(COLOR_ON_SURFACE))
            typeface = try {
                android.graphics.Typeface.create("GoogleSans", android.graphics.Typeface.NORMAL)
            } catch (e: Exception) {
                android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.NORMAL)
            }
            setPadding(0, 0, 0, dp(context, 10))
        })

        root.addView(TextView(context).apply {
            text = "Enter the developer server URL"
            textSize = 14f
            setTextColor(Color.parseColor(COLOR_ON_SURFACE_VARIANT))
            typeface = try {
                android.graphics.Typeface.create("GoogleSans", android.graphics.Typeface.NORMAL)
            } catch (e: Exception) {
                android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.NORMAL)
            }
            setPadding(0, 0, 0, dp(context, 16))
        })

        // example link to bundle
        val input = EditText(context).apply {
            hint = "http://localhost:4040/index.js"
            setHintTextColor(Color.parseColor("#44F1E0E0"))
            setTextColor(Color.parseColor(COLOR_ON_SURFACE))
            textSize = 14f
            typeface = try {
                android.graphics.Typeface.create("GoogleSans", android.graphics.Typeface.NORMAL)
            } catch (e: Exception) {
                android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.NORMAL)
            }
            background = createShape(context, Color.parseColor("#251818"), 12f)
            setPadding(dp(context, 14), dp(context, 12), dp(context, 14), dp(context, 12))
            setText(getSavedBundleUrl(context))
        }
        root.addView(input)

        val buttonBar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            setPadding(0, dp(context, 18), 0, 0)
        }

        val dialog = AlertDialog.Builder(context).setView(root).create()
        buttonBar.addView(createTextButton(context, "Cancel") { dialog.dismiss() })
        buttonBar.addView(createTextButton(context, "Save", true) {
            saveBundleUrl(context, input.text.toString())
            dialog.dismiss()
            reloadApp()
        })

        root.addView(buttonBar)
        dialog.window?.setBackgroundDrawable(createShape(context, Color.TRANSPARENT, 0f))

        dialog.show()

        val displayMetrics = context.resources.displayMetrics
        val screenWidth = displayMetrics.widthPixels
        val targetPercent = 0.90f
        val maxWidth = (screenWidth * targetPercent).toInt()
        val horizontalPadding = dp(context, 24)
        val finalWidth = (screenWidth - horizontalPadding).coerceAtMost(maxWidth)
        dialog.window?.setLayout(finalWidth, WindowManager.LayoutParams.WRAP_CONTENT)
        dialog.window?.setGravity(Gravity.CENTER)
    }

    private fun showConfirmationDialog(context: Context, title: String, message: String, onConfirm: () -> Unit) {
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(context, 20), dp(context, 20), dp(context, 20), dp(context, 20))
            background = createShape(context, Color.parseColor(COLOR_SURFACE), 24f)
        }

        root.addView(TextView(context).apply {
            text = title
            textSize = 17f
            setTextColor(Color.parseColor(COLOR_ON_SURFACE))
            typeface = try {
                android.graphics.Typeface.create("GoogleSans", android.graphics.Typeface.NORMAL)
            } catch (e: Exception) {
                android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
            }
            setPadding(0, 0, 0, dp(context, 12))
        })

        root.addView(TextView(context).apply {
            text = message
            textSize = 14f
            setTextColor(Color.parseColor(COLOR_ON_SURFACE_VARIANT))
            typeface = try {
                android.graphics.Typeface.create("GoogleSans", android.graphics.Typeface.NORMAL)
            } catch (e: Exception) {
                android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.NORMAL)
            }
        })

        val buttonBar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            setPadding(0, dp(context, 18), 0, 0)
        }

        val dialog = AlertDialog.Builder(context).setView(root).create()
        buttonBar.addView(createTextButton(context, "No") { dialog.dismiss() })
        buttonBar.addView(createTextButton(context, "Yes", true) {
            dialog.dismiss()
            onConfirm()
        })

        root.addView(buttonBar)
        dialog.window?.setBackgroundDrawable(createShape(context, Color.TRANSPARENT, 0f))

        dialog.show()

        val displayMetrics = context.resources.displayMetrics
        val screenWidth = displayMetrics.widthPixels
        val targetPercent = 0.88f
        val maxWidth = (screenWidth * targetPercent).toInt()
        val horizontalPadding = dp(context, 24)
        val finalWidth = (screenWidth - horizontalPadding).coerceAtMost(maxWidth)
        dialog.window?.setLayout(finalWidth, WindowManager.LayoutParams.WRAP_CONTENT)
        dialog.window?.setGravity(Gravity.CENTER)
    }

    private fun createMenuButton(context: Context, label: String, onClick: () -> Unit): View {
        return LinearLayout(context).apply {
            setPadding(dp(context, 14), dp(context, 12), dp(context, 14), dp(context, 12))
            val bg = createShape(context, Color.parseColor(COLOR_PRIMARY_CONTAINER), 14f)
            background = RippleDrawable(ColorStateList.valueOf(Color.parseColor("#33FFFFFF")), bg, null)
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, dp(context, 10), 0, 0) }
            addView(TextView(context).apply {
                text = label
                setTextColor(Color.parseColor(COLOR_ON_PRIMARY_CONTAINER))
                textSize = 14f
                typeface = try {
                    android.graphics.Typeface.create("GoogleSans", android.graphics.Typeface.NORMAL)
                } catch (e: Exception) {
                    android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
                }
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(-1, -2)
            })
            setOnClickListener { onClick() }
        }
    }

    private fun createTextButton(context: Context, label: String, isPrimary: Boolean = false, onClick: () -> Unit) = TextView(context).apply {
        text = label
        textSize = 14f
        typeface = try {
            android.graphics.Typeface.create("GoogleSans", android.graphics.Typeface.NORMAL)
        } catch (e: Exception) {
            android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
        }
        setPadding(dp(context, 16), dp(context, 10), dp(context, 16), dp(context, 10))
        setTextColor(Color.parseColor(if (isPrimary) COLOR_ERROR else COLOR_ON_SURFACE_VARIANT))
        setOnClickListener { onClick() }
    }

    private fun getSettingsFile(context: Context) = File(context.dataDir, "${Constants.FILES_DIR}/loader.json")
    private fun getVendettaSettingsFile(context: Context) = File(context.filesDir, "vd_mmkv/VENDETTA_SETTINGS")

    private fun getSavedBundleUrl(context: Context): String {
        return try {
            val file = getSettingsFile(context)

            // If loader.json exists, try to read with the same LoaderConfig used by UpdaterModule
            if (file.exists()) {
                val cfg = runCatching { JSON.decodeFromString<LoaderConfig>(file.readText()) }.getOrNull()
                if (cfg != null) {
                    return if (cfg.customLoadUrl.enabled) cfg.customLoadUrl.url else ""
                }

                // Fallback to manual parsing if decode failed but file exists
                val obj = JSONObject(file.readText())
                val custom = obj.optJSONObject("customLoadUrl") ?: return ""
                return if (custom.optBoolean("enabled", false)) custom.optString("url", "") else ""
            }

            // Fallback to legacy LogBox settings for compatibility
            val legacy = File(context.filesDir, "logbox/LOGBOX_SETTINGS")
            if (legacy.exists()) JSONObject(legacy.readText()).optString("bundleUrl", "") else ""
        } catch (e: Exception) { "" }
    }

    private fun saveBundleUrl(context: Context, url: String) {
        try {
            // Use the same location and config format as UpdaterModule
            val filesDir = File(context.dataDir, Constants.FILES_DIR).apply { mkdirs() }
            val configFile = File(filesDir, "loader.json")

            // Read current config if available
            val current = runCatching {
                if (configFile.exists()) JSON.decodeFromString<LoaderConfig>(configFile.readText()) else LoaderConfig()
            }.getOrDefault(LoaderConfig())

            val newCfg = current.copy(customLoadUrl = CustomLoadUrl(enabled = url.isNotBlank(), url = url))
            configFile.writeText(JSON.encodeToString(newCfg))

            val legacy = File(context.filesDir, "logbox/LOGBOX_SETTINGS")
            if (legacy.exists()) legacy.delete()
        } catch (ignored: Exception) {}
    }

    private fun clearBundle(context: Context) {
        try {
            // Clear custom bundle in loader config
            val settingsFile = getSettingsFile(context)
            if (settingsFile.exists()) {
                val settings = JSONObject(settingsFile.readText())
                val custom = settings.optJSONObject("customLoadUrl") ?: JSONObject()
                custom.put("enabled", false)
                custom.put("url", "")
                settings.put("customLoadUrl", custom)
                settingsFile.writeText(settings.toString())
            }

            Toast.makeText(context, "Bundle cleared", Toast.LENGTH_SHORT).show()
            reloadApp()
        } catch (e: Exception) {
            Toast.makeText(context, "Failed to clear bundle", Toast.LENGTH_SHORT).show()
        }
    }

    private fun isSafeModeEnabled(context: Context): Boolean {
        return try {
            val file = getVendettaSettingsFile(context)
            if (file.exists()) JSONObject(file.readText()).optJSONObject("safeMode")?.optBoolean("enabled", false) ?: false else false
        } catch (e: Exception) { false }
    }

    private fun toggleSafeMode(context: Context) {
        try {
            val file = getVendettaSettingsFile(context)
            file.parentFile?.mkdirs()
            val settings = if (file.exists()) JSONObject(file.readText()) else JSONObject()
            val safeMode = settings.optJSONObject("safeMode") ?: JSONObject()
            safeMode.put("enabled", !safeMode.optBoolean("enabled", false))
            settings.put("safeMode", safeMode)
            file.writeText(settings.toString())
            reloadApp()
        } catch (ignored: Exception) {}
    }

    private fun dp(context: Context, value: Int) = TypedValue.applyDimension(1, value.toFloat(), context.resources.displayMetrics).toInt()
    private fun createShape(context: Context, color: Int, r: Float) = GradientDrawable().apply { setColor(color); cornerRadius = dp(context, r.toInt()).toFloat() }

    private class LogoView(context: Context) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.FILL }
        private val path1 = Path().apply {
            moveTo(9.27f, 99f); lineTo(294.72f, 99f); lineTo(312.23f, 215.27f); cubicTo(312.72f, 222.6f, 310.02f, 229.99f, 304.84f, 235.18f)
            lineTo(265.51f, 274.93f); cubicTo(260.74f, 279.72f, 254.27f, 283.27f, 247.51f, 283.27f); lineTo(182.78f, 283.27f)
            cubicTo(168.72f, 283.27f, 152.98f, 270.12f, 152.98f, 256.07f); lineTo(152.98f, 255.61f); cubicTo(152.98f, 250.06f, 156.9f, 244.85f, 160.09f, 240.6f)
            lineTo(160.29f, 240.34f); lineTo(75.99f, 236.22f); cubicTo(73.99f, 236.13f, 69.01f, 236.05f, 62.99f, 235.98f); cubicTo(43.52f, 235.76f, 27.11f, 221.37f, 24.4f, 202.09f)
            lineTo(8.75f, 103.01f); cubicTo(8.85f, 101.65f, 9.02f, 100.31f, 9.27f, 99f); close()
            moveTo(84.55f, 137.59f); lineTo(139.98f, 152.45f); cubicTo(140f, 152.79f, 140f, 153.13f, 140f, 153.48f); cubicTo(140f, 170.04f, 126.57f, 183.48f, 110f, 183.48f)
            cubicTo(93.43f, 183.48f, 80f, 170.04f, 80f, 153.48f); cubicTo(80f, 147.64f, 81.66f, 142.2f, 84.55f, 137.59f); close()
            moveTo(180.02f, 152.4f); lineTo(235.43f, 137.55f); cubicTo(238.32f, 142.17f, 240f, 147.63f, 240f, 153.48f); cubicTo(240f, 170.04f, 226.57f, 183.48f, 210f, 183.48f)
            cubicTo(193.43f, 183.48f, 180f, 170.04f, 180f, 153.48f); cubicTo(180f, 153.12f, 180.01f, 152.76f, 180.02f, 152.4f); close()
        }
        private val path2 = Path().apply {
            moveTo(288.33f, 82.03f); cubicTo(288.57f, 83.42f, 289.2f, 84.71f, 290.02f, 85.86f); cubicTo(292.71f, 89.64f, 294.37f, 94.08f, 294.69f, 98.77f)
            lineTo(295.9f, 106.81f); cubicTo(296.63f, 111.65f, 292.88f, 116f, 287.99f, 116f); lineTo(17.64f, 116f); cubicTo(13.7f, 116f, 10.35f, 113.14f, 9.74f, 109.25f)
            lineTo(8.89f, 103.91f); cubicTo(8.8f, 103.31f, 8.77f, 102.7f, 8.83f, 102.09f); cubicTo(10.43f, 85.73f, 23.3f, 72.67f, 39.73f, 70.91f); lineTo(87.19f, 63.33f)
            cubicTo(89.98f, 62.88f, 92.8f, 63.94f, 94.6f, 66.12f); lineTo(105.91f, 79.74f); cubicTo(107.72f, 81.91f, 110.53f, 82.98f, 113.32f, 82.53f); lineTo(203.66f, 68.23f)
            cubicTo(205.85f, 67.88f, 207.8f, 66.63f, 209.04f, 64.79f); lineTo(222.88f, 44.23f); cubicTo(224.12f, 42.39f, 226.06f, 41.15f, 228.25f, 40.8f)
            lineTo(247.64f, 37.7f); cubicTo(265.68f, 35.77f, 282.18f, 48.04f, 285.52f, 65.87f); lineTo(288.33f, 82.03f); close()
        }
        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val scale = width.toFloat() / 320f
            canvas.scale(scale, scale)
            path1.fillType = Path.FillType.EVEN_ODD
            canvas.drawPath(path1, paint)
            canvas.drawPath(path2, paint)
        }
    }
}
