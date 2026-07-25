@file:Suppress("DEPRECATION")

package io.github.revenge.xposed.tweaks.legacy.appearance

import android.R.color
import android.app.AndroidAppHelper
import android.os.Build
import androidx.core.content.ContextCompat
import io.github.revenge.xposed.tweak
import io.github.revenge.xposed.tweaks.legacy.RevengePayloadBuilder
import kotlinx.serialization.json.add
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Material-You system colors.
 */
val sysColors by tweak {
    val supported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

    RevengePayloadBuilder.contribute {
        put("isSysColorsSupported", supported)
        if (supported) {
            val ctx = AndroidAppHelper.currentApplication()
            val accents = arrayOf("accent1", "accent2", "accent3", "neutral1", "neutral2")
            val shades = arrayOf(0, 10, 50, 100, 200, 300, 400, 500, 600, 700, 800, 900, 1000)
            putJsonObject("sysColors") {
                for (accent in accents) putJsonArray(accent) {
                    for (shade in shades) {
                        val name = "system_${accent}_$shade"
                        val resId = runCatching {
                            color::class.java.getField(name).getInt(null)
                        }.getOrElse { 0 }
                        val rgb = if (resId != 0) ContextCompat.getColor(ctx, resId) else 0
                        add(String.format("#%06X", 0xFFFFFF and rgb))
                    }
                }
            }
        }
    }
}
