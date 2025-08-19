package io.github.revenge.xposed.modules

import de.robv.android.xposed.callbacks.XC_LoadPackage
import io.github.revenge.xposed.Module
import java.lang.reflect.Method

/**
 * See for possible return types:
 * https://github.com/facebook/react-native/blob/c23e84ae9/packages/react-native/ReactAndroid/src/main/java/com/facebook/react/bridge/Arguments.kt#L19
 *
 * You may return a [Unit] and the resulting value will be `null`.
 */
typealias BridgeMethodCallback = (args: BridgeMethodArgs) -> Any?

typealias BridgeMethodArgs = ArrayList<Any>

class NativeBridgeModule : Module() {
    private lateinit var readableMapGetStringMethod: Method
    private lateinit var readableMapToHashMapMethod: Method
    private lateinit var argumentsMakeNativeObject: Method

    companion object {
        private val CALL_DATA_KEY = "revenge"
        private val METHOD_NAME_KEY = "method"
        private val METHOD_ARGS_KEY = "args"

        private val methods = mutableMapOf<String, BridgeMethodCallback>(
            "revenge.test" to {
                mapOf(
                    "string" to "string",
                    "number" to 7256,
                    "array" to listOf("testing", 527737, listOf(true)),
                    "object" to mapOf("nested" to true),
                    "boolean" to false
                )
            },
            "revenge.test2" to { it }
        )

        fun registerMethod(name: String, callback: BridgeMethodCallback) {
            require(!methods.containsKey(name)) {
                "Bridge callback already exists: $name"
            }

            methods[name] = callback
        }
    }

    private fun Any?.toNativeObject(): Any? =
        argumentsMakeNativeObject.invoke(null, when (this) {
            Unit -> null
            else -> this
        })

    override fun onLoad(packageParam: XC_LoadPackage.LoadPackageParam) = with(packageParam) {
        val argumentsClass = classLoader.loadClass("com.facebook.react.bridge.Arguments")
        val readableMapClass = classLoader.loadClass("com.facebook.react.bridge.ReadableMap")
        val promiseClass = classLoader.loadClass("com.facebook.react.bridge.Promise")

        val promiseResolveMethod = promiseClass.method("resolve", Object::class.java)
        argumentsMakeNativeObject =
            argumentsClass.method("makeNativeObject", Object::class.java)
        readableMapGetStringMethod =
            readableMapClass.method("getString", String::class.java)
        readableMapToHashMapMethod = readableMapClass.method("toHashMap")

        classLoader.loadClass("com.horcrux.svg.RNSVGRenderableManager")
            .hookMethod("getBBox", Double::class.javaObjectType, readableMapClass) {
                before {
                    callBridgeMethod(readableMapToHashMap(args[1]!!))
                        ?.let { result = it.toNativeObject() }
                }
            }

        classLoader.loadClass("com.facebook.react.modules.blob.FileReaderModule")
            .hookMethod("readAsDataURL", readableMapClass, promiseClass) {
                before {
                    callBridgeMethod(readableMapToHashMap(args[0]!!))
                        ?.let { ret ->
                            promiseResolveMethod.invoke(args[1], ret.toNativeObject())
                            result = null
                        }
                }
            }

        return@with
    }

    private fun readableMapToHashMap(map: Any): HashMap<String, Any?> {
        @Suppress("UNCHECKED_CAST")
        return readableMapToHashMapMethod.invoke(map) as HashMap<String, Any?>
    }

    private fun callBridgeMethod(hashMap: HashMap<String, Any?>): Map<String, Any?>? = try {
        val (method, args) = getBridgeCallData(hashMap) ?: return null
        val ret = method(args).toNativeObject()
        mapOf("result" to ret)
    } catch (e: Throwable) {
        mapOf("error" to e.toString())
    }

    private fun getBridgeCallData(hashMap: HashMap<String, Any?>): Pair<BridgeMethodCallback, BridgeMethodArgs>? {
        @Suppress("UNCHECKED_CAST")
        val data = hashMap[CALL_DATA_KEY] as HashMap<String, Any?>?
        data ?: return null

        @Suppress("UNCHECKED_CAST")
        val name = data[METHOD_NAME_KEY] as String
        val method = methods[name]
        method ?: throw Error("Method not registered: $name")

        @Suppress("UNCHECKED_CAST")
        val args = data[METHOD_ARGS_KEY] as BridgeMethodArgs

        return Pair(method, args)
    }
}
