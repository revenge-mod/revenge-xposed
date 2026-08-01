package io.github.revenge.xposed.tweaks

import io.github.revenge.reloadApp
import io.github.revenge.xposed.*
import io.github.revenge.xposed.tweaks.plugins.internal.showRecoveryAlert

/**
 * Enables LogBox and makes the shake gesture alert show our recovery options.
 */
val discordDevSupport by tweak {
    /**
     * Enabling DevSupport also exposes the user to potentially harmful entrypoints,
     * such as being able to run arbitrary scripts by exposing a "Metro server".
     *
     * It's not worth it just for LogBox and shake-to-recovery gesture.
     */
    if (!BuildConfig.DEBUG) {
        log.i("Skipped DevSupport patches as not running a debug build")
        return@tweak
    }

    classLoader.loadClassOrNull("com.discord.bridge.DCDReactNativeHost")?.let { cls ->
        runCatching {
            val getUseDeveloperSupport = cls.declaredMethods.firstOrNull {
                it.name == "getUseDeveloperSupport"
            } ?: return@runCatching

            getUseDeveloperSupport.isAccessible = true
            getUseDeveloperSupport.hook {
                before { result = true }
            }
            log.i("Enabled Discord developer support")
        }
    }

    // Wire `showDevOptionsDialog` (shake gesture) to the Revenge recovery dialog instead of RN's default dev menu
    withAppContext { ctx ->
        listOf(
            "com.facebook.react.devsupport.BridgeDevSupportManager",
            "com.facebook.react.devsupport.BridgelessDevSupportManager",
        ).mapNotNull { classLoader.loadClassOrNull(it) }.forEach { cls ->
            runCatching {
                val show = cls.declaredMethods.firstOrNull { it.name == "showDevOptionsDialog" }
                    ?: return@runCatching

                show.isAccessible = true
                show.hook {
                    before {
                        try {
                            showRecoveryAlert(ctx)
                        } catch (e: Throwable) {
                            log.e("Failed to show recovery dialog from shake gesture", e)
                        }
                        result = null
                    }
                }
            }

            // Reload app instead of reloading from Metro server
            runCatching {
                cls.method("handleReloadJS").hook {
                    before {
                        reloadApp()
                        result = null
                    }
                }
            }
        }
    }
}
