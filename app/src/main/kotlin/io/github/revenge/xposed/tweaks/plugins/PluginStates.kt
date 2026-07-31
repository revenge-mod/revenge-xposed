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

    with(RevengeBridgeRegistry) {
        registerMethod("revenge.plugins.states.read") {
            PluginStatesStore.ensureLoaded(dataDir).toMap()
        }

        registerMethod("revenge.plugins.states.requestNextBootDefaultsOnly") {
            PluginStatesStore.requestDefaultsOnlyBoot(dataDir)
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
    STARTED_LATE,
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
    // @TODO: (2026-07-26) Remove this in a month's time.
    "enabledLate" to (PluginFlags.STARTED_LATE in this),
    "startedLate" to (PluginFlags.STARTED_LATE in this),
)

/**
 * Persisted to `files/revenge/plugins/states`.
 */
object PluginStatesStore {
    private const val DATA_DIR = "files/revenge/plugins"
    private const val STATES_FILE = "states"
    private const val DEFAULTS_ONLY_MARKER_FILE = ".defaults-only"

    internal val log: Logger = logger("pluginStates")

    @Volatile
    var states: PluginsStates? = null
        private set

    /**
     * Read the states file as if it were empty, so only essential and enabled-by-default plugins run for one boot.
     *
     * Reads are overlayed, writes hit the real states file, so the user can disable the problematic plugin.
     */
    @Volatile
    var defaultsOnly: Boolean = false
        private set

    @Volatile
    private var isBatchSaving = false

    private fun statesDir(dataDir: String) = File(dataDir, DATA_DIR).apply { if (!exists()) mkdirs() }

    fun ensureLoaded(dataDir: String): PluginsStates {
        states?.let { return it }
        synchronized(this) {
            states?.let { return it }
            val dir = statesDir(dataDir)

            val marker = File(dir, DEFAULTS_ONLY_MARKER_FILE)
            if (marker.exists()) {
                defaultsOnly = true
                marker.delete()
                log.i("Booting with default plugins only")
            }

            val file = File(dir, STATES_FILE)
            val loaded = PluginsStates.loadFromFileOrNull(file, log) ?: PluginsStates(file, emptyMap())
            states = loaded
            return loaded
        }
    }

    fun requestDefaultsOnlyBoot(dataDir: String) {
        File(statesDir(dataDir), DEFAULTS_ONLY_MARKER_FILE).writeText("")
    }

    /** Test: drops the cached states so [ensureLoaded] reads from disk again. */
    internal fun resetForTests() {
        synchronized(this) {
            states = null
            defaultsOnly = false
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
        if (defaultsOnly) return null
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
        return !PluginStatesStore.defaultsOnly && isPluginEnabledInSaved(pluginId)
    }

    @Synchronized
    fun hasPlugin(pluginId: String): Boolean {
        return !PluginStatesStore.defaultsOnly && hasPluginInSaved(pluginId)
    }

    /** Enabled in the user's saved setup, ignoring the defaults-only overlay. */
    @Synchronized
    fun isPluginEnabledInSaved(pluginId: String): Boolean {
        val pf = flags[pluginId]?.toInt() ?: return false
        return (pf and PluginFlags.ENABLED.bit) != 0
    }

    /** Saved in the user's setup, ignoring the defaults-only overlay. */
    @Synchronized
    fun hasPluginInSaved(pluginId: String): Boolean = flags.containsKey(pluginId)

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

    fun toMap(): Map<String, Any> = buildMap {
        val saved = flags.mapValues { pluginFlagsFromBitmask(it.value.toInt()).toJSPayload() }

        // Empty in defaults-only so JS uses its defaults and runs nothing extra.
        put("states", if (PluginStatesStore.defaultsOnly) emptyMap<String, Any>() else saved)

        // The real saved states sent only when running defaults-only,
        // so the UI can show and edit what actually applies on the next reload.
        if (PluginStatesStore.defaultsOnly) put("savedStates", saved)
    }

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
