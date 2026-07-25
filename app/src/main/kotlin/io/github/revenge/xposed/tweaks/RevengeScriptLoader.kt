package io.github.revenge.xposed.tweaks

import io.github.revenge.xposed.RevengeConstants
import io.github.revenge.xposed.ensureDir
import io.github.revenge.xposed.ensureFile
import io.github.revenge.xposed.tweak
import io.github.revenge.xposed.tweaks.base.InjectorScope
import io.github.revenge.xposed.tweaks.base.registerScriptInjector
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File

/**
 * Waits for script updates and loads the Revenge bundle. Depends on [io.github.revenge.xposed.tweaks.base.scriptLoader].
 *
 * 1. Awaits the bundle download from [revengeUpdater].
 * 2. Runs every file under `files/pyoncord/preloads/`.
 * 3. Loads `cache/revenge/bundle.js` (downloaded copy) if present.
 * 4. Falls back to the in-APK `assets://revenge.bundle` asset shipped with this module.
 */
val revengeScriptLoader by tweak {
    val dataDir = appInfo.dataDir
    val filesDir = File(dataDir, RevengeConstants.FILES_DIR).apply { ensureDir() }
    val cacheDir = File(dataDir, RevengeConstants.CACHE_DIR).apply { ensureDir() }
    val preloadsDir = File(filesDir, RevengeConstants.PRELOADS_DIR).apply { ensureDir() }
    val mainScript = File(cacheDir, RevengeConstants.MAIN_SCRIPT_FILE).apply { ensureFile() }

    registerScriptInjector { scope: InjectorScope ->
        runRevengeScripts(scope, preloadsDir, mainScript)
    }
}

private fun runRevengeScripts(scope: InjectorScope, preloadsDir: File, mainScript: File) {
    val log = scope.tweakLog
    log.i("Running Revenge custom scripts...")

    runBlocking {
        try {
            withTimeout(RevengeUpdater.TIMEOUT) { RevengeUpdater.downloadReady.await() }
        } catch (e: Throwable) {
            log.w("Bundle download did not complete", e)
        }
    }

    try {
        preloadsDir.walk().filter { it.isFile }.sorted().forEach { f ->
            log.d("Running preload: ${f.absolutePath}")
            scope.runFile(f.absolutePath)
        }

        if (mainScript.exists()) {
            log.i("Loading downloaded bundle: ${mainScript.absolutePath}")
            scope.runFile(mainScript.absolutePath)
        } else {
            log.i("Downloaded bundle missing; falling back to ${RevengeConstants.FALLBACK_BUNDLE_ASSET}")
            scope.runAsset(RevengeConstants.FALLBACK_BUNDLE_ASSET)
        }
    } catch (e: Throwable) {
        log.e("Unable to run Revenge scripts", e)
    }
}

