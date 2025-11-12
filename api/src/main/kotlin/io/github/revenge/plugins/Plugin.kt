package io.github.revenge.plugins

import InternalApi
import android.content.pm.ApplicationInfo
import kotlin.properties.ReadOnlyProperty

typealias Callback = Plugin.(args: ArrayList<Any>) -> Any

class PluginBuilder() {
    private val methods: MutableMap<String, Callback> = mutableMapOf()
    private var init: ((ApplicationInfo, ClassLoader) -> Unit)? = null

    operator fun String.invoke(callback: Callback) = apply { methods[this] = callback }

    fun init(block: (ApplicationInfo, ClassLoader) -> Unit) = apply { init = block }

    fun build(jsCaller: (methodName: String, args: Any) -> Any) = Plugin(init, methods, jsCaller)
}

fun plugin(block: PluginBuilder.() -> Unit) = PluginBuilder().apply(block)


class Plugin internal constructor(
    @OptIn(InternalApi::class) val init: ((ApplicationInfo, ClassLoader) -> Unit)?,
    @OptIn(InternalApi::class) val methods: Map<String, Callback>,
    val jsCaller: (methodName: String, args: Any) -> Any,
) {
    operator fun String.invoke(vararg args: Any) = jsCaller(this, args.toList())
}

val p = plugin {
    "name" {
        "jsFunc"("args", 1)
    }
}