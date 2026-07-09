package io.github.revenge.xposed

import io.github.revenge.Logger
import io.github.revenge.logger
import io.github.revenge.xposed.api.HostScope
import kotlin.properties.PropertyDelegateProvider
import kotlin.properties.ReadOnlyProperty

/**
 * A bound tweak with a [HostScope] and [log].
 *
 * ```kotlin
 * val myTweak by tweak {
 *     log.i("hello from ${appInfo.packageName}")
 *     classLoader.loadClass("...").method("foo").hook { /* ... */ }
 *     registerMethod("my.method") { args -> mapOf("echo" to args) }
 * }
 * ```
 */
class Tweak internal constructor(
    val name: String,
    context: HostScope,
) : HostScope by context {
    /** Per-tweak logger, namespaced by [name]. */
    val log: Logger = logger(name)
}

/**
 * The compiled form of `val foo by tweak { ... }`.
 * Captures the tweak name (from the `val`'s declaration site) and the body the host will run.
 */
class TweakSpec internal constructor(
    val name: String,
    private val body: Tweak.() -> Unit,
) {
    /** Run the tweak body against [context]. */
    fun applyTo(context: HostScope) {
        Tweak(name, context).body()
    }
}

/**
 * Declare a tweak. The block runs once, when the host applies the tweak set, with a [Tweak]
 * receiver. The tweak's name is taken from the delegation:
 *
 * ```kotlin
 * val myTweak by tweak {     // name = "myTweak"
 *     log.i("running")
 * }
 * ```
 */
fun tweak(
    body: Tweak.() -> Unit,
): PropertyDelegateProvider<Any?, ReadOnlyProperty<Any?, TweakSpec>> =
    PropertyDelegateProvider { _, property ->
        val spec = TweakSpec(property.name, body)
        ReadOnlyProperty { _, _ -> spec }
    }
