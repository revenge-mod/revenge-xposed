package io.github.revenge.xposed.modules.plugins

import android.util.AtomicFile
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam
import io.github.revenge.xposed.Module
import io.github.revenge.xposed.Utils.Log
import io.github.revenge.xposed.modules.bridge.BridgeModule
import io.github.revenge.xposed.modules.bridge.asDelegate
import io.github.revenge.xposed.plugins.Plugin
import java.io.*

enum class PluginFlags(val value: Int) {
    ENABLED(1 shl 0),
    RELOAD_REQUIRED(1 shl 1),
    ERRORED(1 shl 2),
    ENABLED_LATE(1 shl 3),
}

enum class InternalPluginFlags(val value: Int) {
    INTERNAL(1 shl 0),
    ESSENTIAL(1 shl 1),
}

/**
 * Flags that should be persisted across restarts.
 */
val PERSISTENT_PLUGIN_FLAGS = setOf(PluginFlags.ENABLED)

/**
 * Manages the states of plugins, allowing reading and writing of plugin states to a file.
 *
 * ## Methods
 *
 * - `revenge.plugins.states.read(): { flags: { [pluginId: string]: number } }`
 * - Reads the current plugin states from the file and returns them as a map.
 *
 * - `revenge.plugins.states.setPluginFlags(pluginId: string, pluginFlags: number): void`
 * - Sets the flags for a specific plugin identified by `pluginId`.
 *
 * - `revenge.plugins.states.getConstants(): { PluginFlags: { [name: string]: number }, InternalPluginFlags: { [name: string]: number } }`
 * - Returns the constants for `PluginFlags` and `InternalPluginFlags`.
 *
 * - `revenge.plugins.states.write(flags: { [pluginId: string]: number }): void`
 * - Writes the provided plugin states to the file. (Deprecated)
 */
object PluginsStatesModule : Module() {
    private const val DATA_DIR = "files/revenge/plugins"
    private const val STATES_FILE = "states"

    lateinit var states: PluginsStates
    private var isBatchSaving = false

    override fun onLoad(packageParam: LoadPackageParam) = with(packageParam) {
        BridgeModule.registerMethod("revenge.plugins.states.getConstants") {
            mapOf(
                "PluginFlags" to PluginFlags.entries.associate { it.name to it.value },
                "InternalPluginFlags" to InternalPluginFlags.entries.associate { it.name to it.value }
            )
        }

        BridgeModule.registerMethod("revenge.plugins.states.read") {
            readStatesOrNull()?.toMap()
        }

        BridgeModule.registerMethod("revenge.plugins.states.setPluginFlags") {
            val args = it.asDelegate()
            val pluginId by args.string()
            val pluginFlags by args.int()

            readStatesOrNull()?.apply {
                setPluginFlags(
                    pluginId,
                    PluginFlags.entries.filter { flag -> (pluginFlags and flag.value) != 0 }.toSet()
                )
                write()
            }
        }

        BridgeModule.registerMethod("revenge.plugins.states.write") {
            Log.w("Method revenge.plugins.states.write is deprecated, is JS updated?")

            val args = it.asDelegate()
            val flags by args.hashMap<String, Double>()

            for ((pluginId, pluginFlags) in flags) {
                states.flags[pluginId] = pluginFlags
            }

            states.write()
        }
    }

    fun LoadPackageParam.readStatesOrNull(): PluginsStates? {
        return if (::states.isInitialized) states else {
            val dataDir = File(appInfo.dataDir, DATA_DIR).apply {
                if (!exists()) mkdirs()
            }
            val statesFile = File(dataDir, STATES_FILE)

            PluginsStates.loadFromFileOrNull(statesFile)?.also {
                states = it
            } ?: PluginsStates(statesFile, emptyMap()).also {
                states = it
            }
        }
    }

    fun PluginsStates.write() {
        if (isBatchSaving) return

        try {
            this.save()
            Log.i("Plugin states saved: ${this.file.absolutePath}")
        } catch (e: Exception) {
            Log.e("Failed to save plugin states: ${e.message}")
        }
    }

    fun updatePluginFlags(plugin: Plugin) {
        plugin.flags.filter { it in PERSISTENT_PLUGIN_FLAGS }.let { persistentFlags ->
            states.setPluginFlags(plugin.manifest.id, persistentFlags)
        }

        states.write()
    }

    /**
     * Executes a block with batch saving enabled to prevent multiple writes.
     * Useful when loading/updating multiple plugins at once.
     */
    fun <T> batchSave(block: () -> T): T {
        isBatchSaving = true
        try {
            return block()
        } finally {
            isBatchSaving = false
            states.write()
        }
    }

    /**
     * Load saved flags into a plugin instance.
     */
    fun loadPluginFlags(plugin: Plugin) {
        val savedFlags = states.flags[plugin.manifest.id]?.toInt() ?: return
        val flagsToSet = PluginFlags.entries.filter { (savedFlags and it.value) != 0 }.toSet()
        plugin.flags = flagsToSet
    }
}

private class UnsupportedPluginStatesVersionException(version: Int) :
    Throwable("Unsupported plugin states version: $version")

data class PluginsStates(
    val file: File,
    private val flagsData: Map<String, Double>
) {
    val flags: MutableMap<String, Double> = flagsData.toMutableMap()

    @Synchronized
    fun isPluginEnabled(pluginId: String): Boolean {
        val pluginFlags = flags[pluginId]?.toInt() ?: return false
        return (pluginFlags and PluginFlags.ENABLED.value) != 0
    }

    @Synchronized
    fun setPluginFlags(pluginId: String, pluginFlags: Iterable<PluginFlags>) {
        var flagsInt = 0
        for (flag in pluginFlags) {
            flagsInt = flagsInt or flag.value
        }

        flags[pluginId] = flagsInt.toDouble()
    }

    @Synchronized
    fun save() {
        val atomic = AtomicFile(file)
        var fos: FileOutputStream? = null
        try {
            fos = atomic.startWrite()
            val out = DataOutputStream(BufferedOutputStream(fos))

            out.writeInt(CURRENT_VERSION)

            // Write flags
            out.writeInt(flags.size)
            for ((id, flags) in flags) {
                out.writeUTF(id)
                out.writeInt(flags.toInt())
            }

            out.flush()
            atomic.finishWrite(fos)
        } catch (t: Throwable) {
            if (fos != null) {
                atomic.failWrite(fos)
            }
            throw IOException("Failed to save plugin states", t)
        }
    }

    fun toMap(): Map<String, Any> = mapOf("flags" to flags.toMap())

    companion object {
        const val CURRENT_VERSION = 1

        fun loadFromFileOrNull(file: File): PluginsStates? {
            if (!file.exists() || file.length() <= 0L) return null
            val atomic = AtomicFile(file)

            try {
                DataInputStream(BufferedInputStream(atomic.openRead())).use { input ->
                    when (val version = input.readInt()) {
                        1 -> return loadV1FromFileOrNull(input, file)
                        else -> throw UnsupportedPluginStatesVersionException(version)
                    }
                }
            } catch (e: UnsupportedPluginStatesVersionException) {
                Log.e(e.message!!)
            } catch (e: EOFException) {
                Log.e("Plugin states corrupt: ${e.message}")
            } catch (e: IOException) {
                Log.e("Failed to read plugin states: ${e.message}")
            } catch (e: Exception) {
                Log.e("Unexpected error reading plugin states: ${e.message}")
            }

            runCatching {
                file.renameTo(File(file.parentFile, "${file.name}.corrupt.${System.currentTimeMillis()}"))
            }.onFailure {
                Log.e("Failed to rename corrupt states file: ${it.message}")
                file.delete()
            }

            return null
        }

        private fun loadV1FromFileOrNull(input: DataInputStream, file: File): PluginsStates {
            val flagsSize = input.readInt()
            val flags = mutableMapOf<String, Double>()

            repeat(flagsSize) {
                val id = input.readUTF()
                val flagValue = input.readInt().toDouble()
                flags[id] = flagValue
            }

            Log.i("Loaded plugin states for ${flags.size} plugins")
            return PluginsStates(file, flags)
        }
    }
}