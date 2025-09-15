package io.github.revenge.xposed.modules

import android.app.Activity
import android.util.AtomicFile
import io.github.revenge.xposed.Module
import io.github.revenge.xposed.Utils.Log
import io.github.revenge.xposed.modules.bridge.BridgeModule
import java.io.*

class CacheModule : Module() {
    private val CACHE_DIR = "revenge"

    private val MODULES_CACHE_PREFIX = "modules"
    private val ASSETS_CACHE_PREFIX = "assets"

    private lateinit var modulesCache: ModulesCache
    private lateinit var assetsCache: AssetsCache

    override fun onActivity(activity: Activity) = with(activity) {
        val revengeCacheDir = File(cacheDir, CACHE_DIR).apply { asDir() }
        val (_, _, _, versionCode) = getAppInfo()

        val modulesCacheFile =
            File(
                revengeCacheDir,
                "$MODULES_CACHE_PREFIX.$versionCode"
            ).apply { asFile() }
        val assetsCacheFile =
            File(
                revengeCacheDir,
                "$ASSETS_CACHE_PREFIX.$versionCode"
            ).apply { asFile() }

        ModulesCache.loadFromFileOrNull(modulesCacheFile)?.let { modulesCache = it }
        AssetsCache.loadFromFileOrNull(assetsCacheFile)?.let { assetsCache = it }

        BridgeModule.registerMethod("revenge.caches.modules.read") {
            if (::modulesCache.isInitialized) modulesCache.toMap() else null
        }

        BridgeModule.registerMethod("revenge.caches.modules.write") {
            val (blacklist, finds) = it
            @Suppress("UNCHECKED_CAST")
            modulesCache =
                ModulesCache(
                    blacklist as ArrayList<Double>,
                    finds as HashMap<String, HashMap<String, Double>?>
                )
                    .apply { saveToFile(modulesCacheFile) }
            Log.i("Modules cache saved: ${modulesCacheFile.absolutePath}")
        }

        BridgeModule.registerMethod("revenge.caches.assets.read") {
            if (::assetsCache.isInitialized) assetsCache.toMap() else null
        }


        BridgeModule.registerMethod("revenge.caches.assets.write") {
            val (data) = it
            @Suppress("UNCHECKED_CAST")
            assetsCache =
                AssetsCache(data as HashMap<String, HashMap<String, Double>>)
                    .apply { saveToFile(assetsCacheFile) }
            Log.i("Assets cache saved: ${assetsCacheFile.absolutePath}")
        }

        return@with
    }
}

private class CacheVersionMismatchException(val expected: Int, val actual: Int) :
    Throwable("Expected cache version: $expected, but got version: $actual")

data class ModulesCache(
    val blacklist: List<Double>,
    val finds: Map<String, Map<String, Double>?>
) {
    fun saveToFile(file: File) {
        val atomic = AtomicFile(file)
        var fos: FileOutputStream? = null
        try {
            fos = atomic.startWrite()
            val out = DataOutputStream(BufferedOutputStream(fos))

            out.writeInt(VERSION)

            // Write blacklist
            out.writeInt(blacklist.size)
            for (num in blacklist) out.writeInt(num.toInt())

            // Write finds
            out.writeInt(finds.size)
            for ((filter, matches) in finds) {
                out.writeUTF(filter) // replaces writeUTF
                if (matches == null) {
                    out.writeBoolean(false) // null marker
                } else {
                    out.writeBoolean(true) // not null
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
        val findsMap =
            finds.mapValues { (_, matches) -> matches?.map { (k, v) -> k to v }?.toMap() }

        return mapOf("blacklist" to blacklist, "finds" to findsMap, "version" to VERSION)
    }

    companion object {
        val VERSION = 3

        fun loadFromFileOrNull(file: File): ModulesCache? {
            if (!file.exists() || file.length() <= 0L) return null
            val atomic = AtomicFile(file)
            try {
                DataInputStream(BufferedInputStream(atomic.openRead())).use { input ->
                    val version = input.readInt()
                    if (version != VERSION)
                        throw CacheVersionMismatchException(VERSION, version)

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
                Log.i("Modules cache version mismatch: ${e.message}")
            } catch (e: EOFException) {
                Log.e("Modules cache corrupt: ${e.message}")
            } catch (e: IOException) {
                Log.e("Failed to read modules cache: ${e.message}")
            }
            runCatching { file.delete() }
            return null
        }
    }
}

data class AssetsCache(
    val data: Map<String, Map<String, Double>>
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

    fun toMap(): Map<String, Any> {
        return mapOf(
            "data" to data.mapValues { (_, mappings) ->
                mappings.map { (type, id) -> type to id }.toMap()
            },
            "version" to VERSION
        )
    }

    companion object {
        val VERSION = 2

        fun loadFromFileOrNull(file: File): AssetsCache? {
            if (!file.exists() || file.length() <= 0L) return null
            val atomic = AtomicFile(file)
            try {
                DataInputStream(BufferedInputStream(atomic.openRead())).use { input ->
                    val version = input.readInt()
                    if (version != VERSION)
                        throw CacheVersionMismatchException(VERSION, version)

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
                Log.i("Assets cache version mismatch: ${e.message}")
            } catch (e: EOFException) {
                Log.e("Assets cache corrupt: ${e.message}")
            } catch (e: IOException) {
                Log.e("Failed to read assets cache: ${e.message}")
            }
            runCatching { file.delete() }
            return null
        }
    }
}