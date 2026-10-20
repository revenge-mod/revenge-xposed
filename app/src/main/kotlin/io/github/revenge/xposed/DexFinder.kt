package io.github.revenge.xposed

import android.content.pm.ApplicationInfo
import io.github.revenge.logger
import kotlinx.serialization.Serializable
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.result.ClassData
import org.luckypray.dexkit.result.MethodData
import org.luckypray.dexkit.wrap.DexClass
import org.luckypray.dexkit.wrap.DexMethod
import java.io.File
import java.lang.reflect.Method

/**
 * Finds obfuscated host classes and methods with DexKit.
 *
 * Results are cached by descriptor per host APK and module build, so later launches skip the scan.
 * Misses are cached too, since a member missing from one APK stays missing until the APK changes.
 *
 * Prefer a stable name (JNI natives, `@DoNotStrip` members, React methods, manifest components) when one exists,
 * and use this only for members that R8 renames.
 */
object DexFinder {
    private val log = logger("dexFinder")

    private lateinit var classLoader: ClassLoader
    private lateinit var cacheFile: File
    private lateinit var cache: Cache

    private var bridge: DexKitBridge? = null
    private var released = false
    private var nativeLoaded = false

    @Serializable
    private data class Cache(val key: String, val entries: MutableMap<String, String> = mutableMapOf())

    @Synchronized
    fun init(appInfo: ApplicationInfo, classLoader: ClassLoader, modulePath: String) {
        this.classLoader = classLoader
        cacheFile = File(appInfo.dataDir, "${RevengeConstants.CACHE_DIR}/dexkit.json")

        val apk = File(appInfo.sourceDir)
        val key = "${apk.lastModified()}:${apk.length()}:${File(modulePath).lastModified()}"
        cache = runCatching { RevengeJson.decodeFromString(Cache.serializer(), cacheFile.readText()) }
            .getOrNull()
            ?.takeIf { it.key == key }
            ?: Cache(key)
    }

    /**
     * Finds a class by [query], or returns `null` if nothing matched.
     *
     * @param key A unique, stable name for the lookup, used as the cache key.
     */
    @Synchronized
    fun findClass(key: String, query: DexKitBridge.() -> ClassData?): Class<*>? =
        find(key, { DexClass(it).getInstance(classLoader) }) { query()?.descriptor }

    /**
     * Finds a method by [query], or returns `null` if nothing matched.
     *
     * @param key A unique, stable name for the lookup, used as the cache key.
     */
    @Synchronized
    fun findMethod(key: String, query: DexKitBridge.() -> MethodData?): Method? =
        find(key, { DexMethod(it).getMethodInstance(classLoader).apply { isAccessible = true } }) {
            query()?.descriptor
        }

    /**
     * Reads a value from the dex by [query], such as a constant R8 inlined, or returns `null` if nothing matched.
     *
     * @param key A unique, stable name for the lookup, used as the cache key.
     */
    @Synchronized
    fun findString(key: String, query: DexKitBridge.() -> String?): String? = find(key, { it }, query)

    /** Closes the DexKit bridge opened during startup. Lookups after this open and close their own bridge. */
    @Synchronized
    fun release() {
        released = true
        bridge?.close()
        bridge = null
    }

    private fun <T> find(key: String, resolve: (String) -> T, query: DexKitBridge.() -> String?): T? {
        cache.entries[key]?.let { descriptor ->
            if (descriptor.isEmpty()) return null
            runCatching { return resolve(descriptor) }
                .onFailure { log.w("Cached result for $key no longer resolves: $descriptor", it) }
        }

        val bridge = openBridge() ?: return null
        val descriptor = try {
            val start = System.currentTimeMillis()
            bridge.query().also { log.i("Found $key in ${System.currentTimeMillis() - start}ms: $it") }
        } catch (e: Throwable) {
            log.e("Query for $key failed", e)
            return null
        } finally {
            if (released) closeBridge()
        }

        cache.entries[key] = descriptor ?: ""
        saveCache()

        return descriptor?.let {
            runCatching { resolve(it) }
                .onFailure { e -> log.e("Unable to resolve $key: $it", e) }
                .getOrNull()
        }
    }

    private fun openBridge(): DexKitBridge? {
        bridge?.let { return it }

        return try {
            if (!nativeLoaded) {
                System.loadLibrary("dexkit")
                nativeLoaded = true
            }
            DexKitBridge.create(classLoader, true).also { bridge = it }
        } catch (e: Throwable) {
            log.e("Unable to start DexKit", e)
            null
        }
    }

    private fun closeBridge() {
        bridge?.close()
        bridge = null
    }

    private fun saveCache() {
        runCatching {
            cacheFile.parentFile?.ensureDir()
            val temp = File(cacheFile.path + ".tmp")
            temp.writeText(RevengeJson.encodeToString(Cache.serializer(), cache))
            if (!temp.renameTo(cacheFile)) error("Unable to replace ${cacheFile.path}")
        }.onFailure { log.w("Unable to save the DexKit cache", it) }
    }
}
