package io.github.revenge.xposed.tweaks.plugins.repos

import android.util.AtomicFile
import io.github.revenge.xposed.RevengeJson
import io.github.revenge.xposed.tweaks.plugins.PluginErrorCodes
import io.github.revenge.xposed.tweaks.plugins.PluginSystemError
import kotlinx.serialization.Serializable
import java.io.File
import java.io.FileOutputStream

/**
 * Where an installed plugin came from, and how it should be updated.
 *
 * - `repo == null`: Sideloaded. No repo updates.
 * - `repo != null`: Installed from that repository. Updates are fetched from it.
 */
@Serializable
internal data class PluginSource(
    val repo: String? = null,
    /** The channel followed for updates (`latest` unless the user opted into another). */
    val channel: String = REPO_CHANNEL_LATEST,
    /** Keep the installed version, skip update checks, and refuse to update it to satisfy something else. */
    val held: Boolean = false,
)

/** sources.json. */
@Serializable
private data class SourcesConfig(
    val format: Int = SOURCES_CONFIG_FORMAT,
    val sources: Map<String, PluginSource> = emptyMap(),
)

private const val SOURCES_CONFIG_FORMAT = 1

/** Persistence for plugin provenance keyed by plugin ID, `files/revenge/plugins/repos/sources.json`. */
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

    /** Records where [pluginId] was just installed from, keeping every other configuration intact. */
    @Synchronized
    fun record(pluginId: String, repo: String?, channel: String) {
        val existing = all()[pluginId]
        set(pluginId, existing?.copy(repo = repo, channel = channel) ?: PluginSource(repo, channel))
    }

    /** Holds [pluginId] at its installed version, or resumes following its channel. Returns the new record. */
    @Synchronized
    fun setHeld(pluginId: String, held: Boolean): PluginSource {
        val updated = (all()[pluginId] ?: PluginSource()).copy(held = held)
        set(pluginId, updated)
        return updated
    }

    @Synchronized
    fun remove(pluginId: String) {
        val current = all()
        if (pluginId !in current) return
        persist(current - pluginId)
    }

    /** Removes provenance and clears the repo field for every plugin pinned to one of [repoUrls]. */
    @Synchronized
    fun forgetRepos(repoUrls: Collection<String>) {
        if (repoUrls.isEmpty()) return
        val current = all()
        val updated = current.mapValues { (_, source) ->
            if (source.repo in repoUrls) source.copy(repo = null) else source
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
            throw PluginSystemError(PluginErrorCodes.STORAGE_FAILED, "Failed to save plugin sources", t)
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
