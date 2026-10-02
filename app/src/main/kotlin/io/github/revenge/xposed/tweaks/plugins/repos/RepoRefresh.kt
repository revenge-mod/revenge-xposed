package io.github.revenge.xposed.tweaks.plugins.repos

import android.util.AtomicFile
import androidx.core.util.writeBytes
import io.github.revenge.xposed.ETagFetchResult
import io.github.revenge.xposed.getWithETag
import io.github.revenge.xposed.httpClient
import io.github.revenge.xposed.tweaks.plugins.PluginErrorCodes
import io.github.revenge.xposed.tweaks.plugins.PluginSystemError

private const val REFRESH_TIMEOUT = 10_000L

/** Name of the index document under the repository root URL. */
private const val INDEX_PATH = "index.json"

/**
 * Refreshes one repository's cached index for a single source. A failed fetch or invalid index won't modify the cache.
 *
 * @return Returns the new index.
 * @throws PluginSystemError if the repository is unknown, the cache is unreadable, or the index format is unsupported.
 */
internal suspend fun refreshRepo(url: String): RepoIndex {
    if (RepoStore.list().none { it.url == url }) throw PluginSystemError(
        PluginErrorCodes.NOT_FOUND,
        "Unknown repository: '$url'"
    )

    val indexUrl = url.trimEnd('/') + "/" + INDEX_PATH
    val indexFile = RepoStore.cachedIndexFile(url)
    val etagFile = RepoStore.cachedETagFile(url)

    val result = httpClient.getWithETag(
        url = indexUrl,
        etag = if (etagFile.isFile && indexFile.isFile) etagFile.readText() else null,
        timeoutMillis = REFRESH_TIMEOUT,
    )

    return when (result) {
        ETagFetchResult.NotModified ->
            RepoStore.cachedIndex(url)
                ?: throw PluginSystemError(
                    PluginErrorCodes.STORAGE_FAILED,
                    "Repository '$url' responded 304 but the cache is unreadable",
                )

        is ETagFetchResult.Fetched -> {
            val index = parseRepoIndex(result.bytes.decodeToString(), url, RepoStore.log)

            RepoStore.cacheDirFor(url).mkdirs()
            AtomicFile(indexFile).writeBytes(result.bytes)
            result.etag?.let(etagFile::writeText) ?: etagFile.delete()

            index
        }
    }
}
