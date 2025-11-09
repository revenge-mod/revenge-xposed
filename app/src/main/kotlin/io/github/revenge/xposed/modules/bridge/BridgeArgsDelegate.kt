@file:Suppress("UNUSED")

package io.github.revenge.xposed.modules.bridge

import kotlin.properties.ReadOnlyProperty

/**
 * Turn [BridgeMethodArgs] into a delegate-friendly reader.
 *
 * Example:
 * ```kotlin
 * registerMethod("method.name") { argv ->
 * val args = argv.asDelegate()
 *
 * val s: String by args.string()
 * val i: Int by args.int()
 * val b: Boolean by args.boolean(default = false)
 * val arr: ArrayList<Any?> by args.arrayList()
 * val obj: HashMap<String, Any?> by args.hashMap()
 * val fifth: String by args.at(4).string()
 * }
 * ```
 */
fun BridgeMethodArgs.asDelegate(): ArgsDelegate = ArgsDelegate(this)

/**
 * Provides property-delegate style, typed access to a positional argument list.
 *
 * - Sequential access: `val a: String by args.string()`
 * Each call captures the current cursor index and advances the cursor by 1.
 *
 * - Random access: `val x: Int by args.at(4).int()`
 * Uses the absolute index without affecting the cursor.
 *
 * - Defaults: `val b: Boolean by args.boolean(default = false)`
 *
 * - Nullable variants: `val c: Int? by args.intOrNull()`
 */
class ArgsDelegate(source: List<Any?>) {
    // Snapshot in case list is mutated elsewhere
    private val raw: List<Any?> = source.toList()
    private var cursor: Int = 0

    private fun sequential(): Accessor = Accessor(raw) { captureIndex() }

    fun string(default: String? = null)
            : ReadOnlyProperty<Any?, String> = sequential().string(default)

    fun stringOrNull()
            : ReadOnlyProperty<Any?, String?> = sequential().stringOrNull()

    fun int(default: Int? = null)
            : ReadOnlyProperty<Any?, Int> = sequential().int(default)

    fun intOrNull()
            : ReadOnlyProperty<Any?, Int?> = sequential().intOrNull()

    fun double(default: Double? = null)
            : ReadOnlyProperty<Any?, Double> = sequential().double(default)

    fun doubleOrNull()
            : ReadOnlyProperty<Any?, Double?> = sequential().doubleOrNull()

    fun boolean(default: Boolean? = null)
            : ReadOnlyProperty<Any?, Boolean> = sequential().boolean(default)

    fun booleanOrNull()
            : ReadOnlyProperty<Any?, Boolean?> = sequential().booleanOrNull()

    fun arrayList(
        default: ArrayList<String>? = null
    ): ReadOnlyProperty<Any?, ArrayList<String>> =
        sequential().arrayList(default)

    fun arrayListOrNull()
            : ReadOnlyProperty<Any?, ArrayList<String>?> =
        sequential().arrayListOrNull()

    @JvmName("arrayListTyped")
    fun <T> arrayList(
        default: ArrayList<T>? = null
    ): ReadOnlyProperty<Any?, ArrayList<T>> =
        sequential().arrayList(default)

    @JvmName("arrayListOrNullTyped")
    fun <T> arrayListOrNull()
            : ReadOnlyProperty<Any?, ArrayList<T>?> =
        sequential().arrayListOrNull()

    fun hashMap(
        default: HashMap<String, Any?>? = null
    ): ReadOnlyProperty<Any?, HashMap<String, Any?>> =
        sequential().hashMap(default)

    fun hashMapOrNull()
            : ReadOnlyProperty<Any?, HashMap<String, Any?>?> =
        sequential().hashMapOrNull()

    @JvmName("hashMapTyped")
    fun <K, V> hashMap(
        default: HashMap<K, V>? = null
    ): ReadOnlyProperty<Any?, HashMap<K, V>> =
        sequential().hashMap(default)

    @JvmName("hashMapOrNullTyped")
    fun <K, V> hashMapOrNull()
            : ReadOnlyProperty<Any?, HashMap<K, V>?> =
        sequential().hashMapOrNull()

    fun at(index: Int): Accessor = Accessor(raw) { index }

    fun skip(count: Int = 1): ArgsDelegate = apply { cursor += count }

    private fun captureIndex(): Int = cursor.also { cursor++ }

    /**
     * Lightweight builder for capturing an index for the delegate.
     */
    class Accessor(
        private val src: List<Any?>,
        private val indexProvider: () -> Int
    ) {
        fun string(default: String? = null): ReadOnlyProperty<Any?, String> =
            prop(src, indexProvider(), default, "String") { it as? String }

        fun stringOrNull(): ReadOnlyProperty<Any?, String?> =
            propNullable(src, indexProvider()) { it as? String }

        fun int(default: Int? = null): ReadOnlyProperty<Any?, Int> =
            prop(src, indexProvider(), default, "Int", ::coerceInt)

        fun intOrNull(): ReadOnlyProperty<Any?, Int?> =
            propNullable(src, indexProvider(), ::coerceInt)

        fun double(default: Double? = null): ReadOnlyProperty<Any?, Double> =
            prop(src, indexProvider(), default, "Double") { it as? Double }

        fun doubleOrNull(): ReadOnlyProperty<Any?, Double?> =
            propNullable(src, indexProvider()) { it as? Double }

        fun boolean(default: Boolean? = null): ReadOnlyProperty<Any?, Boolean> =
            prop(src, indexProvider(), default, "Boolean") { it as? Boolean }

        fun booleanOrNull(): ReadOnlyProperty<Any?, Boolean?> =
            propNullable(src, indexProvider()) { it as? Boolean }

        fun <T> arrayList(
            default: ArrayList<T>? = null
        ): ReadOnlyProperty<Any?, ArrayList<T>> =
            @Suppress("UNCHECKED_CAST")
            prop(src, indexProvider(), default, "ArrayList<T>") { it as? ArrayList<T> }

        fun <T> arrayListOrNull(): ReadOnlyProperty<Any?, ArrayList<T>?> =
            @Suppress("UNCHECKED_CAST")
            propNullable(src, indexProvider()) { it as? ArrayList<T> }

        fun <K, V> hashMap(
            default: HashMap<K, V>? = null
        ): ReadOnlyProperty<Any?, HashMap<K, V>> =
            @Suppress("UNCHECKED_CAST")
            prop(src, indexProvider(), default, "HashMap<K, V>") { it as? HashMap<K, V> }

        fun <K, V> hashMapOrNull(): ReadOnlyProperty<Any?, HashMap<K, V>?> =
            @Suppress("UNCHECKED_CAST")
            propNullable(src, indexProvider()) { it as? HashMap<K, V> }
    }
}

private fun <T> prop(
    src: List<Any?>,
    index: Int,
    default: T?,
    expectedTypeName: String,
    convert: (Any?) -> T?
): ReadOnlyProperty<Any?, T> {
    return ReadOnlyProperty<Any?, T> { _, property ->
        val name = property.name
        val rawValue = src.getOrNull(index)
        val converted = convert(rawValue)
        val value = converted ?: default
        if (value == null) {
            val size = src.size
            val kind =
                if (rawValue == null && index >= size) "missing" else "invalid"
            throw ArgParseException(
                propertyName = name,
                index = index,
                expectedType = expectedTypeName,
                actualValue = rawValue,
                argsSize = size,
                message = "Argument '$name' at index $index is $kind."
            )
        }
        value
    }
}

private fun <T> propNullable(
    src: List<Any?>,
    index: Int,
    convert: (Any?) -> T?
): ReadOnlyProperty<Any?, T?> {
    return ReadOnlyProperty { _, _ ->
        val rawValue = src.getOrNull(index)
        convert(rawValue)
    }
}

private class ArgParseException(
    val propertyName: String,
    val index: Int,
    val expectedType: String,
    val actualValue: Any?,
    val argsSize: Int,
    message: String
) : IllegalArgumentException("$message. Expected: $expectedType, Provided: $actualValue (args size=$argsSize)")

private fun coerceInt(v: Any?): Int? = when (v) {
    null -> null
    is Int -> v
    is Number -> v.toInt() // RN typically passes Double
    is String -> v.toIntOrNull()
    else -> null
}