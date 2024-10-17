package io.github.revenge.xposed

import android.content.Context
import android.graphics.Color
import android.content.res.Resources
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.callbacks.XC_LoadPackage
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import kotlinx.serialization.json.*

@Serializable
data class Author(
    val name: String,
    val id: String? = null
)
@Serializable
data class ThemeData(
    val name: String,
    val description: String? = null,
    val authors: List<Author>? = null,
    val spec: Int,
    val semanticColors: Map<String, List<String>>? = null,
    val rawColors: Map<String, String>? = null
)
@Serializable
data class Theme(
    val id: String,
    val selected: Boolean,
    val data: ThemeData
)

class ThemeModule : Module() {
    private lateinit var param: XC_LoadPackage.LoadPackageParam

    private var theme: Theme? = null
    private val rawColorMap = mutableMapOf<String, Int>()

    @ExperimentalSerializationApi
    override fun buildJson(builder: JsonObjectBuilder) {
        builder.apply {
            put("hasThemeSupport", true)
            if (theme != null) 
                put("storedTheme", Json.encodeToJsonElement<Theme>(theme!!))
            else
                put("storedTheme", null)
        }
    }

    override fun onInit(packageParam: XC_LoadPackage.LoadPackageParam) {
        param = packageParam
        theme = getTheme()
        hookTheme()
    }

    private fun File.isValidish(): Boolean {
        if (!this.exists()) return false
        
        val text = this.readText()
        return text.isNotBlank() && text != "{}" && text != "null"
    }

    private fun getTheme(): Theme? {
        val filesDir = File(param.appInfo.dataDir, "files").apply { mkdirs() }
        val pyonDir = File(filesDir, "pyoncord").apply { mkdirs() }
        val themeFile = File(pyonDir, "current-theme.json")

        val legacyThemeFile = File(filesDir, "vendetta_theme.json")
        if (legacyThemeFile.isValidish() && !themeFile.isValidish()) {
            legacyThemeFile.copyTo(themeFile, overwrite = true)
        }

        if (!themeFile.isValidish()) return null
        
        return try {
            val themeText = themeFile.readText()
            Json { ignoreUnknownKeys = true }.decodeFromString<Theme>(themeText)
        } catch (e: Exception) { null }
    }

    fun hookTheme() {
        val themeManager = param.classLoader.loadClass("com.discord.theme.utils.ColorUtilsKt")
        val darkTheme = param.classLoader.loadClass("com.discord.theme.DarkTheme")
        val lightTheme = param.classLoader.loadClass("com.discord.theme.LightTheme")

        val theme = this.theme
        if (theme == null) return

        // Apply rawColors
        theme.data.rawColors?.forEach { (key, value) -> 
            rawColorMap[key.lowercase()] = hexStringToColorInt(value)
        }
        
        // Apply semanticColors
        theme.data.semanticColors?.forEach { (key, value) ->
            // TEXT_NORMAL -> getTextNormal
            val methodName = "get${key.split("_").joinToString("") { it.lowercase().replaceFirstChar { it.uppercase() } }}"
            value.forEachIndexed { index, v ->
                when (index) {
                    0 -> hookThemeMethod(darkTheme, methodName, hexStringToColorInt(v))
                    1 -> hookThemeMethod(lightTheme, methodName, hexStringToColorInt(v))
                }
            }
        }

        // If there's any rawColors value, hook the color getter
        if (!theme.data.rawColors.isNullOrEmpty()) {
            val getColorCompat = themeManager.getDeclaredMethod(
                "getColorCompat", 
                Resources::class.java, 
                Int::class.javaPrimitiveType,
                Resources.Theme::class.java, 
            )

            val getColorCompatLegacy = themeManager.getDeclaredMethod(
                "getColorCompat", 
                Context::class.java, 
                Int::class.javaPrimitiveType
            )

            val patch = object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val arg1 = param.args[0]
                    val resources = if (arg1 is Context) arg1.resources else (arg1 as Resources)  
                    val name = resources.getResourceEntryName(param.args[1] as Int)

                    if (rawColorMap[name] != null) param.result = rawColorMap[name]
                }
            }
            
            XposedBridge.hookMethod(getColorCompat, patch)
            XposedBridge.hookMethod(getColorCompatLegacy, patch)
        }
    }

    // Convert 0xRRGGBBAA to 0XAARRGGBB
    private fun hexStringToColorInt(hexString: String): Int {
        val parsed = Color.parseColor(hexString)
        return parsed.takeIf { hexString.length == 7 } ?: (parsed and 0xFFFFFF or (parsed ushr 24))
    }

    private fun hookThemeMethod(themeClass: Class<*>, methodName: String, themeValue: Int) {
        try {
            themeClass.getDeclaredMethod(methodName).let { method ->
                // Log.i("Hooking $methodName -> ${themeValue.toString(16)}")
                XposedBridge.hookMethod(method, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        param.result = themeValue
                    }
                })
            }
        } catch (ex: NoSuchMethodException) {
            // do nothing
        }
    }
}