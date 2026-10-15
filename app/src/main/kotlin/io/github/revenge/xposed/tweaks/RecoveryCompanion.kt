package io.github.revenge.xposed.tweaks

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.os.Build
import androidx.annotation.RequiresApi
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
        return@tweak
    }

    // Starts without an Activity (eg. notifications) never reset the crash count.
    // Reset the count if the previous process didn't actually crash.
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) withAppContext { ctx ->
        val lastExit = runCatching {
            ctx.getSystemService(ActivityManager::class.java)
                .getHistoricalProcessExitReasons(appInfo.packageName, 0, 0)
                .firstOrNull { it.processName == appInfo.packageName }
        }.onFailure { log.e(it) { "Failed to get last exit reason" } }.getOrNull() ?: return@withAppContext

        if (lastExit.reason !in CRASH_EXIT_REASONS) {
            log.i("Last exit was not a crash (reason ${lastExit.reason}), resetting")
            setCrashCountAndIncrementInFile(0)
        }
    }
}

@RequiresApi(Build.VERSION_CODES.R)
private val CRASH_EXIT_REASONS = setOf(
    ApplicationExitInfo.REASON_CRASH,
    ApplicationExitInfo.REASON_CRASH_NATIVE,
    ApplicationExitInfo.REASON_ANR,
)

private fun setCrashCountAndIncrementInFile(count: Int) {
    crashCount = count
    crashCountFile.writeText((count + 1).toString())
}

fun resetCrashCount() {
    crashCountFile.writeText("0")
    crashCount = 0
}