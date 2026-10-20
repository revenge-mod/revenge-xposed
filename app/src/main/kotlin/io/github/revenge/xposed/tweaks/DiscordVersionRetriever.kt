package io.github.revenge.xposed.tweaks

import de.robv.android.xposed.XposedBridge.hookAllConstructors
import de.robv.android.xposed.XposedHelpers
import io.github.revenge.plugins.Version
import io.github.revenge.xposed.DexFinder
import io.github.revenge.xposed.loadClassOrNull
import io.github.revenge.xposed.methodHook
import io.github.revenge.xposed.tweak
import io.github.revenge.xposed.tweaks.plugins.internal.DISCORD_VERSION
import org.luckypray.dexkit.query.enums.StringMatchType

var DISCORD_OTA_COMMIT: String? = null

private val VERSION_NAME = Regex("""^\d+(\.\d+)+$""")

val discordVersionRetriever by tweak {
    val version = try {
        // Discord dropped BuildConfig when it enabled R8.
        val buildConfig = classLoader.loadClassOrNull("com.discord.BuildConfig")
        val nativeVersionString = buildConfig
            ?.let { XposedHelpers.getStaticObjectField(it, "VERSION_NAME_RNA") as String }
            ?: findClientInfoVersionName()

        log.i("Discord loaded with version: $nativeVersionString")
        nativeVersionString?.let(Version::parse)
    } catch (e: Throwable) {
        log.e("Unable to get the Discord version", e)
        null
    }

    // Plugins can't resolve their Discord dependency without a version, so fall back to 0.
    if (version == null) log.w("Unknown Discord version, using 0")
    DISCORD_VERSION = version ?: Version(listOf(0))

    val updater = DiscordBundleUpdater(classLoader)
    val updaterClass = updater.updaterClass
    val getOtaCommit = updater.getOtaCommit
    if (updaterClass == null || getOtaCommit == null) {
        log.e("Unable to find Discord's bundle updater, so the OTA commit is unknown")
        return@tweak
    }

    // Discord creates the updater once, during app initialization.
    hookAllConstructors(updaterClass, methodHook {
        after {
            try {
                val commit = getOtaCommit.invoke(thisObject) as String?

                if (commit != null) {
                    log.i("Running on OTA commit: $commit")
                    DISCORD_OTA_COMMIT = commit
                } else log.i("Running on local commit")
            } catch (e: Throwable) {
                log.e("Unable to get Discord OTA commit", e)
            }
        }
    }.build())
}

/**
 * Reads the version name (e.g. `352.2`) that Discord's `ClientInfo.init` assigns, which R8 inlined from `BuildConfig.VERSION_NAME_RNA`.
 * The method runs after plugins load, so this reads the constant from the DEX instead.
 *
 * The method builds a user agent like `Discord-Android/<versionCode>;RNA`, but also sets the version name.
 */
private fun findClientInfoVersionName(): String? =
    DexFinder.findString("ClientInfo.versionName") {
        findMethod {
            matcher {
                addUsingString("Discord-Android/", StringMatchType.StartsWith)
                addUsingString(";RNA", StringMatchType.EndsWith)
            }
        }.singleOrNull()?.usingStrings?.singleOrNull(VERSION_NAME::matches)
    }
