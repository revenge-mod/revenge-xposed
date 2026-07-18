package io.github.revenge.xposed.tweaks.plugins.repos

import android.util.AtomicFile
import io.github.revenge.xposed.RevengeJson
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * Where an installed plugin came from.
 *
 * - `repo == null`: Sideloaded. Never offered repo updates.
 * - `repo != null`: Installed from that repository. Updates must be fetched from it.
 */
@Serializable
internal data class PluginSource(
    val repo: String? = null,
    /** The channel followed for updates (`latest` unless the user opted into another). */
    val channel: String = REPO_CHANNEL_LATEST,
)

/** sources.json. */
@Serializable
private data class SourcesConfig(
    val format: Int = SOURCES_CONFIG_FORMAT,
    val sources: Map<String, PluginSource> = emptyMap(),
)

private const val SOURCES_CONFIG_FORMAT = 1

/**
 * Persistence for plugin provenance keyed by plugin ID, `files/revenge/plugins/repos/sources.json`.
 */
internal object SourcesStore {
    private const val REPOS_DIR = "files/revenge/plugins/repos"
    private const val SOURCES_FILE = "sources.json"

    @Volatile
    private var file: File? = null

    @Volatile
    private var sources: Map<String, PluginSource>? = null

    fun ensureLoaded(dataDir: String): Map<String, PluginSource> {
        sources?.let { return it }
        synchronized(this) {
            sources?.let { return it }
            val dir = File(dataDir, REPOS_DIR).apply { if (!exists()) mkdirs() }
            val f = File(dir, SOURCES_FILE)
            file = f
            val loaded = loadFromFileOrNull(f) ?: emptyMap()
            sources = loaded
            return loaded
        }
    }

    fun all(): Map<String, PluginSource> = sources ?: error("SourcesStore not loaded")

    operator fun get(pluginId: String): PluginSource? = all()[pluginId]

    @Synchronized
    fun set(pluginId: String, source: PluginSource) {
        val updated = all() + (pluginId to source)
        persist(updated)
    }

    @Synchronized
    fun remove(pluginId: String) {
        val current = all()
        if (pluginId !in current) return
        persist(current - pluginId)
    }

    /**
     * Removes the provenance and nulls each of every plugin pinned to one of [repoUrls].
     */
    @Synchronized
    fun forgetRepos(repoUrls: Collection<String>) {
        if (repoUrls.isEmpty()) return
        val current = all()
        val updated = current.mapValues { (_, source) ->
            if (source.repo in repoUrls) PluginSource(repo = null, channel = source.channel) else source
        }
        if (updated != current) persist(updated)
    }

    private fun persist(updated: Map<String, PluginSource>) {
        val f = file ?: error("SourcesStore not loaded")
        val atomic = AtomicFile(f)
        var fos: FileOutputStream? = null
        try {
            fos = atomic.startWrite()
            fos.write(RevengeJson.encodeToString(SourcesConfig(sources = updated)).toByteArray())
            atomic.finishWrite(fos)
        } catch (t: Throwable) {
            if (fos != null) atomic.failWrite(fos)
            throw IOException("Failed to save plugin sources", t)
        }
        sources = updated
    }

    private fun loadFromFileOrNull(f: File): Map<String, PluginSource>? {
        if (!f.isFile || f.length() <= 0L) return null
        return try {
            val config = RevengeJson.decodeFromString<SourcesConfig>(f.readText())
            require(config.format == SOURCES_CONFIG_FORMAT) {
                "Unsupported sources config format ${config.format} (supported: $SOURCES_CONFIG_FORMAT)"
            }
            config.sources
        } catch (e: Exception) {
            RepoStore.log.e("Failed to read plugin sources: ${e.message}")
            runCatching {
                f.renameTo(File(f.parentFile, "${f.name}-${System.currentTimeMillis()}.bak"))
            }.onFailure { f.delete() }
            null
        }
    }
}
