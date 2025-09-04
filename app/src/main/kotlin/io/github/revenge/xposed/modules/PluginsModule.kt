package io.github.revenge.xposed.modules

import android.app.Activity
import android.util.AtomicFile
import io.github.revenge.xposed.Module
import io.github.revenge.xposed.Utils.Log
import io.github.revenge.xposed.modules.bridge.BridgeModule
import java.io.*

class PluginsModule : Module() {
    private val DATA_DIR = "revenge/plugins"
    private val STATES_FILE = "states"

    private lateinit var states: PluginStates

    override fun onActivity(activity: Activity) = with(activity) {
        val dataDir = File(filesDir, DATA_DIR)

        val statesFile = File(
            dataDir,
            STATES_FILE
        ).apply { asFile() }

        BridgeModule.registerMethod("revenge.plugins.states.read") {
            if (::states.isInitialized) states.toMap() else
                PluginStates.loadFromFileOrNull(statesFile)?.let {
                    states = it
                    it.toMap()
                }
        }

        BridgeModule.registerMethod("revenge.plugins.states.write") {
            val (flags) = it
            @Suppress("UNCHECKED_CAST")
            states =
                PluginStates(
                    flags as Map<String, Double>
                )
                    .apply { saveToFile(statesFile) }
            Log.i("Plugin states saved: ${statesFile.absolutePath}")
        }

        return@with
    }
}

private class UnsupportedPluginStatesVersionException(version: Int) :
    Throwable("Unsupported plugin states version: $version")

data class PluginStates(
    val flags: Map<String, Double>
) {
    fun saveToFile(file: File) {
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
            if (fos != null) atomic.failWrite(fos)
            throw t
        }
    }

    fun toMap(): Map<String, Any> = mapOf("flags" to flags)

    companion object {
        const val CURRENT_VERSION = 1

        fun loadFromFileOrNull(file: File): PluginStates? {
            if (!file.exists() || file.length() <= 0L) return null
            val atomic = AtomicFile(file)

            try {
                DataInputStream(BufferedInputStream(atomic.openRead())).use { input ->
                    when (val version = input.readInt()) {
                        1 -> return loadV1FromFileOrNull(input)
                        else -> throw UnsupportedPluginStatesVersionException(version)
                    }
                }
            } catch (e: UnsupportedPluginStatesVersionException) {
                Log.i(e.message!!)
            } catch (e: EOFException) {
                Log.e("Plugin states corrupt: ${e.message}")
            } catch (e: IOException) {
                Log.e("Failed to read plugin states: ${e.message}")
            }

            runCatching { file.delete() }
            return null
        }

        private fun loadV1FromFileOrNull(input: DataInputStream): PluginStates {
            val flagsSize = input.readInt()
            val flags = mutableMapOf<String, Double>()

            repeat(flagsSize) {
                flags[input.readUTF()] = input.readInt().toDouble()
            }

            return PluginStates(flags)
        }
    }
}