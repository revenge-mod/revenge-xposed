@file:Suppress("DEPRECATION")

package io.github.revenge.xposed.tweaks.legacy.appearance

import android.content.res.AssetManager
import android.graphics.Typeface
import android.graphics.Typeface.CustomFallbackBuilder
import android.graphics.fonts.Font
import android.graphics.fonts.FontFamily
import android.os.Build
import de.robv.android.xposed.XC_MethodReplacement
import de.robv.android.xposed.XposedHelpers
import io.github.revenge.Logger
import io.github.revenge.xposed.*
import io.github.revenge.xposed.tweaks.legacy.RevengePayloadBuilder
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.coroutines.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.put
import java.io.File
import java.io.IOException

@Serializable
data class FontDefinition(
    val name: String? = null,
    val description: String? = null,
    val spec: Int? = null,
    val main: Map<String, String>,
)

/**
 * Custom font loading + ReactFontManager hijack.
 */
val fonts by tweak {
    val log: Logger = this.log

    RevengePayloadBuilder.contribute { put("fontPatch", 2) }

    // ReactFontManager hijack runs regardless of fonts.json presence  it falls back to the default Typeface chain if no custom font file is found.
    XposedHelpers.findAndHookMethod(
        $$"com.facebook.react.views.text.ReactFontManager$Companion",
        classLoader,
        "createAssetTypeface",
        String::class.java,
        Int::class.java,
        "android.content.res.AssetManager",
        object : XC_MethodReplacement() {
            override fun replaceHookedMethod(param: MethodHookParam): Typeface? {
                val fontFamilyName: String = param.args[0].toString()
                val style: Int = param.args[1] as Int
                val assetManager: AssetManager = param.args[2] as AssetManager
                return FontsState.createAssetTypeface(fontFamilyName, style, assetManager)
            }
        },
    )

    withAppContext { ctx ->
        val dataDir = ctx.dataDir.absolutePath
        val fontDefFile = File(dataDir, "${RevengeConstants.FILES_DIR}/fonts.json").apply { ensureFile() }
        if (!fontDefFile.exists()) return@withAppContext

        val fontDef = try {
            RevengeJson.decodeFromString<FontDefinition>(fontDefFile.readText())
        } catch (e: Throwable) {
            log.w("fonts.json malformed: ${e.message}")
            return@withAppContext
        }
        val setName = fontDef.name ?: return@withAppContext

        val downloadsDir = File(dataDir, "${RevengeConstants.FILES_DIR}/downloads/fonts").apply { ensureDir() }
        val setDir = File(downloadsDir, setName).apply { ensureDir() }
        FontsState.fontsDownloadsDir = downloadsDir
        FontsState.fontsAbsPath = setDir.absolutePath + "/"

        // Prune stale font files for this set.
        setDir.listFiles()?.forEach { file ->
            val fileName = file.name
            if (!fileName.startsWith(".")) {
                val fontName = fileName.split('.')[0]
                if (fontDef.main.keys.none { it == fontName }) {
                    log.i("Deleting stale font file: $fileName")
                    file.delete()
                }
            }
        }

        CoroutineScope(Dispatchers.IO).launch {
            fontDef.main.entries.map { (name, url) ->
                async {
                    try {
                        log.i("Downloading $name from $url")
                        val ext = FontsState.FILE_EXTENSIONS.firstOrNull { url.endsWith(it) } ?: ".ttf"
                        val file = File(setDir, "$name$ext").apply { ensureFile() }
                        if (file.exists()) return@async
                        httpClient.use { client ->
                            val response: HttpResponse = client.get(url)
                            if (response.status == HttpStatusCode.OK) {
                                file.writeBytes(response.body())
                            }
                        }
                    } catch (e: Throwable) {
                        log.e("Failed to download font ($name from $url)", e)
                    }
                }
            }.awaitAll()
        }
    }
}

/**
 * Holds the per-process font-state used by the `createAssetTypeface` hijack. Mirrors the static
 * fields of the old `FontsModule`.
 */
private object FontsState {
    val EXTENSIONS = arrayOf("", "_bold", "_italic", "_bold_italic")
    val FILE_EXTENSIONS = arrayOf(".ttf", ".otf")
    const val FONTS_ASSET_PATH = "fonts/"

    @Volatile
    var fontsDownloadsDir: File? = null

    @Volatile
    var fontsAbsPath: String? = null

    fun createAssetTypeface(
        rawName: String,
        style: Int,
        assetManager: AssetManager,
    ): Typeface? {
        val fontFamilyNames = rawName.split(",").map { it.trim() }.filter { it.isNotEmpty() }.toTypedArray()

        var fontFamilyName = rawName
        if (fontFamilyNames.size > 1) {
            fontFamilyName = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                return createAssetTypefaceWithFallbacks(fontFamilyNames, style, assetManager)
            } else {
                fontFamilyNames[0]
            }
        }

        val extension = EXTENSIONS.getOrElse(style) { "" }

        try {
            for (fileExt in FILE_EXTENSIONS) {
                val split = fontFamilyName.split(":")
                if (split.size != 2) break
                val (customName, refName) = split
                val downloads = fontsDownloadsDir ?: break
                val file = File(downloads, "$customName/$refName.$fileExt").apply { ensureFile() }
                if (!file.exists()) continue
                return Typeface.createFromFile(file.absolutePath)
            }
        } catch (_: Throwable) {
        }

        for (fontRootPath in arrayOf(fontsAbsPath, FONTS_ASSET_PATH).filterNotNull()) {
            for (fileExt in FILE_EXTENSIONS) {
                val fileName = "$fontRootPath$fontFamilyName$extension$fileExt"
                return try {
                    if (fileName[0] == '/') Typeface.createFromFile(fileName)
                    else Typeface.createFromAsset(assetManager, fileName)
                } catch (_: RuntimeException) {
                    continue
                }
            }
        }
        return Typeface.create(fontFamilyName, style)
    }

    private fun createAssetTypefaceWithFallbacks(
        fontFamilyNames: Array<String>,
        style: Int,
        assetManager: AssetManager,
    ): Typeface? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        val fontFamilies: MutableList<FontFamily> = ArrayList()
        for (fontFamilyName in fontFamilyNames) {
            try {
                for (fileExt in FILE_EXTENSIONS) {
                    val split = fontFamilyName.split(":")
                    if (split.size != 2) break
                    val (customName, refName) = split
                    val downloads = fontsDownloadsDir ?: break
                    val file = File(downloads, "$customName/$refName.$fileExt").apply { ensureFile() }
                    if (!file.exists()) continue
                    val font = Font.Builder(file).build()
                    fontFamilies.add(FontFamily.Builder(font).build())
                }
            } catch (_: Throwable) {
            }

            for (fontRootPath in arrayOf(fontsAbsPath, FONTS_ASSET_PATH).filterNotNull()) {
                for (fileExt in FILE_EXTENSIONS) {
                    val fileName = "$fontRootPath$fontFamilyName$fileExt"
                    try {
                        val builder = if (fileName[0] == '/') Font.Builder(File(fileName))
                        else Font.Builder(assetManager, fileName)
                        val font = builder.build()
                        fontFamilies.add(FontFamily.Builder(font).build())
                    } catch (_: RuntimeException) {
                        continue
                    } catch (_: IOException) {
                        continue
                    }
                }
            }
        }

        if (fontFamilies.isEmpty()) return createAssetTypeface(fontFamilyNames[0], style, assetManager)

        val fallbackBuilder = CustomFallbackBuilder(fontFamilies[0])
        for (i in 1 until fontFamilies.size) fallbackBuilder.addCustomFallback(fontFamilies[i])
        return fallbackBuilder.build()
    }
}
