package io.github.revenge.plugin


import android.content.pm.ApplicationInfo
import java.lang.reflect.Method
import java.lang.reflect.Proxy

class PluginContext(
    val appInfo: ApplicationInfo,
    val classLoader: ClassLoader,
)

class PluginBuilder internal constructor(private val name: String) {
    private var initBlock: PluginContext.() -> Unit = {}
    private val callbackBlocks = mutableMapOf<String, PluginContext.(Array<Any>) -> Unit>()

    fun init(block: PluginContext.() -> Unit) {
        initBlock = block
    }

    operator fun String.invoke(callbackBlock: PluginContext.(Array<Any>) -> Unit) {
        callbackBlocks[this] = callbackBlock
    }

    // Use a proxy because ReactContextBaseJavaModule is not available at plugin build time.
    fun PluginContext.build(): Any = Proxy.newProxyInstance(
        classLoader,
        arrayOf(classLoader.loadClass("com.facebook.react.bridge.ReactContextBaseJavaModule"))
    ) { _, method: Method, args: Array<Any> ->
        when (method.name) {
            "getName" -> name
            "getCallbacks" -> callbackBlocks.mapValues { (_, block) ->
                // Use a proxy because Callback is not available at plugin build time.
                Proxy.newProxyInstance(
                    classLoader,
                    arrayOf(classLoader.loadClass("com.facebook.react.bridge.Callback"))
                ) { _, method, args ->
                    when (method.name) {
                        "invoke" -> block(args)
                        else -> throw NoSuchMethodException("Method ${method.name} not found in plugin $name")
                    }
                }
            }

            else -> throw NoSuchMethodException("Method ${method.name} not found in plugin $name")
        }
    } 
}

fun plugin(name: String, block: PluginBuilder.() -> Unit) =
    PluginBuilder(name).apply(block)