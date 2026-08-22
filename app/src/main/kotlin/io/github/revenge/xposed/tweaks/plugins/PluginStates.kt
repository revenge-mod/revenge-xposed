package io.github.revenge.xposed.tweaks.plugins

import android.util.AtomicFile
import io.github.revenge.Logger
import io.github.revenge.bridge.asDelegate
import io.github.revenge.logger
import io.github.revenge.xposed.tweak
import java.io.*

/**
 * Plugin-state persistence + exposes `revenge.plugins.states.*` bridge methods.
 *
 * Two things are tracked separately:
 * - saved: what is on disk, and what applies on the next boot. The UI edits this.
 * - session: what is true for the plugins running right now.
 *
 * On a normal boot they are the same thing. During a defaults-only boot the session runs on defaults.
 * Each states have different write and notifier functions.
 */
val pluginStates by tweak {
    val dataDir = appInfo.dataDir

    pluginSystemMethod("revenge.plugins.states.read") {
        PluginStatesStore.ensureLoaded(dataDir).toMap()
    }

    pluginSystemMethod("revenge.plugins.states.requestNextBootDefaultsOnly") {
        PluginStatesStore.requestDefaultsOnlyBoot(dataDir)
    }

    /**
     * `revenge.plugins.states.update(id, PluginStates): PluginStates`
     *
     * Updates the session plugin states.
     */
    pluginSystemMethod("revenge.plugins.states.update") { args ->
        val argv = args.asDelegate()
        val pluginId by argv.string()
        val states by argv.hashMap()

        val newStates = pluginFlagsFromJSPayload(states)
        PluginStatesStore.writeSessionFlags(pluginId, newStates)
        newStates.toJSPayload()
    }
}

/**
 * Lifecycle flags. Flags with bits can be persisted.
 */
enum class PluginFlags(val bit: Int = 0, val jsName: String, val persistAfterDisable: Boolean = false) {
    /** The plugin is enabled. */
    ENABLED(1 shl 0, "enabled"),

    /** The plugin is explicitly enabled by the user. Any optional disablement should not disable this plugin */
    REQUIRED_BY_USER(1 shl 1, "requiredByUser"),

    /** The plugin requires a host reload to apply changes. */
    PENDING_RELOAD(jsName = "pendingReload", persistAfterDisable = true),

    /** The plugin was enabled *after* the initial load (e.g. user-toggled at runtime). */
    STARTED_LATE(jsName = "startedLate"),
}

fun pluginFlagsFromBitmask(mask: Int): Set<PluginFlags> =
    PluginFlags.entries.filterTo(LinkedHashSet()) { (mask and it.bit) != 0 }

fun Iterable<PluginFlags>.toBitmask(): Int {
    var m = 0
    for (f in this) m = m or f.bit
    return m
}

fun Set<PluginFlags>.toJSPayload(): Map<String, Boolean> {
    val map = PluginFlags.entries.associate { it.jsName to (it in this) }
    // Add backwards compatibility here if needed
    return map
}

fun pluginFlagsFromJSPayload(payload: HashMap<String, Any?>): Set<PluginFlags> {
    val set = PluginFlags.entries.filter { payload[it.jsName] == true }.toSet()
    // Add backwards compatibility here if needed
    return set
}

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
     * Only session reads are overlayed. Saved states are still readable/writable, so the user can edit it and fix problems.
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

    fun writeSavedFlags(pluginId: String, flags: Set<PluginFlags>) {
        val s = states ?: return
        s.setPluginFlags(pluginId, flags)
        writeNow()
    }

    /**
     * Copies a running plugin's flags into the saved setup.
     * Does nothing in defaults-only boot, as session flags is different to saved there.
     */
    fun writeSessionFlags(pluginId: String, flags: Set<PluginFlags>) {
        if (defaultsOnly) return
        writeSavedFlags(pluginId, flags)
    }

    fun removeSavedFlags(pluginId: String) {
        val s = states ?: return
        s.removePlugin(pluginId)
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

    /**
     * Flags a plugin starts this boot with, or `null` when nothing applies.
     * Always `null` during a defaults-only boot, so every plugin falls back to its defaults.
     */
    fun bootFlags(pluginId: String): Set<PluginFlags>? {
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

    /** Enabled for this boot. Returns false for everything during a defaults-only boot. */
    @Synchronized
    fun isPluginEnabledThisBoot(pluginId: String): Boolean {
        return !PluginStatesStore.defaultsOnly && isPluginEnabledInSaved(pluginId)
    }

    /** Has an entry for this boot. Returns false for everything during a defaults-only boot. */
    @Synchronized
    fun hasPluginThisBoot(pluginId: String): Boolean {
        return !PluginStatesStore.defaultsOnly && hasPluginInSaved(pluginId)
    }

    /** Enabled in the user's saved setup, ignoring the defaults-only overlay. */
    @Synchronized
    fun isPluginEnabledInSaved(pluginId: String): Boolean {
        val pf = flags[pluginId]?.toInt() ?: return false
        return (pf and PluginFlags.ENABLED.bit) != 0
    }

    /** Has an entry in the user's saved setup, ignoring the defaults-only overlay. */
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

        // Session states. Empty in defaults-only so JS uses its defaults and runs nothing extra.
        put("states", if (PluginStatesStore.defaultsOnly) emptyMap<String, Any>() else saved)

        // Saved states, sent only when it differs from the session,
        // so the UI shows and edits the correct data while the session runs on defaults.
        if (PluginStatesStore.defaultsOnly) put("savedStates", saved)
    }

    companion object {
        const val CURRENT_VERSION = 1

        fun loadFromFileOrNull(file: File, log: Logger): PluginsStates? {
            if (!file.exists() || file.length() <= 0L) return null

            try {
                val atomic = AtomicFile(file)
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
