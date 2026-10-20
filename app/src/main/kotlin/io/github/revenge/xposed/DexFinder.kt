package io.github.revenge.xposed

import android.content.pm.ApplicationInfo
import io.github.revenge.logger
import kotlinx.serialization.Serializable
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.query.FindClass
import org.luckypray.dexkit.query.FindField
import org.luckypray.dexkit.query.FindMethod
import java.io.File
import io.github.revenge.xposed.api.DexFinder as DexFinderApi

/**
 * [DexFinderApi], shared by tweaks and plugins.
 *
 * Results are cached as strings (descriptors for members) per host APK and module build, so later launches skip the scan.
 * Misses are cached too, since a member missing from one APK stays missing until the APK changes.
 */
object DexFinder : DexFinderApi {
    private val log = logger("dexFinder")

    override lateinit var classLoader: ClassLoader
        private set
    private lateinit var cacheFile: File
    private lateinit var cache: Cache

    private var bridge: DexKitBridge? = null
    private var released = false
    private var nativeLoaded = false

    @Serializable
    private data class Cache(val key: String, val entries: MutableMap<String, String?> = mutableMapOf())

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
     * Finds a value by [query], or returns `null` if nothing matched.
     *
     * The value is cached as the string [encode] returns, and [decode] turns that string back into the result.
     *
     * @param key A unique, stable name for the lookup, used as the cache key.
     */
    @Synchronized
    override fun <R : Any, T> findValue(
        key: String,
        encode: (R) -> String,
        decode: (String) -> T,
        query: DexKitBridge.() -> R?,
    ): T? = find(key, decode) { query()?.let(encode) }

    /** Closes the DexKit bridge opened during startup. Lookups after this open and close their own bridge. */
    @Synchronized
    fun release() {
        released = true
        bridge?.close()
        bridge = null
    }

    private fun <T> find(key: String, resolve: (String) -> T, query: DexKitBridge.() -> String?): T? {
        if (key in cache.entries) {
            val descriptor = cache.entries[key] ?: return null
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

        cache.entries[key] = descriptor
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

/** Prefixes named [DexFinder] lookup keys with [prefix] to not collide with other plugins or the host. */
class ScopedDexFinder(private val prefix: String) : DexFinderApi {
    override val classLoader get() = DexFinder.classLoader

    override fun <R : Any, T> findValue(
        key: String,
        encode: (R) -> String,
        decode: (String) -> T,
        query: DexKitBridge.() -> R?,
    ): T? = DexFinder.findValue("$prefix$key", encode, decode, query)

    override fun findClass(query: FindClass.() -> Unit) = DexFinder.findClass(query)
    override fun findMethod(query: FindMethod.() -> Unit) = DexFinder.findMethod(query)
    override fun findField(query: FindField.() -> Unit) = DexFinder.findField(query)
}
