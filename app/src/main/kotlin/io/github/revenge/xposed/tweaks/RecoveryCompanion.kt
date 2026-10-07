package io.github.revenge.xposed.tweaks

import io.github.revenge.xposed.ensureFile
import io.github.revenge.xposed.tweak
import io.github.revenge.xposed.tweaks.plugins.PluginStatesStore
import java.io.File
import kotlin.properties.Delegates

lateinit var crashCountFile: File
var crashCount by Delegates.notNull<Int>()
var crashRecoveryTriggered = false

/**
 * Companion tweak for [io.github.revenge.xposed.tweaks.plugins.internal.recoveryPlugin].
 * Starts before any plugins to track crashes before plugins run, entering defaults-only boot once 3 crashes are detected.
 */
val recoveryCompanion by tweak {
    crashCountFile = File(appInfo.dataDir, "files/revenge/recovery_crash_count").apply {
        mkdirs()
        ensureFile()
    }

    if (!crashCountFile.exists() || crashCountFile.readText().toIntOrNull()
            ?.also(::setCrashCountAndIncrementInFile) == null
    ) {
        resetCrashCount()
        return@tweak
    }

    log.i("Crash count: $crashCount")

    if (crashCount >= 3) {
        crashRecoveryTriggered = true
        // Don't need to reload app since this applies before pluginStates ever gets loaded.
        PluginStatesStore.setActiveSlot(appInfo.dataDir, PluginStatesStore.DEFAULTS_SLOT, true)
        resetCrashCount()
    }
}

private fun setCrashCountAndIncrementInFile(count: Int) {
    crashCount = count
    crashCountFile.writeText((count + 1).toString())
}

fun resetCrashCount() {
    crashCountFile.writeText("0")
    crashCount = 0
}