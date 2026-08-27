package io.github.revenge.xposed

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.compression.*
import io.ktor.client.request.*
import io.ktor.http.*

internal val httpClient by lazy {
    HttpClient(CIO) {
        expectSuccess = false
        install(UserAgent) { agent = RevengeConstants.USER_AGENT }
        install(HttpRedirect) {}
        install(HttpTimeout) {}

        // Ask for the bundle compressed, and unpack it here.
        //
        // Without this Ktor sends no Accept-Encoding at all, so the server has no choice but to
        // send the bundle raw — and the bundle is a megabytes-large text file that compresses to
        // about a sixth of itself. Measured on the real asset: 3.0 MB raw, 0.41 MB brotli.
        //
        // The arithmetic is the whole point. The download budget below is five seconds when a copy
        // already exists, which at 3.0 MB demands 588 KB/s sustained — a bar most phones on mobile
        // data do not clear, so the update simply never lands and everybody stays on the build they
        // have. Compressed, the same five seconds needs 82 KB/s.
        //
        // Safe in both directions: a server that does not compress keeps sending exactly what it
        // sent before, because compression only ever happens when the client asks for it.
        install(ContentEncoding) {
            gzip()
            deflate()
        }
    }
}

internal sealed class ETagFetchResult {
    /** A fresh body was fetched. */
    class Fetched(val bytes: ByteArray, val etag: String?, val renewedToken: String? = null) : ETagFetchResult()

    /** The server responded `304 Not Modified`. The cached copy is up-to-date. */
    object NotModified : ETagFetchResult()

    /**
     * The server declined to serve this account: they left the Esharq server, or the install is
     * no longer valid. Not an exception — it is an answer, and a different one from "the network
     * failed". A failed network keeps the cached bundle; this deletes it.
     */
    class Refused(val refusal: EsharqRefusal) : ETagFetchResult()
}

internal suspend fun HttpClient.getWithETag(
    url: String,
    etag: String?,
    timeoutMillis: Long? = null,
    bearer: String? = null,
): ETagFetchResult {
    val response = get(url) {
        etag?.let { headers.append(HttpHeaders.IfNoneMatch, it) }
        bearer?.let { headers.append(HttpHeaders.Authorization, "Bearer $it") }
        timeoutMillis?.let { timeout { requestTimeoutMillis = it } }
    }

    return when (response.status) {
        HttpStatusCode.OK -> ETagFetchResult.Fetched(
            bytes = response.body(),
            etag = response.headers[HttpHeaders.ETag]?.takeIf { it.isNotEmpty() },
            // The receipt baked into the APK expires, and an APK cannot rewrite itself. The server
            // hands back a fresh one on every authorised call, so an install that keeps checking in
            // never has to be run through the installer again — one that goes quiet does.
            renewedToken = response.headers["X-Esharq-Token"]?.takeIf { it.isNotEmpty() },
        )

        HttpStatusCode.NotModified -> ETagFetchResult.NotModified

        HttpStatusCode.Unauthorized, HttpStatusCode.Forbidden ->
            ETagFetchResult.Refused(EsharqRefusal.parse(response.body()))

        else -> throw ResponseException(response, "Received status: ${response.status}")
    }
}