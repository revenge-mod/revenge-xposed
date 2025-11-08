package io.github.revenge.xposed.modules.bridge

import de.robv.android.xposed.callbacks.XC_LoadPackage
import io.github.revenge.xposed.BuildConfig
import io.github.revenge.xposed.Constants
import io.github.revenge.xposed.Module
import io.github.revenge.xposed.Utils.Log
import java.lang.reflect.Method

/**
 * See for possible return types:
 * https://github.com/facebook/react-native/blob/c23e84ae9/packages/react-native/ReactAndroid/src/main/java/com/facebook/react/bridge/Arguments.kt#L19
 *
 * You may return a [Unit] and the resulting value will be `null`.
 */
typealias BridgeMethodCallback = (args: BridgeMethodArgs) -> Any?

typealias BridgeMethodArgs = ArrayList<Any>

/**
 * A module that exposes a bridge for calling methods from JavaScript.
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
 */
object BridgeModule : Module() {
    private lateinit var readableMapGetString: Method
    private lateinit var readableMapToHashMap: Method
    private lateinit var argumentsMakeNative: Method

    private const val CALL_DATA_KEY = "revenge"
    private const val METHOD_NAME_KEY = "method"
    private const val METHOD_ARGS_KEY = "args"

    private val methods: MutableMap<String, BridgeMethodCallback> = mutableMapOf()

    /**
     * Registers a bridge method that can be called from JavaScript.
     *
     * If the method is already registered, it will be overwritten.
     *
     * @param name The name of the method to register.
     * @param callback The callback to invoke when the method is called.
     */
    fun registerMethod(name: String, callback: BridgeMethodCallback) {
        if (methods.containsKey(name)) Log.w("Bridge method already exists and will be overridden: $name")
        methods[name] = callback
    }

    override fun onLoad(packageParam: XC_LoadPackage.LoadPackageParam) = with(packageParam) {
        val arguments = classLoader.loadClass("com.facebook.react.bridge.Arguments")
        val readableMap = classLoader.loadClass("com.facebook.react.bridge.ReadableMap")
        val promise = classLoader.loadClass("com.facebook.react.bridge.Promise")

        val promiseResolve = promise.method("resolve", Object::class.java)
        argumentsMakeNative = arguments.method("makeNativeObject", Object::class.java)
        readableMapGetString = readableMap.method("getString", String::class.java)
        readableMapToHashMap = readableMap.method("toHashMap")

        classLoader.loadClass("com.horcrux.svg.RNSVGRenderableManager")
            .hookMethod("getBBox", Double::class.javaObjectType, readableMap) {
                before {
                    callBridgeMethod(readableMapToHashMap(args[1]!!))?.let { result = it.toNativeObject() }
                }
            }

        classLoader.loadClass("com.facebook.react.modules.blob.FileReaderModule")
            .hookMethod("readAsDataURL", readableMap, promise) {
                before {
                    val (readableMap, promise) = args
                    callBridgeMethod(readableMapToHashMap(readableMap!!))?.let {
                        promiseResolve.invoke(promise, it.toNativeObject())
                        result = null
                    }
                }
            }

        // Clear methods every package load. Plugins should re-register on each load.
        methods.clear()
        registerDefaultMethods()

        return@with
    }

    private fun registerDefaultMethods() {
        methods["revenge.info"] = {
            mapOf(
                "name" to Constants.LOADER_NAME, "version" to BuildConfig.VERSION_CODE
            )
        }

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
    }

    private fun Any?.toNativeObject(): Any? = argumentsMakeNative.invoke(
        null, when (this) {
            Unit -> null
            else -> this
        }
    )

    private fun readableMapToHashMap(map: Any): HashMap<String, Any?> {
        @Suppress("UNCHECKED_CAST") return readableMapToHashMap.invoke(map) as HashMap<String, Any?>
    }

    private fun callBridgeMethod(hashMap: HashMap<String, Any?>): Map<String, Any?>? = try {
        val (method, args) = getBridgeCallData(hashMap) ?: return null
        val ret = method(args).toNativeObject()
        mapOf("result" to ret)
    } catch (e: Throwable) {
        mapOf("error" to e.stackTraceToString())
    }

    private fun getBridgeCallData(hashMap: HashMap<String, Any?>): Pair<BridgeMethodCallback, BridgeMethodArgs>? {
        @Suppress("UNCHECKED_CAST") val data = hashMap[CALL_DATA_KEY] as HashMap<String, Any?>?
        data ?: return null

        @Suppress("UNCHECKED_CAST") val name = data[METHOD_NAME_KEY] as String
        val method = methods[name]
        method ?: throw Error("Method not registered: $name")

        @Suppress("UNCHECKED_CAST") val args = data[METHOD_ARGS_KEY] as BridgeMethodArgs

        return Pair(method, args)
    }
}
