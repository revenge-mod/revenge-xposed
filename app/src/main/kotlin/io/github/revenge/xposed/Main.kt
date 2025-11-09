package io.github.revenge.xposed

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Bundle
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.IXposedHookZygoteInit
import de.robv.android.xposed.callbacks.XC_LoadPackage
import io.github.revenge.xposed.Utils.Log
import io.github.revenge.xposed.modules.*
import io.github.revenge.xposed.modules.appearance.FontsModule
import io.github.revenge.xposed.modules.appearance.SysColorsModule
import io.github.revenge.xposed.modules.appearance.ThemesModule
import io.github.revenge.xposed.modules.bridge.AdditionalBridgeMethodsModule
import io.github.revenge.xposed.modules.bridge.BridgeModule
import io.github.revenge.xposed.modules.no_track.BlockCrashReportingModule
import io.github.revenge.xposed.modules.no_track.BlockDeepLinksTrackingModule
import kotlinx.coroutines.CompletableDeferred

object HookStateHolder {
    /**
     * Whether all hooks are completed, and we are ready to load the JS bundle.
     */
    val readyDeferred = CompletableDeferred<Unit>()

    /**
     * Whether we have successfully received a [Context] yet.
     * Sometimes the app process is recreated and Xposed hooks way too late for us to get [Context] from [ContextWrapper.attachBaseContext].
     * But since Xposed hooks before [Activity.onCreate], we can still get it from there and still initialize properly.
     */
    @Volatile
    var gotContext = false
}

class Main : Module(), IXposedHookLoadPackage, IXposedHookZygoteInit {
    private var hooked = false
    private val modules = mutableListOf(
        HookScriptLoaderModule,
        BridgeModule,
        AdditionalBridgeMethodsModule,
        PluginsModule(),
        UpdaterModule,
        FixResourcesModule,
        BlockDeepLinksTrackingModule,
        BlockCrashReportingModule,
        LogBoxModule,
        CacheModule,
        FontsModule,
        ThemesModule,
        SysColorsModule
    )

    init {
        modules += PayloadGlobalModule(modules)
    }

    override fun initZygote(startupParam: IXposedHookZygoteInit.StartupParam) {
        for (module in modules) module.onInit(startupParam)
    }

    override fun handleLoadPackage(param: XC_LoadPackage.LoadPackageParam) = with(param) {
        if (hooked) return

        val reactActivity = classLoader.loadClass(Constants.TARGET_ACTIVITY)

        ContextWrapper::class.java.hookMethod("attachBaseContext", Context::class.java) {
            after {
                val ctx = args[0] as Context
                HookStateHolder.gotContext = true
                Log.i("Received Context")
                this@Main.onContext(ctx)
            }
        }

        reactActivity.hookMethod("onCreate", Bundle::class.java) {
            after {
                val act = thisObject as Activity
                Log.i("Received Activity")

                if (!HookStateHolder.gotContext) {
                    Log.w("Activity created before we got Context, process may have been recreated!")
                    this@Main.onContext(act.applicationContext)
                }

                this@Main.onActivity(act)
                HookStateHolder.readyDeferred.complete(Unit)
            }
        }

        this@Main.onLoad(param)

        hooked = true
    }

    override fun onLoad(packageParam: XC_LoadPackage.LoadPackageParam) {
        for (module in modules) module.onLoad(packageParam)
    }

    override fun onContext(context: Context) {
        for (module in modules) module.onContext(context)
    }

    override fun onActivity(activity: Activity) {
        for (module in modules) module.onActivity(activity)
    }
}