package io.github.revenge.xposed.tweaks.plugins.repos

import android.util.AtomicFile
import androidx.core.util.writeBytes
import io.github.revenge.xposed.ETagFetchResult
import io.github.revenge.xposed.getWithETag
import io.github.revenge.xposed.httpClient

private const val REFRESH_TIMEOUT = 10_000L

/** Name of the index document under the repository root URL. */
private const val INDEX_PATH = "index.json"

/**
 * Refreshes one repository's cached index for a single source.
 * A failed fetch or an invalid index doesn't modify the cache.
 *
 * @return Returns the now-current index.
 * @throws IllegalStateException if the repository is unknown or the cache is unreadable.
 * @throws IllegalArgumentException if the index format is unsupported.
 */
internal suspend fun refreshRepo(url: String): RepoIndex {
    require(RepoStore.list().any { it.url == url }) { "Unknown repository: '$url'" }

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
                ?: throw IllegalStateException("Repository '$url' responded 304 but the cache is unreadable")

        is ETagFetchResult.Fetched -> {
            val index = parseRepoIndex(result.bytes.decodeToString(), url, RepoStore.log)

            RepoStore.cacheDirFor(url).mkdirs()
            AtomicFile(indexFile).writeBytes(result.bytes)
            result.etag?.let(etagFile::writeText) ?: etagFile.delete()

            index
        }
    }
}
