package io.github.revenge

import com.facebook.react.bridge.Callback

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
