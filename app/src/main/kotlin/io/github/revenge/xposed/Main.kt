package io.github.revenge.xposed

import android.app.Activity
import android.content.Context
import android.content.pm.ApplicationInfo
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.IXposedHookZygoteInit
import de.robv.android.xposed.callbacks.XC_LoadPackage
import io.github.revenge.bridge.RevengeBridge
import io.github.revenge.xposed.api.HostScope
import io.github.revenge.xposed.tweaks.*
import io.github.revenge.xposed.tweaks.base.lifecycleSupport
import io.github.revenge.xposed.tweaks.base.scriptLoader
import io.github.revenge.xposed.tweaks.bridge.RevengeBridgeRegistry
import io.github.revenge.xposed.tweaks.bridge.additionalBridgeMethods
import io.github.revenge.xposed.tweaks.bridge.revengeBridgeSupport
import io.github.revenge.xposed.tweaks.legacy.appearance.fonts
import io.github.revenge.xposed.tweaks.legacy.appearance.sysColors
import io.github.revenge.xposed.tweaks.legacy.appearance.themes
import io.github.revenge.xposed.tweaks.legacy.revengePayloadGlobal
import io.github.revenge.xposed.tweaks.plugins.pluginLoader
import io.github.revenge.xposed.tweaks.plugins.pluginStates

private lateinit var modulePath: String

@Suppress("UNUSED")
class Main : IXposedHookLoadPackage, IXposedHookZygoteInit {
    @Volatile
    private var hooked = false

    private val tweaks: List<TweakSpec> = listOf(
        // Framework
        lifecycleSupport,
        revengeBridgeSupport,
        scriptLoader,

        // Static patches
        fixResources,

        // Persistence
        caches,
        pluginStates,

        // Async updater
        revengeUpdater,

        // Consumers
        discordDevSupport,
        revengeRecovery,
        additionalBridgeMethods,
        fonts,
        themes,
        sysColors,
        pluginLoader,
        revengeScriptLoader,
        revengePayloadGlobal,
    )

    override fun initZygote(startupParam: IXposedHookZygoteInit.StartupParam) {
        modulePath = startupParam.modulePath
    }

    override fun handleLoadPackage(param: XC_LoadPackage.LoadPackageParam) {
        if (hooked) return
        hooked = true

        val ctx = HostScopeImpl(
            modulePath = modulePath,
            appInfo = param.appInfo,
            classLoader = param.classLoader,
        )
        for (spec in tweaks) spec.applyTo(ctx)
    }
}

private class HostScopeImpl(
    override val modulePath: String,
    override val appInfo: ApplicationInfo,
    override val classLoader: ClassLoader,
) : HostScope {
    override val bridge: RevengeBridge get() = RevengeBridgeRegistry
    override fun withAppContext(block: (Context) -> Unit) = io.github.revenge.xposed.tweaks.base.withAppContext(block)
    override fun withAppActivity(block: (Activity) -> Unit) =
        io.github.revenge.xposed.tweaks.base.withAppActivity(block)
}