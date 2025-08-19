package io.github.revenge.xposed

import android.app.Activity
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.lang.reflect.Method
import kotlinx.serialization.json.JsonObjectBuilder
import java.io.File

abstract class Module {
    open fun buildPayload(builder: JsonObjectBuilder) {}

    open fun onLoad(packageParam: XC_LoadPackage.LoadPackageParam) {}

    open fun onCreate(activity: Activity) {}

    protected fun File.asDir() {
        if (!this.isDirectory()) this.delete()
        this.mkdirs()
    }

    protected fun File.asFile() {
        if (!this.isFile()) this.deleteRecursively()
    }

    protected fun Class<*>.method(
        name: String,
        vararg params: Class<*>?
    ): Method = getDeclaredMethod(name, *params).apply {
        isAccessible = true
    }

    protected fun Method.hook(hook: XC_MethodHook): XC_MethodHook.Unhook =
        XposedBridge.hookMethod(this, hook)

    protected fun Class<*>.hookMethod(
        name: String,
        vararg params: Class<*>?,
        block: MethodHookBuilder.() -> Unit
    ): XC_MethodHook.Unhook =
        method(name, *params).hook(MethodHookBuilder().apply(block).build())

    protected class MethodHookBuilder {
        private var beforeBlock: (HookScope.() -> Unit)? = null
        private var afterBlock: (HookScope.() -> Unit)? = null

        fun before(block: HookScope.() -> Unit) {
            beforeBlock = block
        }

        fun after(block: HookScope.() -> Unit) {
            afterBlock = block
        }

        fun build(): XC_MethodHook =
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val b = beforeBlock
                    if (b != null) {
                        val scope =
                            HookScope(
                                param = param,
                                callSuper = { p ->
                                    super.beforeHookedMethod(p)
                                }
                            )
                        scope.b()
                    } else {
                        super.beforeHookedMethod(param)
                    }
                }

                override fun afterHookedMethod(param: MethodHookParam) {
                    val a = afterBlock
                    if (a != null) {
                        val scope =
                            HookScope(
                                param = param,
                                callSuper = { p ->
                                    super.afterHookedMethod(p)
                                }
                            )
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
     * @property callSuper Function that calls the super method.
     */
    protected class HookScope internal constructor(
        val param: XC_MethodHook.MethodHookParam,
        private val callSuper: (XC_MethodHook.MethodHookParam) -> Unit
    ) {
        /**
         * Continues with the default XC_MethodHook super behavior.
         * Equivalent to calling `super.beforeHookedMethod(param)` or
         * `super.afterHookedMethod(param)` depending on the phase.
         */
        fun proceed() = callSuper(param)

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