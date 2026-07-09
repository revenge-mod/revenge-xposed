package io.github.revenge.xposed.tweaks.bridge

import de.robv.android.xposed.XposedHelpers
import io.github.revenge.Logger
import io.github.revenge.bridge.RevengeBridge
import io.github.revenge.logger
import io.github.revenge.xposed.*
import kotlinx.coroutines.CompletableDeferred
import java.lang.ref.WeakReference
import java.lang.reflect.Method
import java.util.concurrent.CopyOnWriteArrayList

typealias MethodCallback = (args: List<Any?>) -> Any?

object RevengeBridgeRegistry : RevengeBridge {
    internal val log: Logger = logger("revengeBridge")

    private val methods = mutableMapOf<String, MethodCallback>()
    private val jsCallableReturnQueue = CopyOnWriteArrayList<CompletableDeferred<Any?>>()

    @Volatile
    private var reactInstance: WeakReference<Any>? = null

    @Volatile
    private var reactInstanceCallFunctionOnModule: Method? = null

    @Volatile
    private var argumentsMakeNativeObject: Method? = null

    @Volatile
    private var readableMapToHashMap: Method? = null

    internal const val JS_CALLABLE_MODULE_NAME = "RevengeBridge"
    internal const val NATIVE_CALL_DATA_KEY = "revenge"
    internal const val NATIVE_METHOD_NAME_KEY = "method"
    internal const val NATIVE_METHOD_ARGS_KEY = "args"

    override fun registerMethod(name: String, handler: MethodCallback) {
        if (methods.containsKey(name)) log.w("Bridge method already exists and will be overridden: $name")
        methods[name] = handler
    }

    override suspend fun callJSMethod(name: String, args: List<Any?>): Any? {
        val deferred = CompletableDeferred<Any?>()
        jsCallableReturnQueue += deferred

        val callFn = reactInstanceCallFunctionOnModule
            ?: error("Bridge not ready; no captured method ReactInstance.callFunctionOnModule")
        val instance = reactInstance?.get()
            ?: error("Bridge not ready; no captured ReactInstance")

        // Will throw on the CPP thread on error. Uncatchable from here.
        callFn.invoke(instance, JS_CALLABLE_MODULE_NAME, name, args.toNativeObject())
        return deferred.await()
    }

    internal fun clearMethods() = methods.clear()

    internal fun captureReactInstance(instance: Any) {
        reactInstance = WeakReference(instance)
    }

    internal fun setReflectiveMethods(
        callFunctionOnModule: Method,
        makeNativeObject: Method,
        toHashMap: Method,
    ) {
        reactInstanceCallFunctionOnModule = callFunctionOnModule
        argumentsMakeNativeObject = makeNativeObject
        readableMapToHashMap = toHashMap
    }

    /**
     * Returns the wrapped `{result | error}` payload if [rawCallData] was for Revenge bridge,
     * or `null` if it wasn't ours (should let the original method run).
     */
    @Suppress("UNCHECKED_CAST")
    internal fun tryDispatchNative(rawCallData: Any): Map<String, Any?>? {
        val hm = readableMapToHashMap?.invoke(rawCallData) as? HashMap<String, Any?> ?: return null
        val callData = hm[NATIVE_CALL_DATA_KEY] as? HashMap<String, Any?> ?: return null

        return try {
            val name = callData[NATIVE_METHOD_NAME_KEY] as? String
                ?: throw Error("Invalid native bridge call data")
            val handler = methods[name]
                ?: throw Error("Native bridge method not registered: $name")
            val args = callData[NATIVE_METHOD_ARGS_KEY] as? List<Any?>
                ?: throw Error("Invalid native bridge args (expected List)")

            val result = handler(args).toNativeObject()
            mapOf("result" to result)
        } catch (e: Throwable) {
            mapOf("error" to e.stackTraceToString())
        }
    }

    /**
     * Completes the next pending [CompletableDeferred] in the JS-callable return queue.
     * Used by the built-in `revenge.__callableReturn` bridge method registered in [revengeBridgeSupport].
     */
    internal fun completeNextJsCallable(result: Any?, error: Any?) {
        if (jsCallableReturnQueue.isEmpty()) return
        val deferred = jsCallableReturnQueue.removeAt(0)
        if (error != null) deferred.completeExceptionally(Error("JS returned error: $error"))
        else deferred.complete(result)
    }

    private fun Any?.toNativeObject(): Any? = argumentsMakeNativeObject!!.invoke(
        null,
        if (this == Unit) null else this,
    )
}


val revengeBridgeSupport by tweak {
    RevengeBridgeRegistry.clearMethods()

    val arguments = classLoader.loadClass("com.facebook.react.bridge.Arguments")
    val readableMap = classLoader.loadClass("com.facebook.react.bridge.ReadableMap")
    val promise = classLoader.loadClass("com.facebook.react.bridge.Promise")

    val promiseResolve = promise.method("resolve", Any::class.java)
    val makeNativeObject = arguments.method("makeNativeObject", Any::class.java)
    val toHashMap = readableMap.method("toHashMap")
    val reactInstanceClass = classLoader.loadClass("com.facebook.react.runtime.ReactInstance")
    val nativeArrayClass = classLoader.loadClass("com.facebook.react.bridge.NativeArray")
    val callFunctionOnModule = reactInstanceClass.method(
        "callFunctionOnModule",
        String::class.java,
        String::class.java,
        nativeArrayClass,
    )

    RevengeBridgeRegistry.setReflectiveMethods(
        callFunctionOnModule = callFunctionOnModule,
        makeNativeObject = makeNativeObject,
        toHashMap = toHashMap,
    )

    // JS -> native (sync)
    runCatching {
        classLoader.loadClass("com.horcrux.svg.RNSVGRenderableManager")
            .method("getBBox", Double::class.javaObjectType, readableMap)
            .hook {
                before {
                    val callData = args[1] ?: return@before
                    val response = RevengeBridgeRegistry.tryDispatchNative(callData) ?: return@before
                    result = makeNativeObject.invoke(null, response as Any)
                }
            }
    }.onFailure { log.w("RNSVGRenderableManager not available; getBBox bridge path disabled.") }

    // JS -> native (async)
    classLoader.loadClass("com.facebook.react.modules.blob.FileReaderModule")
        .method("readAsDataURL", readableMap, promise)
        .hook {
            before {
                val callData = args[0] ?: return@before
                val response = RevengeBridgeRegistry.tryDispatchNative(callData) ?: return@before
                promiseResolve.invoke(args[1]!!, makeNativeObject.invoke(null, response as Any))
                result = null
            }
        }

    // Native -> JS
    XposedHelpers.findAndHookConstructor(
        reactInstanceClass,
        "com.facebook.react.runtime.BridgelessReactContext",
        "com.facebook.react.runtime.ReactHostDelegate",
        "com.facebook.react.fabric.ComponentFactory",
        "com.facebook.react.devsupport.interfaces.DevSupportManager",
        "com.facebook.react.bridge.queue.QueueThreadExceptionHandler",
        Boolean::class.javaPrimitiveType,
        "com.facebook.react.runtime.ReactHostInspectorTarget",
        methodHook {
            after {
                log.i("Captured ReactInstance for RevengeBridge")
                RevengeBridgeRegistry.captureReactInstance(thisObject!!)
            }
        }.build(),
    )

    // Native -> JS return (async, `revenge.__callableReturn`)
    RevengeBridgeRegistry.registerMethod("revenge.__callableReturn") { args ->
        @Suppress("UNCHECKED_CAST")
        val data = args.firstOrNull() as? Map<String, Any?> ?: emptyMap()
        RevengeBridgeRegistry.completeNextJsCallable(
            result = data["result"],
            error = data["error"],
        )
        null
    }

    RevengeBridgeRegistry.registerMethod("revenge.info") {
        mapOf(
            "name" to RevengeConstants.LOADER_NAME,
            "version" to RevengeConstants.LOADER_VERSION,
        )
    }
}
