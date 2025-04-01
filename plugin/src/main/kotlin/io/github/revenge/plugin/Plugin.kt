package io.github.revenge.plugin

import android.content.pm.ApplicationInfo
import com.facebook.react.bridge.Callback
import com.facebook.react.bridge.ReactContextBaseJavaModule
import com.facebook.react.bridge.ReactMethod

class Plugin internal constructor(
    private val name: String,
    private val callbacks: Map<String, Callback>,
    init: () -> Unit
) : ReactContextBaseJavaModule() {
    init {
        init()
    }

    override fun getName(): String = name

    @ReactMethod
    fun callbacks() = callbacks
}

class PluginContext(
    val appInfo: ApplicationInfo? = null,
    val classLoader: ClassLoader? = null,
)

class PluginBuilder internal constructor(private val name: String) {
    private var initBlock: (PluginContext) -> Unit = {}
    private val callbackBlocks = mutableMapOf<String, (PluginContext, Array<Any>) -> Unit>()

    fun init(block: (PluginContext) -> Unit) {
        initBlock = block
    }

    operator fun String.invoke(callbackBlock: (PluginContext, Array<Any>) -> Unit) {
        callbackBlocks[this] = callbackBlock
    }

    fun build(context: PluginContext) = Plugin(
        name = name,
        callbacks = callbackBlocks.mapValues { (_, block) -> Callback { args -> block(context, args) } },
        init = { initBlock(context) }
    )
}

fun plugin(name: String, block: PluginBuilder.() -> Unit) =
    PluginBuilder(name).apply(block)
