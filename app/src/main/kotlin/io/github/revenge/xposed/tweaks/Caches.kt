package io.github.revenge.xposed.tweaks

import android.util.AtomicFile
import io.github.revenge.Logger
import io.github.revenge.bridge.asDelegate
import io.github.revenge.xposed.ensureDir
import io.github.revenge.xposed.ensureFile
import io.github.revenge.xposed.tweak
import io.github.revenge.xposed.tweaks.bridge.RevengeBridgeRegistry
import io.github.revenge.xposed.tweaks.plugins.DISCORD_OTA_COMMIT
import io.github.revenge.xposed.versionCode
import java.io.*

/**
 * Module-finds + asset-mapping caches, keyed by host app versionCode so they're auto-invalidated on app update.
 */
val caches by tweak {
    val log: Logger = this.log

    withAppContext { ctx ->
        val revengeCacheDir = File(ctx.cacheDir, "revenge").apply { ensureDir() }
        val versionCode = ctx.versionCode()

        fun cacheFileName(type: String): String {
            var name = "$type.$versionCode"
            if (DISCORD_OTA_COMMIT != null) name = "$name.$DISCORD_OTA_COMMIT"
            log.d("$type cache at: $name")
            return name
        }

        val modulesCacheFile by lazy { File(revengeCacheDir, cacheFileName("modules")).apply { ensureFile() } }
        val assetsCacheFile by lazy { File(revengeCacheDir, cacheFileName("assets")).apply { ensureFile() } }

        var modulesCache: ModulesCache? = null
        var assetsCache: AssetsCache? = null

        with(RevengeBridgeRegistry) {
            registerMethod("revenge.caches.modules.read") {
                (modulesCache ?: ModulesCache.loadFromFileOrNull(modulesCacheFile, log)?.also { modulesCache = it })
                    ?.toMap()
            }

            registerMethod("revenge.caches.modules.write") { args ->
                val argv = args.asDelegate()
                val blacklist by argv.arrayList<Double>()
                val finds by argv.hashMap<String, HashMap<String, Double>?>()
                modulesCache = ModulesCache(blacklist, finds).also { it.saveToFile(modulesCacheFile) }
                log.i("Modules cache saved: ${modulesCacheFile.absolutePath} (blacklisted: ${blacklist.size}, finds: ${finds.size})")
                null
            }

            registerMethod("revenge.caches.assets.read") {
                (assetsCache ?: AssetsCache.loadFromFileOrNull(assetsCacheFile, log)?.also { assetsCache = it })
                    ?.toMap()
            }

            registerMethod("revenge.caches.assets.write") { args ->
                val argv = args.asDelegate()
                val data by argv.hashMap<String, HashMap<String, Double>>()
                assetsCache = AssetsCache(data).also { it.saveToFile(assetsCacheFile) }
                log.i("Assets cache saved: ${assetsCacheFile.absolutePath} (count: ${data.size})")
                null
            }
        }
    }
}

private class CacheVersionMismatchException(expected: Int, actual: Int) :
    Throwable("Expected cache version: $expected, but got version: $actual")

internal data class ModulesCache(
    val blacklist: List<Double>,
    val finds: Map<String, Map<String, Double>?>,
) {
    fun saveToFile(file: File) {
        val atomic = AtomicFile(file)
        var fos: FileOutputStream? = null
        try {
            fos = atomic.startWrite()
            val out = DataOutputStream(BufferedOutputStream(fos))
            out.writeInt(VERSION)

            out.writeInt(blacklist.size)
            for (num in blacklist) out.writeInt(num.toInt())

            out.writeInt(finds.size)
            for ((filter, matches) in finds) {
                out.writeUTF(filter)
                if (matches == null) {
                    out.writeBoolean(false)
                } else {
                    out.writeBoolean(true)
                    out.writeInt(matches.size)
                    for ((id, flag) in matches) {
                        out.writeInt(id.toInt())
                        out.writeInt(flag.toInt())
                    }
                }
            }
            out.flush()
            atomic.finishWrite(fos)
        } catch (t: Throwable) {
            if (fos != null) atomic.failWrite(fos)
            throw t
        }
    }

    fun toMap(): Map<String, Any> {
        val findsMap = finds.mapValues { (_, matches) -> matches?.toMap() }
        return mapOf("blacklist" to blacklist, "finds" to findsMap, "version" to VERSION)
    }

    companion object {
        const val VERSION = 3

        fun loadFromFileOrNull(file: File, log: Logger): ModulesCache? {
            if (!file.exists() || file.length() <= 0L) return null
            val atomic = AtomicFile(file)
            try {
                DataInputStream(BufferedInputStream(atomic.openRead())).use { input ->
                    val version = input.readInt()
                    if (version != VERSION) throw CacheVersionMismatchException(VERSION, version)

                    val blacklistSize = input.readInt()
                    val blacklist = MutableList(blacklistSize) { input.readInt().toDouble() }

                    val findsSize = input.readInt()
                    val finds = mutableMapOf<String, Map<String, Double>?>()
                    repeat(findsSize) {
                        val filter = input.readUTF()
                        val hasMatches = input.readBoolean()
                        if (!hasMatches) {
                            finds[filter] = null
                        } else {
                            val mapSize = input.readInt()
                            val matches = mutableMapOf<String, Double>()
                            repeat(mapSize) {
                                val id = input.readInt().toString()
                                val flag = input.readInt().toDouble()
                                matches[id] = flag
                            }
                            finds[filter] = matches
                        }
                    }
                    return ModulesCache(blacklist, finds)
                }
            } catch (e: CacheVersionMismatchException) {
                log.i("Modules cache version mismatch: ${e.message}")
            } catch (e: EOFException) {
                log.e("Modules cache corrupt: ${e.message}")
            } catch (e: IOException) {
                log.e("Failed to read modules cache: ${e.message}")
            }
            runCatching { file.delete() }
            return null
        }
    }
}

internal data class AssetsCache(
    val data: Map<String, Map<String, Double>>,
) {
    fun saveToFile(file: File) {
        val atomic = AtomicFile(file)
        var fos: FileOutputStream? = null
        try {
            fos = atomic.startWrite()
            val out = DataOutputStream(BufferedOutputStream(fos))
            out.writeInt(VERSION)
            out.writeInt(data.size)
            for ((name, mappings) in data) {
                out.writeUTF(name)
                out.writeInt(mappings.size)
                for ((type, id) in mappings) {
                    out.writeUTF(type)
                    out.writeInt(id.toInt())
                }
            }
            out.flush()
            atomic.finishWrite(fos)
        } catch (t: Throwable) {
            if (fos != null) atomic.failWrite(fos)
            throw t
        }
    }

    fun toMap(): Map<String, Any> = mapOf(
        "data" to data.mapValues { (_, m) -> m.toMap() },
        "version" to VERSION,
    )

    companion object {
        const val VERSION = 2

        fun loadFromFileOrNull(file: File, log: Logger): AssetsCache? {
            if (!file.exists() || file.length() <= 0L) return null
            val atomic = AtomicFile(file)
            try {
                DataInputStream(BufferedInputStream(atomic.openRead())).use { input ->
                    val version = input.readInt()
                    if (version != VERSION) throw CacheVersionMismatchException(VERSION, version)

                    val dataSize = input.readInt()
                    val data = mutableMapOf<String, Map<String, Double>>()
                    repeat(dataSize) {
                        val name = input.readUTF()
                        val mappingsSize = input.readInt()
                        val mappings = mutableMapOf<String, Double>()
                        repeat(mappingsSize) {
                            val type = input.readUTF()
                            val id = input.readInt().toDouble()
                            mappings[type] = id
                        }
                        data[name] = mappings
                    }
                    return AssetsCache(data)
                }
            } catch (e: CacheVersionMismatchException) {
                log.i("Assets cache version mismatch: ${e.message}")
            } catch (e: EOFException) {
                log.e("Assets cache corrupt: ${e.message}")
            } catch (e: IOException) {
                log.e("Failed to read assets cache: ${e.message}")
            }
            runCatching { file.delete() }
            return null
        }
    }
}
