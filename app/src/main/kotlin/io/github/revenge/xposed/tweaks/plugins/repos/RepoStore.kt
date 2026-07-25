package io.github.revenge.xposed.tweaks.plugins.repos

import android.util.AtomicFile
import io.github.revenge.Logger
import io.github.revenge.logger
import io.github.revenge.xposed.RevengeJson
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest

/**
 * Reserved identity of the built-in internal repository serving internal plugins.
 * It is not persisted, and not removable.
 */
internal const val INTERNAL_REPO_URL = "revenge://internal"

/** One user-configured repository. */
@Serializable
internal data class UserRepo(
    /** Absolute HTTPS URL of the repository root. Also the repository's identity. */
    val url: String,
    val enabled: Boolean = true,
)

/** repos.json. */
@Serializable
private data class ReposConfig(
    val format: Int = REPOS_CONFIG_FORMAT,
    val repos: List<UserRepo> = emptyList(),
)

private const val REPOS_CONFIG_FORMAT = 1

/**
 * Persistence for the user's repository list and per-repository index caches.
 *
 * Directory layout:
 * ```
 * files/revenge/plugins/repos/
 *   repos.json              # ordered [{ url, enabled }], order is priority
 *   cache/<sha256(url)>/    # per-repo cache
 *     index.json            # last successfully fetched and validated index
 *     etag                  # E-Tag of the cached index, if the server sent one
 * ```
 */
internal object RepoStore {
    private const val REPOS_DIR = "files/revenge/plugins/repos"
    private const val REPOS_FILE = "repos.json"
    private const val CACHE_DIR = "cache"
    private const val INDEX_FILE = "index.json"
    private const val ETAG_FILE = "etag"

    internal val log: Logger = logger("pluginRepos")

    @Volatile
    private var root: File? = null

    @Volatile
    private var repos: List<UserRepo>? = null

    fun ensureLoaded(dataDir: String): List<UserRepo> {
        repos?.let { return it }
        synchronized(this) {
            repos?.let { return it }
            val dir = File(dataDir, REPOS_DIR).apply { if (!exists()) mkdirs() }
            root = dir
            val loaded = loadFromFileOrNull(File(dir, REPOS_FILE)) ?: emptyList()
            repos = loaded
            return loaded
        }
    }

    fun list(): List<UserRepo> = repos ?: error("RepoStore not loaded")

    /**
     * Replaces the whole repository config. Array order is priority order.
     * Caches of removed repositories are dropped.
     */
    @Synchronized
    fun set(entries: List<UserRepo>) {
        val dir = root ?: error("RepoStore not loaded")

        val seen = mutableSetOf<String>()
        for (entry in entries) {
            require(entry.url.startsWith("https://") || entry.url.startsWith("http://")) {
                "Repository URL must be absolute: '${entry.url}'"
            }
            require(entry.url != INTERNAL_REPO_URL) { "Repository URL is reserved: '${entry.url}'" }
            require(seen.add(entry.url)) { "Duplicate repository URL: '${entry.url}'" }
        }

        val previous = repos.orEmpty()
        save(File(dir, REPOS_FILE), entries)
        repos = entries

        // Drop caches of removed repos and null the provenance of plugins pinned to them.
        // The installations stay, treated as sideloaded from now on.
        val removedUrls = previous.map { it.url } - entries.map { it.url }.toSet()
        for (removed in removedUrls) cacheDirFor(removed).deleteRecursively()
        SourcesStore.forgetRepos(removedUrls)
    }

    /** Repository cache directory, keyed by the SHA-256 of the repository URL. */
    fun cacheDirFor(url: String): File {
        val dir = root ?: error("RepoStore not loaded")
        return File(File(dir, CACHE_DIR), sha256Hex(url.toByteArray()))
    }

    fun cachedIndexFile(url: String): File = File(cacheDirFor(url), INDEX_FILE)

    fun cachedETagFile(url: String): File = File(cacheDirFor(url), ETAG_FILE)

    /** The last successfully fetched index of [url], parsed + sanitized, or `null` if none/invalid. */
    fun cachedIndex(url: String): RepoIndex? {
        val file = cachedIndexFile(url)
        if (!file.isFile) return null
        return try {
            parseRepoIndex(file.readText(), url, log)
        } catch (e: Exception) {
            log.e("Failed to read cached index for '$url': ${e.message}")
            null
        }
    }

    private fun loadFromFileOrNull(file: File): List<UserRepo>? {
        if (!file.isFile || file.length() <= 0L) return null
        return try {
            val config = RevengeJson.decodeFromString<ReposConfig>(file.readText())
            require(config.format == REPOS_CONFIG_FORMAT) {
                "Unsupported repos config format ${config.format} (supported: $REPOS_CONFIG_FORMAT)"
            }
            config.repos
        } catch (e: Exception) {
            log.e("Failed to read repos config: ${e.message}")
            runCatching {
                file.renameTo(File(file.parentFile, "${file.name}.corrupt.${System.currentTimeMillis()}"))
            }.onFailure { file.delete() }
            null
        }
    }

    private fun save(file: File, entries: List<UserRepo>) {
        val atomic = AtomicFile(file)
        var fos: FileOutputStream? = null
        try {
            fos = atomic.startWrite()
            fos.write(RevengeJson.encodeToString(ReposConfig(repos = entries)).toByteArray())
            atomic.finishWrite(fos)
        } catch (t: Throwable) {
            if (fos != null) atomic.failWrite(fos)
            throw IOException("Failed to save repos config", t)
        }
    }
}

internal fun sha256Hex(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
