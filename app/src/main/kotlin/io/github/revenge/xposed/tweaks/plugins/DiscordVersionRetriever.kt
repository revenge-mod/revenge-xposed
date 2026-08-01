package io.github.revenge.xposed.tweaks.plugins

import android.content.Context
import de.robv.android.xposed.XposedHelpers
import io.github.revenge.plugins.Version
import io.github.revenge.xposed.hook
import io.github.revenge.xposed.method
import io.github.revenge.xposed.tweak
import io.github.revenge.xposed.tweaks.plugins.internal.DISCORD_VERSION

var DISCORD_OTA_COMMIT: String? = null

val discordVersionRetriever by tweak {
    val buildConfigClass = classLoader.loadClass("com.discord.BuildConfig")
    val nativeVersionString = XposedHelpers.getStaticObjectField(buildConfigClass, "VERSION_NAME_RNA") as String

    log.i("Discord loaded with version: $nativeVersionString")
    DISCORD_VERSION = Version.parse(nativeVersionString)

    try {
        val bundleUpdater = classLoader.loadClass("com.discord.bundle_updater.BundleUpdater")
        val bundleUpdaterCompanion = classLoader.loadClass($$"com.discord.bundle_updater.BundleUpdater$Companion")

        withAppContext {
            bundleUpdaterCompanion.method("init", Context::class.java).hook {
                after {
                    val bundleUpdaterInstance = bundleUpdaterCompanion.method("instance").invoke(thisObject)
                    val commit = bundleUpdater.method("getExistingOtaCommit").invoke(bundleUpdaterInstance) as String?

                    if (commit != null) {
                        log.i("Running on OTA commit: $commit")
                        DISCORD_OTA_COMMIT = commit
                    } else log.i("Running on local commit")
                }
            }
        }
    } catch (e: Throwable) {
        log.e("Unable to get Discord OTA commit", e)
    }
}