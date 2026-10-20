package io.github.revenge.xposed.api

import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.query.FindClass
import org.luckypray.dexkit.query.FindField
import org.luckypray.dexkit.query.FindMethod
import org.luckypray.dexkit.result.ClassData
import org.luckypray.dexkit.result.FieldData
import org.luckypray.dexkit.result.MethodData
import org.luckypray.dexkit.wrap.DexClass
import org.luckypray.dexkit.wrap.DexField
import org.luckypray.dexkit.wrap.DexMethod
import java.lang.reflect.Field
import java.lang.reflect.Method

/**
 * Finds obfuscated host classes, methods, fields and values with DexKit.
 *
 * Results are cached per host APK, so later launches skip the scan. Misses are cached too.
 * [findClass], [findMethod] and [findField] key the cache by the query's content.
 *
 * Lookups made during startup (such as in a plugin's `start`) share one DexKit bridge.
 * Later lookups that miss the cache open and close their own, which is slower.
 *
 * Prefer a stable name (JNI natives, `@DoNotStrip` members, React methods, manifest components) when one exists,
 * and use this only for members that R8 renames.
 *
 * ```kotlin
 * val method = dexFinder.findMethod {
 *     matcher { usingStrings("a string only this method uses") }
 * }
 * ```
 */
interface DexFinder {
    /** [ClassLoader] that found classes and members are loaded from. */
    val classLoader: ClassLoader

    /**
     * Finds a value by [query], or returns `null` if nothing matched.
     *
     * The value is cached as the string [encode] returns, and [decode] turns that string back into the result.
     * Use this only when a query needs code DexKit can't express, since the cache can't tell queries apart by content.
     * Prefer [findClass], [findMethod] and [findField].
     *
     * @param key A stable name for the lookup, used as the cache key. In a plugin, it is scoped to the plugin and its version.
     */
    fun <R : Any, T> findValue(
        key: String,
        encode: (R) -> String,
        decode: (String) -> T,
        query: DexKitBridge.() -> R?,
    ): T?

    /**
     * Finds the class [query] matches, or returns `null` if it matched none or more than one.
     *
     * The result is cached by the query's content, so plugins making the same query share it.
     */
    fun findClass(query: FindClass.() -> Unit): Class<*>? {
        val find = FindClass().apply(query)
        return findValue(
            "class:${find.hashKey()}",
            ClassData::descriptor,
            { DexClass(it).getInstance(classLoader) },
        ) { findClass(find).singleOrNull() }
    }

    /**
     * Finds the method [query] matches, or returns `null` if it matched none or more than one. The method is made accessible.
     *
     * The result is cached by the query's content, so plugins making the same query share it.
     */
    fun findMethod(query: FindMethod.() -> Unit): Method? {
        val find = FindMethod().apply(query)
        return findValue(
            "method:${find.hashKey()}",
            MethodData::descriptor,
            { DexMethod(it).getMethodInstance(classLoader).apply { isAccessible = true } },
        ) { findMethod(find).singleOrNull() }
    }

    /**
     * Finds the field [query] matches, or returns `null` if it matched none or more than one. The field is made accessible.
     *
     * The result is cached by the query's content, so plugins making the same query share it.
     */
    fun findField(query: FindField.() -> Unit): Field? {
        val find = FindField().apply(query)
        return findValue(
            "field:${find.hashKey()}",
            FieldData::descriptor,
            { DexField(it).getFieldInstance(classLoader).apply { isAccessible = true } },
        ) { findField(find).singleOrNull() }
    }

    /**
     * Reads a string from the dex by [query], such as a constant R8 inlined, or returns `null` if nothing matched.
     *
     * @param key A stable name for the lookup, used as the cache key. In a plugin, it is scoped to the plugin and its version.
     */
    fun findString(key: String, query: DexKitBridge.() -> String?): String? = findValue(key, { it }, { it }, query)
}
