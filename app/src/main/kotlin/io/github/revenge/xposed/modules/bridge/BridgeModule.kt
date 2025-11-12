package io.github.revenge.xposed.modules.bridge

import InternalApi
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam
import io.github.revenge.bridge.impl.BridgeHost
import io.github.revenge.plugins.MethodArgs
import io.github.revenge.plugins.MethodCallback
import io.github.revenge.plugins.NativeObject
import io.github.revenge.xposed.BuildConfig
import io.github.revenge.xposed.Constants
import io.github.revenge.xposed.Module
import io.github.revenge.xposed.Utils.Log
import io.github.revenge.xposed.modules.bridge.BridgeModule.JS_CALLABLE_MODULE_NAME
import io.github.revenge.xposed.modules.bridge.BridgeModule.callJSMethod
import kotlinx.coroutines.*
import java.lang.ref.WeakReference
import java.lang.reflect.Method
import java.util.concurrent.CopyOnWriteArrayList

// Solely for helping with readability
typealias ReadableMap = Any

/**
 * A module that exposes a bridge for calling methods from JavaScript, and a bridge for calling JavaScript modules from Kotlin.
 *
 * # JS -> Native
 *
 * To call a method, pass an object with the following structure to a hooked method:
 * ```js
 * {
 *   revenge: {
 *     method: "method.name",
 *     args: [arg1, arg2, ...]
 *   }
 * }
 * ```
 *
 * For methods that accept additional arguments, only the first object argument is used for the bridge call.
 *
 * The result will be an object with either a `result` or `error` key:
 * ```js
 * {
 *   result: ... // The return value of the method
 * }
 * ```
 * or
 * ```js
 * {
 *   error: "Error message"
 * }
 * ```
 *
 * # Native -> JS
 *
 * Register a callable module from JS with the name defined in [JS_CALLABLE_MODULE_NAME].
 * The callable module methods will be called with the method name and arguments given in [callJSMethod].
 *
 * The return value will be delivered via a [CompletableDeferred] that resolves when JS calls back with the result.
 *
 * Once the JS side processes the call, it must call the `revenge.callableReturn` native method back **synchronously** with the following structure:
 * ```js
 * {
 *   result: ... // The return value of the JS method
 * }
 * ```
 * or
 * ```js
 * {
 *   error: "Error message"
 * }
 * ```
 */
object BridgeModule : Module() {
    private lateinit var reactInstance: WeakReference<Any>

    // Actually returns nothing, so we can use another JS -> native call to return the result
    private lateinit var reactInstanceCallFunctionOnModule: Method
    private val jsCallableReturnQueue = CopyOnWriteArrayList<CompletableDeferred<NativeObject>>()
    private const val JS_CALLABLE_MODULE_NAME = "RevengeBridge"

    private lateinit var readableMapGetString: Method
    private lateinit var readableMapToHashMap: Method
    private lateinit var argumentsMakeNativeObject: Method

    private const val NATIVE_CALL_DATA_KEY = "revenge"
    private const val NATIVE_METHOD_NAME_KEY = "method"
    private const val NATIVE_METHOD_ARGS_KEY = "args"

    private val methods: MutableMap<String, MethodCallback> = mutableMapOf()

    /**
     * Registers a bridge method that can be called from JavaScript.
     *
     * If the method is already registered, it will be overwritten.
     *
     * @param name The name of the method to register.
     * @param callback The callback to invoke when the method is called.
     */
    fun registerMethod(name: String, callback: MethodCallback) {
        if (methods.containsKey(name)) Log.w("Bridge method already exists and will be overridden: $name")
        methods[name] = callback
    }

    /**
     * Calls a JavaScript method registered in the callable module named [JS_CALLABLE_MODULE_NAME].
     *
     * @param method The name of the JavaScript method to call.
     * @param args The arguments to pass to the JavaScript method.
     * @return A [CompletableDeferred] that resolves with the result of the JavaScript method call.
     */
    fun callJSMethod(method: String, args: MethodArgs): CompletableDeferred<NativeObject> {
        val deferred = CompletableDeferred<NativeObject>()
        jsCallableReturnQueue += deferred

        // This will throw native side, if there are any errors
        // Which means, we can't really catch it :/
        reactInstanceCallFunctionOnModule.invoke(
            reactInstance.get()!!,
            JS_CALLABLE_MODULE_NAME,
            method,
            args.toNativeObject(),
        )

        return deferred
    }

    override fun onLoad(packageParam: LoadPackageParam) = with(packageParam) {
        initHost()
        initBridgeMethods()
        initCallableModules()

        return@with
    }

    @OptIn(InternalApi::class)
    private fun initHost() {
        BridgeHost.callJSMethod = { methodName, args ->
            callJSMethod(methodName, args)
        }
    }

    private fun LoadPackageParam.initBridgeMethods() {
        val arguments = classLoader.loadClass("com.facebook.react.bridge.Arguments")
        val readableMap = classLoader.loadClass("com.facebook.react.bridge.ReadableMap")
        val promise = classLoader.loadClass("com.facebook.react.bridge.Promise")

        val promiseResolve = promise.method("resolve", Object::class.java)
        argumentsMakeNativeObject = arguments.method("makeNativeObject", Object::class.java)
        readableMapGetString = readableMap.method("getString", String::class.java)
        readableMapToHashMap = readableMap.method("toHashMap")

        classLoader.loadClass("com.horcrux.svg.RNSVGRenderableManager")
            .hookMethod("getBBox", Double::class.javaObjectType, readableMap) {
                before {
                    callNativeMethod(args[1]!!).let { result = it.toNativeObject() }
                }
            }

        classLoader.loadClass("com.facebook.react.modules.blob.FileReaderModule")
            .hookMethod("readAsDataURL", readableMap, promise) {
                before {
                    val (callData, promise) = args
                    callNativeMethod(callData!!).let {
                        promiseResolve.invoke(promise!!, it.toNativeObject())
                        result = null
                    }
                }
            }

        // Clear methods every package load. Plugins should re-register on each load.
        methods.clear()
        registerDefaultMethods()
    }

    private fun LoadPackageParam.initCallableModules() {
        val reactInstanceClass = classLoader.loadClass("com.facebook.react.runtime.ReactInstance")
        val nativeArrayClass = classLoader.loadClass("com.facebook.react.bridge.NativeArray")

        reactInstanceCallFunctionOnModule = reactInstanceClass.method(
            "callFunctionOnModule",
            String::class.java,
            String::class.java,
            nativeArrayClass,
        )

        val hook = MethodHookBuilder().run {
            after {
                Log.i("Got ReactInstance")
                reactInstance = WeakReference(thisObject!!)
            }

            build()
        }

        XposedHelpers.findAndHookConstructor(
            reactInstanceClass,
            "com.facebook.react.runtime.BridgelessReactContext",
            "com.facebook.react.runtime.ReactHostDelegate",
            "com.facebook.react.fabric.ComponentFactory",
            "com.facebook.react.devsupport.interfaces.DevSupportManager",
            "com.facebook.react.bridge.queue.QueueThreadExceptionHandler",
            Boolean::class.javaPrimitiveType,
            "com.facebook.react.runtime.ReactHostInspectorTarget",
            hook,
        )

        methods["revenge.__callableReturn"] = {
            val args = it.asDelegate()
            val data by args.hashMap()

            val deferred = jsCallableReturnQueue.removeFirstOrNull()
                ?: throw Error("No pending JS callable returns")

            val result = data["result"]
            val error = data["error"]

            if (error != null) {
                deferred.completeExceptionally(Error("JS returned error: $error"))
            } else {
                deferred.complete(result)
            }
        }
    }

    private fun registerDefaultMethods() {
        methods["revenge.info"] = {
            mapOf(
                "name" to Constants.LOADER_NAME,
                "version" to BuildConfig.VERSION_CODE,
            )
        }

        if (BuildConfig.DEBUG) {
            methods["revenge.test"] = {
                mapOf(
                    "string" to "string",
                    "number" to 7256,
                    "array" to listOf("testing", 527737, listOf(true)),
                    "object" to mapOf("nested" to true),
                    "boolean" to false,
                    "args" to it,
                )
            }

            methods["revenge.test.callable"] = {
                val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

                scope.launch {
                    // revenge.test(msg: string)
                    val result = callJSMethod("revenge.test", ArrayList<NativeObject>().apply {
                        add("Hello from native callable test!")
                    }).await()

                    Log.i("JS callable returned: $result")

                    // revenge.setGlobalVariable(name: string, value: any)
                    callJSMethod(
                        "revenge.setGlobalVariable", ArrayList<NativeObject>().apply {
                            add("__CALLABLE_RESULT__")
                            add(result)
                        }
                    ).await()
                }

                null
            }
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun callNativeMethod(rawCallData: ReadableMap): Map<String, NativeObject> = try {
        val callData = rawCallData.toHashMap()[NATIVE_CALL_DATA_KEY] as? HashMap<String, NativeObject>
            ?: throw Error("Invalid native bridge call data")

        val name = callData[NATIVE_METHOD_NAME_KEY] as String
        val method = methods[name]
        method ?: throw Error("Native bridge method not registered: $name")

        val args = callData[NATIVE_METHOD_ARGS_KEY] as MethodArgs

        val result = method(args).toNativeObject()
        mapOf("result" to result)
    } catch (e: Throwable) {
        mapOf("error" to e.stackTraceToString())
    }

    private fun Any?.toNativeObject(): NativeObject = argumentsMakeNativeObject.invoke(
        null, when (this) {
            Unit -> null
            else -> this
        }
    )

    @Suppress("UNCHECKED_CAST")
    private fun ReadableMap.toHashMap() =
        readableMapToHashMap.invoke(this) as HashMap<String, NativeObject>
}
