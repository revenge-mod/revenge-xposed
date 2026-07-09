package io.github.revenge.xposed

object RevengeConstants {
    const val TARGET_PACKAGE = "com.discord"
    const val TARGET_ACTIVITY = "$TARGET_PACKAGE.react_activities.ReactActivity"

    // @TODO: Migration to revenge named dir
    const val FILES_DIR = "files/pyoncord"
    const val CACHE_DIR = "cache/revenge"
    const val MAIN_SCRIPT_FILE = "bundle.js"
    const val PRELOADS_DIR = "preloads"

    const val LOADER_NAME = "RevengeXposed"
    val LOADER_VERSION
        get() = BuildConfig.VERSION_NAME
    val USER_AGENT
        get() = "RevengeXposed/$LOADER_VERSION"

    /**
     * Fallback Hermes bundle shipped inside this APK's `assets/` directory.
     * Loaded by [io.github.revenge.xposed.tweaks.revengeScriptLoader] when the cached `bundle.js` isn't available.
     */
    const val FALLBACK_BUNDLE_ASSET = "assets://revenge.bundle"
}

/**
 * Hermes bytecode assets shipped inside the Xposed module APK.
 */
val scriptAssets = emptyList<String>()
