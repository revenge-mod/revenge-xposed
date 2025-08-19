package io.github.revenge.xposed.modules

import android.content.Context
import android.content.res.Resources
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.callbacks.XC_LoadPackage
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import java.io.File
import kotlinx.serialization.json.*
import androidx.core.graphics.toColorInt
import io.github.revenge.xposed.Constants
import io.github.revenge.xposed.Module
import io.github.revenge.xposed.Utils.Companion.JSON

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

    val THEME_FILE = "current-theme.json"

    @ExperimentalSerializationApi
    override fun buildPayload(builder: JsonObjectBuilder) {
        builder.apply {
            put("hasThemeSupport", true)
            if (theme != null) 
                put("storedTheme", JSON.encodeToJsonElement<Theme>(theme!!))
            else
                put("storedTheme", null)
        }
    }

    private fun String.fromScreamingSnakeToCamelCase() = this.split("_").joinToString("") { it -> it.lowercase().replaceFirstChar { it.uppercase() } }

    override fun onLoad(packageParam: XC_LoadPackage.LoadPackageParam) {
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
        val themeFile = File(param.appInfo.dataDir, "${Constants.FILES_DIR}/${THEME_FILE}").apply { asFile() }
        if (!themeFile.isValidish()) return null
        
        return try {
            val themeText = themeFile.readText()
            JSON.decodeFromString<Theme>(themeText)
        } catch (_: Exception) { null }
    }

    fun hookTheme() {
        val themeManager = param.classLoader.loadClass("com.discord.theme.utils.ColorUtilsKt")
        val darkTheme = param.classLoader.loadClass("com.discord.theme.DarkerTheme")
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
            val methodName = "get${key.fromScreamingSnakeToCamelCase()}"
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

    // Parse HEX colour string to INT. Takes "#RRGGBBAA" or "#RRGGBB"
    private fun hexStringToColorInt(hexString: String): Int {
        return if (hexString.length == 9 ) {
            // Rearrange RRGGBBAA -> AARRGGBB so parseColor() is happy
            val alpha = hexString.substring(7, 9)
            val rrggbb = hexString.substring(1, 7)
            "#$alpha$rrggbb".toColorInt()
        } else hexString.toColorInt()
    }

    private fun hookThemeMethod(themeClass: Class<*>, methodName: String, themeValue: Int) {
        try {
            themeClass.getDeclaredMethod(methodName).let { method ->
                XposedBridge.hookMethod(method, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        param.result = themeValue
                    }
                })
            }
        } catch (_: NoSuchMethodException) {}
    }
}