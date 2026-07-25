package io.github.revenge.xposed

import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import java.lang.reflect.Method

fun ClassLoader.loadClassOrNull(name: String): Class<*>? = try {
    loadClass(name)
} catch (_: ClassNotFoundException) {
    null
}

fun Class<*>.method(name: String, vararg params: Class<*>?): Method =
    getDeclaredMethod(name, *params).apply { isAccessible = true }

fun Method.hook(hook: XC_MethodHook): XC_MethodHook.Unhook = XposedBridge.hookMethod(this, hook)
fun Method.hook(block: MethodHookBuilder.() -> Unit) = hook(methodHook(block).build())

fun methodHook(block: MethodHookBuilder.() -> Unit) = MethodHookBuilder().apply(block)

class MethodHookBuilder internal constructor() {
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
                val scope = HookScope(param)
                scope.b()
            } else {
                super.beforeHookedMethod(param)
            }
        }

        override fun afterHookedMethod(param: MethodHookParam) {
            val a = afterBlock
            if (a != null) {
                val scope = HookScope(param)
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
 * - Accessors for `thisObject`, `args`, `result`, and `throwable`
 *
 * @property param The [XC_MethodHook.MethodHookParam] for the current hook.
 */
class HookScope internal constructor(
    val param: XC_MethodHook.MethodHookParam
) {
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