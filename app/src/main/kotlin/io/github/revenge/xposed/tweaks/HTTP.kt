package io.github.revenge.xposed.tweaks

import io.github.revenge.xposed.RevengeConstants
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.http.*

internal val httpClient by lazy {
    HttpClient(CIO) {
        expectSuccess = false
        install(UserAgent) { agent = RevengeConstants.USER_AGENT }
        install(HttpRedirect) {}
        install(HttpTimeout) {}
    }
}

internal sealed class ETagFetchResult {
    /** A fresh body was fetched. */
    class Fetched(val bytes: ByteArray, val etag: String?) : ETagFetchResult()

    /** The server responded `304 Not Modified`. The cached copy is up-to-date. */
    object NotModified : ETagFetchResult()
}

internal suspend fun HttpClient.getWithETag(
    url: String,
    etag: String?,
    timeoutMillis: Long? = null,
): ETagFetchResult {
    val response = get(url) {
        etag?.let { headers.append(HttpHeaders.IfNoneMatch, it) }
        timeoutMillis?.let { timeout { requestTimeoutMillis = it } }
    }

    return when (response.status) {
        HttpStatusCode.OK -> ETagFetchResult.Fetched(
            bytes = response.body(),
            etag = response.headers[HttpHeaders.ETag]?.takeIf { it.isNotEmpty() },
        )

        HttpStatusCode.NotModified -> ETagFetchResult.NotModified

        else -> throw ResponseException(response, "Received status: ${response.status}")
    }
}
