package io.github.revenge.xposed.modules

import android.content.res.AssetManager
import android.os.Build
import android.graphics.Typeface
import android.graphics.Typeface.CustomFallbackBuilder
import android.graphics.fonts.Font
import android.graphics.fonts.FontFamily
import de.robv.android.xposed.XC_MethodReplacement
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import io.github.revenge.xposed.Constants
import io.github.revenge.xposed.Module
import io.github.revenge.xposed.Utils.Log
import io.github.revenge.xposed.Utils.Companion.JSON
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import java.io.IOException
import java.io.File
import kotlinx.coroutines.*
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.client.engine.cio.*
import io.ktor.client.statement.*
import io.ktor.client.plugins.*
import io.ktor.http.*
import java.lang.StringBuilder

@Serializable
data class FontDefinition(
    val name: String? = null,
    val description: String? = null,
    val spec: Int? = null,
    val main: Map<String, String>,
)

class FontsModule: Module() {
    private val EXTENSIONS = arrayOf("", "_bold", "_italic", "_bold_italic")
    private val FILE_EXTENSIONS = arrayOf(".ttf", ".otf")
    private val FONTS_ASSET_PATH = "fonts/"

    private lateinit var fontsDir: File
    private lateinit var fontsDownloadsDir: File
    private var fontsAbsPath: String? = null

    override fun buildPayload(builder: JsonObjectBuilder) {
        builder.apply {
            put("fontPatch", 2)
        }
    }

    override fun onLoad(packageParam: XC_LoadPackage.LoadPackageParam) = with (packageParam) {
        XposedHelpers.findAndHookMethod(
            "com.facebook.react.views.text.ReactFontManager\$Companion",
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
                    return createAssetTypeface(fontFamilyName, style, assetManager)
                }
        })

        val fontDefFile = File(appInfo.dataDir, "${Constants.FILES_DIR}/fonts.json").apply { asFile() }
        if (!fontDefFile.exists()) return@with

        val fontDef = try {
            JSON.decodeFromString<FontDefinition>(fontDefFile.readText())
        } catch (_: Throwable) { return@with }

        fontsDownloadsDir = File(appInfo.dataDir, "${Constants.FILES_DIR}/downloads/fonts").apply { asDir() }
        fontsDir = File(fontsDownloadsDir, fontDef.name!!).apply { asDir() }
        fontsAbsPath = fontsDir.absolutePath + "/"

        fontsDir.listFiles()?.forEach { file ->
            val fileName = file.name
            if (!fileName.startsWith(".")) {
                val fontName = fileName.split('.')[0]
                if (fontDef.main.keys.none { it == fontName }) {
                    Log.i("Deleting font file: $fileName")
                    file.delete()
                }
            }
        }

        // These files should be downloaded by the JS side, but oh well
        CoroutineScope(Dispatchers.IO).launch {
            fontDef.main.keys.map { name ->
                async {
                    val url = fontDef.main.getValue(name)
                    try {
                        Log.i("Downloading $name from $url")
                        val file = File(fontsDir, "$name${FILE_EXTENSIONS.first { url.endsWith(it) }}").apply { asFile() }
                        if (file.exists()) return@async

                        val client = HttpClient(CIO) {
                            install(UserAgent) { agent = Constants.USER_AGENT }
                        }

                        val response: HttpResponse = client.get(url)

                        if (response.status == HttpStatusCode.OK)
                            file.writeBytes(response.body())

                        return@async
                    } catch (e: Throwable) {
                        Log.e("Failed to download fonts ($name from $url)", e)
                    }
                }
            }.awaitAll()
        } 

        return@with
    }

    private fun createAssetTypefaceWithFallbacks(
        fontFamilyNames: Array<String>,
        style: Int,
        assetManager: AssetManager
    ): Typeface? {
        val fontFamilies: MutableList<FontFamily> = ArrayList()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Iterate over the list of fontFamilyNames, constructing new FontFamily objects
            // for use in the CustomFallbackBuilder below.
            for (fontFamilyName in fontFamilyNames) {
                try {
                    for (fileExtension in FILE_EXTENSIONS) {
                        val (customName, refName) = fontFamilyName.split(":")
                        val file = File(fontsDownloadsDir, "$customName/$refName.$fileExtension").apply { asFile() }
                        val font = Font.Builder(file).build()
                        val family = FontFamily.Builder(font).build()
                        fontFamilies.add(family)
                    }
                } catch (_: Throwable) {}

                for (fontRootPath in arrayOf(fontsAbsPath, FONTS_ASSET_PATH).filterNotNull()) {
                    for (fileExtension in FILE_EXTENSIONS) {
                        val fileName = StringBuilder()
                            .append(fontRootPath)
                            .append(fontFamilyName)
                            .append(fileExtension)
                            .toString()
                        try {
                            val builder = if (fileName[0] == '/') Font.Builder(File(fileName)) else Font.Builder(assetManager, fileName)
                            val font = builder.build()
                            val family = FontFamily.Builder(font).build()
                            fontFamilies.add(family)
                        } catch (_: RuntimeException) {
                            // If the typeface asset does not exist, try another extension.
                            continue
                        } catch (_: IOException) {
                            // If the font asset does not exist, try another extension.
                            continue
                        }
                    }
                }
            }

            // If there's some problem constructing fonts, fall back to the default behavior.
            if (fontFamilies.isEmpty()) return createAssetTypeface(fontFamilyNames[0], style, assetManager)

            val fallbackBuilder = CustomFallbackBuilder(fontFamilies[0])
            for (i in 1 until fontFamilies.size) {
                fallbackBuilder.addCustomFallback(fontFamilies[i])
            }
            return fallbackBuilder.build()
        }
        return null
    }

    private fun createAssetTypeface(
        fontFamilyName: String, style: Int, assetManager: AssetManager
    ): Typeface? {
        // This logic attempts to safely check if the frontend code is attempting to use
        // fallback fonts, and if it is, to use the fallback typeface creation logic.
        var fontFamilyName: String = fontFamilyName
        val fontFamilyNames =
            fontFamilyName.split(",".toRegex())
                .dropLastWhile { it.isEmpty() }
                .toTypedArray()
        for (i in fontFamilyNames.indices) {
            fontFamilyNames[i] = fontFamilyNames[i].trim()
        }

        // If there are multiple font family names:
        //   For newer versions of Android, construct a Typeface with fallbacks
        //   For older versions of Android, ignore all the fallbacks and just use the first font family
        if (fontFamilyNames.size > 1) {
            fontFamilyName = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                return createAssetTypefaceWithFallbacks(fontFamilyNames, style, assetManager)
            } else {
                fontFamilyNames[0]
            }
        }

        val extension = EXTENSIONS[style]

        try {
            for (fileExtension in FILE_EXTENSIONS) {
                val (customName, refName) = fontFamilyName.split(":")
                val file = File(fontsDownloadsDir, "$customName/$refName.$fileExtension").apply { asFile() }
                if (!file.exists()) throw Exception()
                return Typeface.createFromFile(file.absolutePath)
            }
        } catch (_: Throwable) {}

        // Lastly, after all those checks above, this is the original RN logic for
        // getting the typeface.
        for (fontRootPath in arrayOf(fontsAbsPath, FONTS_ASSET_PATH).filterNotNull()) {
            for (fileExtension in FILE_EXTENSIONS) {
                val fileName = StringBuilder()
                    .append(fontRootPath)
                    .append(fontFamilyName)
                    .append(extension)
                    .append(fileExtension)
                    .toString()
                
                return try {
                    if (fileName[0] == '/')
                        Typeface.createFromFile(fileName)
                    else
                        Typeface.createFromAsset(assetManager, fileName)
                } catch (_: RuntimeException) {
                    // If the typeface asset does not exist, try another extension.
                    continue
                }
            }
        }

        return Typeface.create(fontFamilyName, style)
    }
}