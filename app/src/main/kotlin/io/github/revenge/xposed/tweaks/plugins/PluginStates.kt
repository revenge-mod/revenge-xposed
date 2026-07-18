package io.github.revenge.xposed.tweaks.plugins

import android.util.AtomicFile
import io.github.revenge.Logger
import io.github.revenge.logger
import io.github.revenge.plugins.PluginManifest
import io.github.revenge.xposed.tweak
import io.github.revenge.xposed.tweaks.bridge.RevengeBridgeRegistry
import java.io.*

/**
 * Plugin-state persistence + exposes `revenge.plugins.states.*` bridge methods.
 */
val pluginStates by tweak {
    // Load eagerly using appInfo.dataDir so the loader tweak (which doesn't need a Context) can read states during plugin construction.
    val dataDir = appInfo.dataDir
    PluginStatesStore.ensureLoaded(dataDir)

    with(RevengeBridgeRegistry) {
        registerMethod("revenge.plugins.states.read") {
            PluginStatesStore.states?.toMap()
        }
    }
}

enum class InternalPluginFlags {
    INTERNAL,
    ESSENTIAL,
    ENABLED_BY_DEFAULT,
    API,
}

/**
 * Lifecycle flags. Flags with bits can be persisted.
 */
enum class PluginFlags(val bit: Int = 0) {
    /** The plugin is enabled. */
    ENABLED(1 shl 0),

    /** The plugin requires a host reload to apply changes. */
    PENDING_RELOAD,

    /** The plugin was enabled *after* the initial load (e.g. user-toggled at runtime). */
    ENABLED_LATE,
}

fun pluginFlagsFromBitmask(mask: Int): Set<PluginFlags> =
    PluginFlags.entries.filterTo(LinkedHashSet()) { (mask and it.bit) != 0 }

fun Iterable<PluginFlags>.toBitmask(): Int {
    var m = 0
    for (f in this) m = m or f.bit
    return m
}

fun Set<PluginFlags>.toJSPayload(): Map<String, Boolean> = mapOf(
    "enabled" to (PluginFlags.ENABLED in this),
    "pendingReload" to (PluginFlags.PENDING_RELOAD in this),
    "enabledLate" to (PluginFlags.ENABLED_LATE in this),
)

/**
 * Persisted to `files/revenge/plugins/states`.
 */
object PluginStatesStore {
    private const val DATA_DIR = "files/revenge/plugins"
    private const val STATES_FILE = "states"

    internal val log: Logger = logger("pluginStates")

    @Volatile
    var states: PluginsStates? = null
        private set

    @Volatile
    private var isBatchSaving = false

    fun ensureLoaded(dataDir: String): PluginsStates {
        states?.let { return it }
        synchronized(this) {
            states?.let { return it }
            val dir = File(dataDir, DATA_DIR).apply { if (!exists()) mkdirs() }
            val file = File(dir, STATES_FILE)
            val loaded = PluginsStates.loadFromFileOrNull(file, log) ?: PluginsStates(file, emptyMap())
            states = loaded
            return loaded
        }
    }

    fun writeNow() {
        if (isBatchSaving) return
        val s = states ?: return
        try {
            s.save()
            log.i("Plugin states saved: ${s.file.absolutePath}")
        } catch (e: Exception) {
            log.e("Failed to save plugin states: ${e.message}")
        }
    }

    fun updatePluginFlags(manifest: PluginManifest, flags: Set<PluginFlags>) {
        val s = states ?: return
        s.setPluginFlags(manifest.id, flags)
        writeNow()
    }

    fun <T> batchSave(block: () -> T): T {
        isBatchSaving = true
        try {
            return block()
        } finally {
            isBatchSaving = false
            writeNow()
        }
    }

    /** Persisted flags for a plugin, or `null` if none were saved. */
    fun loadPluginFlags(pluginId: String): Set<PluginFlags>? {
        val s = states ?: return null
        val savedFlags = s.flags[pluginId]?.toInt() ?: return null
        return pluginFlagsFromBitmask(savedFlags)
    }
}

private class UnsupportedPluginStatesVersionException(version: Int) :
    Throwable("Unsupported plugin states version: $version")

data class PluginsStates(
    val file: File,
    private val flagsData: Map<String, Double>,
) {
    val flags: MutableMap<String, Double> = flagsData.toMutableMap()

    @Synchronized
    fun isPluginEnabled(pluginId: String): Boolean {
        val pf = flags[pluginId]?.toInt() ?: return false
        return (pf and PluginFlags.ENABLED.bit) != 0
    }

    @Synchronized
    fun hasPlugin(pluginId: String): Boolean = flags.containsKey(pluginId)

    @Synchronized
    fun setPluginFlags(pluginId: String, pluginFlags: Iterable<PluginFlags>) {
        flags[pluginId] = pluginFlags.toBitmask().toDouble()
    }

    @Synchronized
    fun removePlugin(pluginId: String) {
        flags.remove(pluginId)
    }

    @Synchronized
    fun save() {
        val atomic = AtomicFile(file)
        var fos: FileOutputStream? = null
        try {
            fos = atomic.startWrite()
            val out = DataOutputStream(BufferedOutputStream(fos))
            out.writeInt(CURRENT_VERSION)
            out.writeInt(flags.size)
            for ((id, f) in flags) {
                out.writeUTF(id)
                out.writeInt(f.toInt())
            }
            out.flush()
            atomic.finishWrite(fos)
        } catch (t: Throwable) {
            if (fos != null) atomic.failWrite(fos)
            throw IOException("Failed to save plugin states", t)
        }
    }

    fun toMap(): Map<String, Any> = mapOf(
        "states" to flags.mapValues { pluginFlagsFromBitmask(it.value.toInt()).toJSPayload() },
    )

    companion object {
        const val CURRENT_VERSION = 1

        fun loadFromFileOrNull(file: File, log: Logger): PluginsStates? {
            if (!file.exists() || file.length() <= 0L) return null
            val atomic = AtomicFile(file)
            try {
                DataInputStream(BufferedInputStream(atomic.openRead())).use { input ->
                    when (val version = input.readInt()) {
                        1 -> return loadV1(input, file, log)
                        else -> throw UnsupportedPluginStatesVersionException(version)
                    }
                }
            } catch (e: UnsupportedPluginStatesVersionException) {
                log.e(e.message ?: "Unsupported plugin states version")
            } catch (e: EOFException) {
                log.e("Plugin states corrupt: ${e.message}")
            } catch (e: IOException) {
                log.e("Failed to read plugin states: ${e.message}")
            } catch (e: Exception) {
                log.e("Unexpected error reading plugin states: ${e.message}")
            }
            runCatching {
                file.renameTo(File(file.parentFile, "${file.name}.corrupt.${System.currentTimeMillis()}"))
            }.onFailure {
                log.e("Failed to rename corrupt states file: ${it.message}")
                file.delete()
            }
            return null
        }

        private fun loadV1(input: DataInputStream, file: File, log: Logger): PluginsStates {
            val n = input.readInt()
            val flags = mutableMapOf<String, Double>()
            repeat(n) {
                val id = input.readUTF()
                val v = input.readInt().toDouble()
                flags[id] = v
            }
            log.i("Loaded plugin states for ${flags.size} plugins")
            return PluginsStates(file, flags)
        }
    }
}
