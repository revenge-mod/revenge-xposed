package io.github.revenge.xposed.tweaks.plugins

import android.os.Build
import io.github.revenge.Logger
import io.github.revenge.bridge.asDelegate
import io.github.revenge.logger
import io.github.revenge.xposed.ensureDir
import io.github.revenge.xposed.tweak
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.*
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Plugin states + `revenge.plugins.states.*` bridge methods.
 *
 * A slot is a named set of plugin flags:
 * - [PluginStatesStore.activeSlotId]: the slot the user chose. The UI reads and edits it.
 * - [PluginStatesStore.bootSlotId]: the slot this boot runs on. Plugin scopes read and write it.
 *
 * Both slot IDs are the same on a normal boot.
 * A defaults boot resolves [PluginStatesStore.bootSlotId] to [PluginStatesStore.DEFAULTS_SLOT].
 */
val pluginStates by tweak {
    val dataDir = appInfo.dataDir

    /** `states.read(): Record<slotId, Record<pluginId, PluginStates>>` */
    pluginSystemMethod("revenge.plugins.states.read") {
        PluginStatesStore.ensureLoaded(dataDir)
        PluginStatesStore.toJSPayload()
    }

    /** `states.getSlots(): { active, oneShot?, slots }` */
    pluginSystemMethod("revenge.plugins.states.getSlots") {
        PluginStatesStore.ensureLoaded(dataDir)
        buildMap {
            put("active", PluginStatesStore.activeSlotId)
            PluginStatesStore.oneShotSlotId?.let { put("oneShot", it) }
            put("slots", PluginStatesStore.persistentSlotIds(dataDir))
        }
    }

    /**
     * `states.setActiveSlot(slot, oneShot?)`
     *
     * Applies on the next boot.
     */
    pluginSystemMethod("revenge.plugins.states.setActiveSlot") { args ->
        val argv = args.asDelegate()
        val slot by argv.string()
        val oneShot by argv.booleanOrNull()

        PluginStatesStore.setActiveSlot(dataDir, slot, oneShot == true)
        null
    }

    /**
     * `states.update(slot, id, PluginStates): PluginStates`
     *
     * JS reporting flags it changed.
     */
    pluginSystemMethod("revenge.plugins.states.update") { args ->
        val argv = args.asDelegate()
        val slotId by argv.string()
        val pluginId by argv.string()
        val states by argv.hashMap()

        val newFlags = pluginFlagsFromJSPayload(states)
        PluginStatesStore.ensureLoaded(dataDir)
        PluginStatesStore.slot(slotId)?.write(pluginId, newFlags)
        newFlags.toJSPayload()
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

/** Matches `[a-zA-Z0-9._-]`, with no consecutive dots or leading dot. Colons are internal-only. */
private val SLOT_ID_REGEX = Regex("[a-zA-Z0-9._-]+")

internal fun isInternalSlotId(id: String) = id.startsWith(':')

internal fun requireValidSlotId(id: String) {
    if (isInternalSlotId(id)) return
    require(SLOT_ID_REGEX.matches(id) && ".." !in id && !id.startsWith(".")) {
        "Invalid slot ID: $id"
    }
}

/**
 * A named set of plugin flags.
 *
 * Persistent slots are saved as a file under `states/slots/`.
 * Ephemeral slots get discarded once the session ends.
 */
class PluginStateSlot(
    val id: String,
    /** Whether writes reach disk. */
    val persistent: Boolean,
    private val file: File?,
    initial: Map<String, Set<PluginFlags>> = emptyMap(),
    /** Runs after every entry change, so the caller never has to remember to persist. */
    private val onChanged: () -> Unit = {},
) {
    private val flows = HashMap<String, MutableStateFlow<Set<PluginFlags>>>()

    /** Plugin IDs with an entry. A flow alone is not an entry, only [write] makes one. */
    private val present = HashSet<String>()

    init {
        for ((pluginId, flags) in initial) {
            flows[pluginId] = MutableStateFlow(flags)
            present += pluginId
        }
    }

    /**
     * Live flags of a plugin in this slot.
     *
     * If no flags are saved for this plugin, a new empty flow is created without calling [onChanged] for persistence.
     */
    @Synchronized
    fun flagsOf(pluginId: String): MutableStateFlow<Set<PluginFlags>> =
        flows.getOrPut(pluginId) { MutableStateFlow(entryFlags(pluginId) ?: emptySet()) }

    /** Flags of the plugin's entry, or `null` when it has none. */
    @Synchronized
    fun entryFlags(pluginId: String): Set<PluginFlags>? =
        if (pluginId in present) flows[pluginId]?.value else null

    @Synchronized
    fun hasPlugin(pluginId: String): Boolean = pluginId in present

    @Synchronized
    fun isPluginEnabled(pluginId: String): Boolean =
        PluginFlags.ENABLED in (entryFlags(pluginId) ?: return false)

    /** Writes an entry, and pushes to the live flow, so a running plugin sees it. */
    fun write(pluginId: String, flags: Set<PluginFlags>) {
        synchronized(this) {
            flows.getOrPut(pluginId) { MutableStateFlow(flags) }.value = flags
            present += pluginId
        }
        onChanged()
    }

    fun remove(pluginId: String) {
        synchronized(this) {
            present -= pluginId
            flows.remove(pluginId)
        }
        onChanged()
    }

    @Synchronized
    fun snapshot(): Map<String, Set<PluginFlags>> =
        present.associateWith { flows[it]?.value ?: emptySet() }

    /** Persists the entries. No-op for an ephemeral slots. */
    fun save() {
        if (!persistent) return
        val target = file ?: return
        val entries = snapshot()

        val parentDir = target.parentFile ?: return
        parentDir.mkdirs()

        val tmpFile = File(parentDir, "${target.name}.tmp")
        val bakFile = File(parentDir, "${target.name}.bak")

        try {
            DataOutputStream(BufferedOutputStream(FileOutputStream(tmpFile))).use { out ->
                out.writeInt(CURRENT_VERSION)
                out.writeInt(entries.size)
                for ((pluginId, flags) in entries) {
                    out.writeUTF(pluginId)
                    out.writeInt(flags.toBitmask())
                }
                out.flush()
            }

            if (target.exists()) {
                if (!bakFile.exists()) {
                    if (!renameFile(target, bakFile)) {
                        throw IOException("Failed to back up $target to $bakFile")
                    }
                } else {
                    // An old backup existed, overwrite target
                    target.delete()
                }
            }

            if (!renameFile(tmpFile, target)) {
                // Restore backup if tmp failed to replace target
                if (bakFile.exists()) {
                    renameFile(bakFile, target)
                }
                throw IOException("Failed to rename $tmpFile to $target")
            }

            bakFile.delete()

        } catch (t: Throwable) {
            tmpFile.delete()
            throw IOException("Failed to save plugin states slot '$id'", t)
        }
    }

    private fun renameFile(src: File, dst: File): Boolean {
        try {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) error("API not supported")
            Files.move(
                src.toPath(),
                dst.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE
            )
            return true
        } catch (_: Exception) {
            try {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) error("API not supported")
                Files.move(
                    src.toPath(),
                    dst.toPath(),
                    StandardCopyOption.REPLACE_EXISTING
                )
                return true
            } catch (_: Exception) {
                return src.renameTo(dst)
            }
        }
    }

    companion object {
        const val CURRENT_VERSION = 1

        /** Reads a slot file, or returns an empty persistent slot when it is absent or corrupt. */
        fun load(id: String, file: File, log: Logger, onChanged: () -> Unit): PluginStateSlot {
            val entries = readEntriesOrNull(file, log) ?: emptyMap()
            return PluginStateSlot(id, true, file, entries, onChanged)
        }

        private fun readEntriesOrNull(file: File, log: Logger): Map<String, Set<PluginFlags>>? {
            if (!file.exists() || file.length() <= 0L) return null

            try {
                DataInputStream(BufferedInputStream(FileInputStream(file))).use { input ->
                    when (val version = input.readInt()) {
                        1 -> return readV1(input, log)
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

        private fun readV1(input: DataInputStream, log: Logger): Map<String, Set<PluginFlags>> {
            val n = input.readInt()
            val entries = LinkedHashMap<String, Set<PluginFlags>>(n)
            repeat(n) {
                val pluginId = input.readUTF()
                entries[pluginId] = pluginFlagsFromBitmask(input.readInt())
            }
            log.i("Loaded plugin states for ${entries.size} plugins")
            return entries
        }
    }
}

private class UnsupportedPluginStatesVersionException(version: Int) :
    Throwable("Unsupported plugin states version: $version")

object PluginStatesStore {
    /** Default slot. */
    const val PRIMARY_SLOT = "primary"

    /** Runs every plugin on its defaults for one boot (recovery). */
    const val DEFAULTS_SLOT = ":defaults"

    private const val PLUGINS_DIR = "files/revenge/plugins"
    private const val STATES_DIR = "states"
    private const val STORAGE_DIR = "storage"
    private const val SLOTS_DIR = "slots"
    private const val ACTIVE_FILE = "active"
    private const val NEXT_FILE = "next"

    internal val log: Logger = logger("pluginStates")

    private val slots = HashMap<String, PluginStateSlot>()

    /** The slot the user chose. The UI reads and edits it, and plugin storage keys on it. */
    @Volatile
    var activeSlotId: String = PRIMARY_SLOT
        private set

    /** The one-shot slot this boot consumed, or `null`. */
    @Volatile
    var oneShotSlotId: String? = null
        private set

    /** The slot this boot runs on. Immutable after [ensureLoaded]. */
    @Volatile
    var bootSlotId: String = PRIMARY_SLOT
        private set

    @Volatile
    private var loaded = false

    @Volatile
    private var isBatchSaving = false

    /** Whether this boot runs on defaults instead of the slot the user chose. */
    val isDefaultsBoot: Boolean get() = bootSlotId == DEFAULTS_SLOT

    /** The slot the user chose. */
    val active: PluginStateSlot get() = slots.getValue(activeSlotId)

    /** The slot this boot runs on. Same as [active] on a normal boot. */
    val boot: PluginStateSlot get() = slots.getValue(bootSlotId)

    fun slot(slotId: String): PluginStateSlot? = slots[slotId]

    private fun statesDir(dataDir: String): File {
        val plugins = File(dataDir, PLUGINS_DIR)
        val dir = File(plugins, STATES_DIR).apply { ensureDir() }

        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    private fun slotsDir(dataDir: String) = File(statesDir(dataDir), SLOTS_DIR).apply {
        if (!exists()) mkdirs()
    }

    private fun readSlotId(file: File): String? = runCatching {
        file.takeIf { it.isFile }?.readText()?.trim()?.takeIf { it.isNotEmpty() }
    }.getOrNull()

    fun ensureLoaded(dataDir: String) {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            val dir = statesDir(dataDir)

            activeSlotId = readSlotId(File(dir, ACTIVE_FILE))
                ?.takeIf { runCatching { requireValidSlotId(it) }.isSuccess && !isInternalSlotId(it) }
                ?: PRIMARY_SLOT

            // Consumed before any plugin loads, so a boot crash can't trap the user in it.
            val next = File(dir, NEXT_FILE)
            oneShotSlotId = readSlotId(next)?.also {
                next.delete()
                log.i("Booting one-shot into slot '$it'")
            }

            bootSlotId = oneShotSlotId ?: activeSlotId

            slots[activeSlotId] = loadSlot(dataDir, activeSlotId)
            if (bootSlotId != activeSlotId) slots[bootSlotId] = loadSlot(dataDir, bootSlotId)

            loaded = true
        }
    }

    private fun loadSlot(dataDir: String, slotId: String): PluginStateSlot =
        if (isInternalSlotId(slotId)) PluginStateSlot(slotId, persistent = false, file = null)
        else PluginStateSlot.load(slotId, File(slotsDir(dataDir), slotId), log, ::writeNow)

    /** Persistent slots on disk, with [PRIMARY_SLOT] always present. */
    fun persistentSlotIds(dataDir: String): List<String> {
        val onDisk = slotsDir(dataDir).list()?.filter {
            runCatching { requireValidSlotId(it) }.isSuccess && !isInternalSlotId(it)
        }.orEmpty()
        return (listOf(PRIMARY_SLOT) + onDisk).distinct()
    }

    /**
     * Picks the slot for the next boot.
     *
     * @param oneShot Consumed and applied on the next boot only.
     */
    fun setActiveSlot(dataDir: String, slotId: String, oneShot: Boolean) {
        requireValidSlotId(slotId)
        if (isInternalSlotId(slotId) && !oneShot) throw PluginSystemError(
            PluginErrorCodes.NOT_ALLOWED,
            "Slot '$slotId' is internal and can only be set for one boot",
        )

        val dir = statesDir(dataDir)
        File(dir, if (oneShot) NEXT_FILE else ACTIVE_FILE).writeText(slotId)
    }

    /** Drops the plugin's entry from every slot, loaded or not. */
    fun removePluginFromAllSlots(dataDir: String, pluginId: String) {
        ensureLoaded(dataDir)
        batchSave { for (slot in slots.values) slot.remove(pluginId) }

        for (slotId in persistentSlotIds(dataDir)) {
            if (slotId in slots) continue
            val file = File(slotsDir(dataDir), slotId)
            if (!file.isFile) continue

            val slot = PluginStateSlot.load(slotId, file, log) {}
            if (!slot.hasPlugin(pluginId)) continue
            slot.remove(pluginId)
            runCatching { slot.save() }.onFailure {
                log.e("Failed to drop '$pluginId' from slot '$slotId': ${it.message}")
            }
        }
    }

    /** Test: drops the loaded slots so [ensureLoaded] reads from disk again. */
    internal fun resetForTests() {
        synchronized(this) {
            slots.clear()
            activeSlotId = PRIMARY_SLOT
            oneShotSlotId = null
            bootSlotId = PRIMARY_SLOT
            loaded = false
        }
    }

    fun writeNow() {
        if (isBatchSaving) return
        for (slot in slots.values) {
            if (!slot.persistent) continue
            try {
                slot.save()
            } catch (e: Exception) {
                log.e("Failed to save plugin states slot '${slot.id}': ${e.message}")
            }
        }
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

    fun toJSPayload(): Map<String, Any> = slots.mapValues { (_, slot) ->
        slot.snapshot().mapValues { it.value.toJSPayload() }
    }
}
