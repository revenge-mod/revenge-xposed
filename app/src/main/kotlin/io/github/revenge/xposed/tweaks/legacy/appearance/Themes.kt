@file:Suppress("DEPRECATION")

package io.github.revenge.xposed.tweaks.legacy.appearance

import android.content.Context
import android.content.res.Resources
import androidx.core.graphics.toColorInt
import io.github.revenge.Logger
import io.github.revenge.xposed.*
import io.github.revenge.xposed.tweaks.legacy.RevengePayloadBuilder
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put
import java.io.File

@Serializable
data class ThemeAuthor(val name: String, val id: String? = null)

@Serializable
data class ThemeData(
    val name: String,
    val description: String? = null,
    val authors: List<ThemeAuthor>? = null,
    val spec: Int,
    val semanticColors: Map<String, List<String>>? = null,
    val rawColors: Map<String, String>? = null,
)

@Serializable
data class Theme(
    val id: String,
    val selected: Boolean,
    val data: ThemeData,
)

/**
 * Patches Discord's [com.discord.theme.DarkerTheme] / [LightTheme] getters and
 * `ColorUtilsKt.getColorCompat` overloads with values read from `files/pyoncord/current-theme.json`.
 */
@OptIn(ExperimentalSerializationApi::class)
val themes by tweak {
    val log: Logger = this.log
    val themeFile = File(
        appInfo.dataDir,
        "${RevengeConstants.FILES_DIR}/current-theme.json",
    ).apply { ensureFile() }

    val loaded: Theme? = if (themeFile.exists() && themeFile.readText().let {
            it.isNotBlank() && it != "{}" && it != "null"
        }) {
        runCatching { RevengeJson.decodeFromString<Theme>(themeFile.readText()) }
            .onFailure { log.w("current-theme.json malformed: ${it.message}") }
            .getOrNull()
    } else null

    RevengePayloadBuilder.contribute {
        put("hasThemeSupport", true)
        if (loaded != null) put("storedTheme", RevengeJson.encodeToJsonElement<Theme>(loaded))
        else put("storedTheme", null)
    }

    val theme = loaded ?: return@tweak

    val rawColorMap = mutableMapOf<String, Int>()
    theme.data.rawColors?.forEach { (k, v) -> rawColorMap[k.lowercase()] = hexStringToColorInt(v) }

    val themeManager = classLoader.loadClass("com.discord.theme.utils.ColorUtilsKt")
    val darkTheme = classLoader.loadClass("com.discord.theme.DarkerTheme")
    val lightTheme = classLoader.loadClass("com.discord.theme.LightTheme")

    theme.data.semanticColors?.forEach { (key, values) ->
        // TEXT_NORMAL -> getTextNormal
        val methodName = "get${key.fromScreamingSnakeToCamelCase()}"
        values.forEachIndexed { index, v ->
            val target = when (index) {
                0 -> darkTheme
                1 -> lightTheme
                else -> null
            } ?: return@forEachIndexed
            runCatching {
                target.getDeclaredMethod(methodName).hook {
                    before { result = hexStringToColorInt(v) }
                }
            }.onFailure { log.d("$methodName missing on ${target.name}") }
        }
    }

    if (!rawColorMap.isNullOrEmpty()) {
        val getColorCompat = themeManager.method(
            "getColorCompat",
            Resources::class.java,
            Int::class.javaPrimitiveType,
            Resources.Theme::class.java,
        )
        val getColorCompatLegacy = themeManager.method(
            "getColorCompat",
            Context::class.java,
            Int::class.javaPrimitiveType,
        )

        val patch = methodHook {
            before {
                val arg0 = args[0]
                val resources = if (arg0 is Context) arg0.resources else (arg0 as Resources)
                val name = resources.getResourceEntryName(args[1] as Int)
                rawColorMap[name]?.let { result = it }
            }
        }
        getColorCompat.hook(patch.build())
        getColorCompatLegacy.hook(patch.build())
    }
}

private fun String.fromScreamingSnakeToCamelCase(): String =
    split("_").joinToString("") { it.lowercase().replaceFirstChar { c -> c.uppercase() } }

/** Parse HEX color string to INT. Accepts `#RRGGBBAA` or `#RRGGBB`. */
private fun hexStringToColorInt(hexString: String): Int =
    if (hexString.length == 9) {
        // RRGGBBAA -> AARRGGBB so parseColor() is happy
        val alpha = hexString.substring(7, 9)
        val rrggbb = hexString.substring(1, 7)
        "#$alpha$rrggbb".toColorInt()
    } else hexString.toColorInt()
