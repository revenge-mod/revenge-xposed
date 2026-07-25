package io.github.revenge.xposed.tweaks.plugins

import de.robv.android.xposed.XposedHelpers
import io.github.revenge.plugins.Version
import io.github.revenge.xposed.tweak
import io.github.revenge.xposed.tweaks.plugins.internal.DISCORD_VERSION

val discordVersionRetriever by tweak {
    val buildConfigClass = classLoader.loadClass("com.discord.BuildConfig")
    val nativeVersionString = XposedHelpers.getStaticObjectField(buildConfigClass, "VERSION_NAME_RNA") as String

    log.i("Discord loaded with version: $nativeVersionString")
    DISCORD_VERSION = Version.parse(nativeVersionString)
}