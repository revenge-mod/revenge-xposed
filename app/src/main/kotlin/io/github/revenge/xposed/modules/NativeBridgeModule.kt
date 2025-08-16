package io.github.revenge.xposed.modules

import de.robv.android.xposed.callbacks.XC_LoadPackage
import io.github.revenge.xposed.Module
import io.github.revenge.xposed.Utils.Log
import java.lang.reflect.Method

typealias BridgeMethodCallback = (HashMap<String, Any>) -> Any?

class NativeBridgeModule : Module() {

    private lateinit var packageParam: XC_LoadPackage.LoadPackageParam

    private lateinit var readableMapGetStringMethod: Method
    private lateinit var readableMapToHashMapMethod: Method
    private lateinit var argumentsMakeNativeObject: Method

    companion object {
        private val BRIDGE_CALL_KEY = "revenge"
        private val methods = mutableMapOf<String, BridgeMethodCallback>(
            "revenge.test" to {
                mapOf(
                    "string" to "string",
                    "number" to 7256,
                    "array" to listOf("testing", 527737, listOf(true)),
                    "object" to mapOf("nested" to true),
                    "boolean" to false
                )
            }
        )

        fun registerMethod(name: String, callback: BridgeMethodCallback) {
            require(!methods.containsKey(name)) {
                "Bridge callback already exists: $name"
            }

            methods[name] = callback
        }
    }


    private fun Any?.toNativeObject(): Any? =
        argumentsMakeNativeObject.invoke(null, this)

    override fun onInit(packageParam: XC_LoadPackage.LoadPackageParam) = with(packageParam) {
        this@NativeBridgeModule.packageParam = packageParam

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
                    val map = args[1]!!
                    callBridgeMethod(map)?.let { result = it.toNativeObject() }
                }
            }

        classLoader.loadClass("com.facebook.react.modules.blob.FileReaderModule")
            .hookMethod("readAsDataURL", readableMapClass, promiseClass) {
                before {
                    val map = args[0]!!
                    callBridgeMethod(map)?.let { ret ->
                        promiseResolveMethod.invoke(args[1], ret.toNativeObject())
                        result = null
                    }
                }
            }

        return@with
    }

    private fun callBridgeMethod(map: Any): Map<String, Any?>? = try {
        val method = getBridgeCallMethod(map) ?: return null
        val ret = method(getBridgeCallArgs(map)).toNativeObject()
        mapOf("result" to ret)
    } catch (e: Error) {
        mapOf("error" to e.toString())
    }

    private fun getBridgeCallArgs(map: Any): HashMap<String, Any> {
        @Suppress("UNCHECKED_CAST")
        val args = readableMapToHashMapMethod.invoke(map) as HashMap<String, Any>
        args.remove(BRIDGE_CALL_KEY)
        return args
    }

    private fun getBridgeCallMethod(map: Any): BridgeMethodCallback? = try {
        val name = readableMapGetStringMethod.invoke(map, BRIDGE_CALL_KEY) as? String
            ?: return null

        methods[name] ?: throw Error("Method $name not registered")
    } catch (e: RuntimeException) {
        // UnexpectedNativeTypeException : RuntimeException
        Log.e(e.toString())
        throw Error("Bad method name")
    }
}
