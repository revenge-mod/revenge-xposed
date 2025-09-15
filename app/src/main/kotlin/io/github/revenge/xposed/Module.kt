package io.github.revenge.xposed

import android.app.Activity
import android.content.Context
import android.os.Build
import de.robv.android.xposed.IXposedHookZygoteInit
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.callbacks.XC_LoadPackage
import kotlinx.serialization.json.JsonObjectBuilder
import java.io.File
import java.lang.reflect.Method

data class AppInfo(
    val name: String,
    val packageName: String,
    val version: String,
    val versionCode: Long,
)

abstract class Module {
    /**
     * Builds a JSON payload to be injected into the JavaScript context.
     */
    @Deprecated("This will be removed in future versions. Payloads can be replaced via synchronous bridge methods.")
    open fun buildPayload(builder: JsonObjectBuilder) {
    }

    /**
     * Called during Zygote initialization.
     */
    open fun onInit(startupParam: IXposedHookZygoteInit.StartupParam) {}

    /**
     * Called when a package is loaded.
     *
     * Hooks should typically be set up here.
     *
     * Bridge methods that do not require [Context] can also be registered here.
     */
    open fun onLoad(packageParam: XC_LoadPackage.LoadPackageParam) {}

    /**
     * Called after [Context] has been attached to a [android.content.ContextWrapper]
     * via [android.content.ContextWrapper.attachBaseContext].
     *
     * This may be called multiple times for different [Context]s, e.g. when the app is killed by the system.
     *
     * For bridge methods that require access to [Context], register them here.
     */
    open fun onContext(context: Context) {}

    /**
     * Called after an [Activity.onCreate] method is executed.
     *
     * UI code that requires an [Activity] context can be placed here.
     *
     * Bridge methods must not be registered here, as it can be too late and cause crashes.
     *
     * If you need to register bridge methods that require an [Activity], do it in [onContext] and make it async.
     *
     * Once an [Activity] is available, execute the queued calls. Then from JS, call the method asynchronously.
     */
    open fun onActivity(activity: Activity) {}

    protected fun Context.getAppInfo(): AppInfo {
        val pInfo = packageManager.getPackageInfo(packageName, 0)
        val versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) pInfo.longVersionCode
        else @Suppress("DEPRECATION") pInfo.versionCode.toLong()

        return AppInfo(
            packageManager.getApplicationLabel(applicationInfo).toString(),
            packageName,
            pInfo.versionName ?: versionCode.toString(),
            versionCode,
        )
    }

    protected fun File.asDir() {
        if (!this.isDirectory) this.delete()
        this.mkdirs()
    }

    protected fun File.asFile() {
        if (!this.isFile) this.deleteRecursively()
    }

    protected fun Class<*>.method(
        name: String, vararg params: Class<*>?
    ): Method = getDeclaredMethod(name, *params).apply {
        isAccessible = true
    }

    protected fun ClassLoader.safeLoadClass(name: String): Class<*>? = runCatching { loadClass(name) }.getOrNull()

    protected fun Method.hook(hook: XC_MethodHook): XC_MethodHook.Unhook = XposedBridge.hookMethod(this, hook)

    protected fun Method.hook(block: MethodHookBuilder.() -> Unit): XC_MethodHook.Unhook =
        hook(MethodHookBuilder().apply(block).build())

    protected fun Class<*>.hookMethod(
        name: String, vararg params: Class<*>?, block: MethodHookBuilder.() -> Unit
    ): XC_MethodHook.Unhook = method(name, *params).hook(MethodHookBuilder().apply(block).build())

    protected class MethodHookBuilder {
        private var beforeBlock: (HookScope.() -> Unit)? = null
        private var afterBlock: (HookScope.() -> Unit)? = null

        fun before(block: HookScope.() -> Unit) {
            beforeBlock = block
        }

        fun after(block: HookScope.() -> Unit) {
            afterBlock = block
        }

        fun build(): XC_MethodHook = object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val b = beforeBlock
                if (b != null) {
                    val scope = HookScope(
                        param = param, proceed = { p ->
                            super.beforeHookedMethod(p)
                        })
                    scope.b()
                } else {
                    super.beforeHookedMethod(param)
                }
            }

            override fun afterHookedMethod(param: MethodHookParam) {
                val a = afterBlock
                if (a != null) {
                    val scope = HookScope(
                        param = param, proceed = { p ->
                            super.afterHookedMethod(p)
                        })
                    scope.a()
                } else {
                    super.afterHookedMethod(param)
                }
            }
        }
    }

    /**
     * Scope object passed to before/after hook blocks.
     *
     * Provides:
     * - Access to the [param] object
     * - [proceed] to call the original XC_MethodHook super method
     * - Accessors for `thisObject`, `args`, `result`, and `throwable`
     *
     * @property param The [XC_MethodHook.MethodHookParam] for the current hook.
     * @property proceed Function that calls the super method.
     */
    protected class HookScope internal constructor(
        val param: XC_MethodHook.MethodHookParam, private val proceed: (XC_MethodHook.MethodHookParam) -> Unit
    ) {
        /**
         * Continues with the default XC_MethodHook super behavior.
         * Equivalent to calling `super.beforeHookedMethod(param)` or
         * `super.afterHookedMethod(param)` depending on the phase.
         */
        fun proceed() = proceed(param)

        val thisObject: Any? get() = param.thisObject

        val args: Array<Any?> get() = param.args

        var result: Any?
            get() = param.result
            set(value) {
                param.result = value
            }

        var throwable: Throwable?
            get() = param.throwable
            set(value) {
                param.throwable = value
            }
    }
}