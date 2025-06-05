package io.github.revenge

import android.content.pm.ApplicationInfo
import com.facebook.react.bridge.Callback
import com.facebook.react.bridge.ReactContextBaseJavaModule
import com.facebook.react.bridge.ReactMethod
import kotlin.properties.ReadOnlyProperty
import kotlin.reflect.KProperty

class Plugin internal constructor(
    private val name: String,
    initBlock: () -> Unit,
    private val callbacks: Map<String, Callback>,
) : ReactContextBaseJavaModule() {
    init {
        initBlock()
    }

    override fun getName() = name

    /**
     * @return The functions that can be called from JavaScript associated by a name.
     */
    @ReactMethod
    fun getCallbacks() = callbacks

    companion object {
        fun plugin(name: String, block: Builder.() -> Unit) = Builder(name).apply(block)
      
        fun plugin(block: Builder.() -> Unit) = object : ReadOnlyProperty<Any?, Builder> {
            override fun getValue(thisRef: Any?, property: KProperty<*>) = Builder(property.name).apply(block)
        }
    }

    class Context(
        val applicationInfo: ApplicationInfo,
        val classLoader: ClassLoader,
    )

    class Builder internal constructor(private val name: String) {
        private var initBlock: Context.() -> Unit = {}
        private val callbackBlocks = mutableMapOf<String, Context.(Array<Any?>) -> Unit>()

        fun init(block: Context.() -> Unit) {
            initBlock = block
        }

        operator fun String.invoke(callbackBlock: Context.(Array<Any?>) -> Unit) {
            callbackBlocks[this] = callbackBlock
        }


        fun Context.build() = Plugin(
            name,
            { initBlock() },
            callbackBlocks.mapValues { (_, block) -> Callback { args -> block(args) } },
        )
    }
}
