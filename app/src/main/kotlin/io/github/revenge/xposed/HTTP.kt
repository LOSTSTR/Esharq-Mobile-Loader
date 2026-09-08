package io.github.revenge.xposed

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.compression.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
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

    /**
     * The server pointed somewhere else instead of sending the file.
     *
     * 🔴 The bundle is three megabytes, and routing it through the server meant every download of it
     * left that server's own network — which is metered, and which pauses the whole site when the
     * allowance runs out. No grant, no bundle, no working client for anybody, over a file that was
     * already sitting on a host built to serve exactly this.
     *
     * So the server hands over a short-lived signed link and we fetch it ourselves. The link is only
     * ever issued after membership has been checked, and it carries no credential of ours.
     */
    class Located(val url: String, val etag: String?) : ETagFetchResult()
}

/** What the server answers with when it hands over a link rather than the file. */
@kotlinx.serialization.Serializable
internal data class BundleLocation(
    val ok: Boolean = false,
    val url: String = "",
    val etag: String? = null
)

/**
 * The server answered, badly.
 *
 * 🔴 It carries the server's own sentence, because that sentence used to be thrown away. Only 401
 * and 403 had their bodies read; every other status raised Ktor's generic error, so a 503 put
 * "Bad response: HttpResponse[.../grant, 503 Service Unavailable]" in front of an Arabic-speaking
 * user while the words explaining it sat unread in the response body.
 *
 * An exception rather than a `Refused`, deliberately: a refusal deletes the install, and a server
 * that is briefly unwell must never do that.
 */
class EsharqServerException(
    val status: Int,
    val refusal: EsharqRefusal?
) : Exception(refusal?.message?.takeIf { it.isNotBlank() } ?: "Server returned $status")

/**
 * A 200, which is now two different things.
 *
 * The server sends the file to a client that did not ask for a link, and a small JSON object naming
 * where the file is to a client that did. Told apart by the content type rather than by what we
 * asked for, so a server that has not been updated yet still answers usefully.
 */
private suspend fun parseOk(response: HttpResponse, preferLocation: Boolean): ETagFetchResult {
    val renewedToken = response.headers["X-Esharq-Token"]?.takeIf { it.isNotEmpty() }
    val etag = response.headers[HttpHeaders.ETag]?.takeIf { it.isNotEmpty() }
    val isJson = response.contentType()?.match(ContentType.Application.Json) == true

    if (preferLocation && isJson) {
        val located = runCatching { RevengeJson.decodeFromString<BundleLocation>(response.body()) }.getOrNull()
        if (located != null && located.ok && located.url.isNotBlank()) {
            return ETagFetchResult.Located(located.url, located.etag ?: etag)
        }
    }

    return ETagFetchResult.Fetched(
        bytes = response.body(),
        etag = etag,
        // The receipt baked into the APK expires, and an APK cannot rewrite itself. The server hands
        // back a fresh one on every authorised call, so an install that keeps checking in never has
        // to be run through the installer again — one that goes quiet does.
        renewedToken = renewedToken,
    )
}

/**
 * Fetch a file from a link the server handed us, carrying nothing of ours.
 *
 * 🔴 No bearer, deliberately. The link points at another host, and Ktor forwards headers across a
 * redirect — sending our receipt there would hand a credential to a party with no business holding
 * one. It is not needed either: the link is itself the permission, and it was only issued after
 * membership had been checked.
 */
internal suspend fun HttpClient.downloadFrom(url: String, timeoutMillis: Long): ByteArray =
    get(url) {
        timeout { requestTimeoutMillis = timeoutMillis }
    }.body()

internal suspend fun HttpClient.getWithETag(
    url: String,
    /** Ask the server for a link to the file rather than the file itself. */
    preferLocation: Boolean = false,
    etag: String?,
    timeoutMillis: Long? = null,
    bearer: String? = null,
): ETagFetchResult {
    val response = get(url) {
        etag?.let { headers.append(HttpHeaders.IfNoneMatch, it) }
        bearer?.let { headers.append(HttpHeaders.Authorization, "Bearer $it") }
        if (preferLocation) headers.append("X-Esharq-Location", "1")
        timeoutMillis?.let { timeout { requestTimeoutMillis = it } }
    }

    return when (response.status) {
        HttpStatusCode.OK -> parseOk(response, preferLocation)
        HttpStatusCode.NotModified -> ETagFetchResult.NotModified

        HttpStatusCode.Unauthorized, HttpStatusCode.Forbidden ->
            ETagFetchResult.Refused(EsharqRefusal.parse(response.body()))

        // 🔴 The server writes a sentence for exactly this case, in both languages, and it was
        // never shown to anybody.
        //
        // Only 401 and 403 had their bodies read, so a 503 fell through to the throw below and the
        // user was handed Ktor's own English text — "Bad response: HttpResponse[…/grant, 503 Service
        // Unavailable]" — on an Arabic-first client. The words that explain what happened and what
        // to do about it sat in the response, unread.
        //
        // Carried on the exception rather than turned into a refusal, because the two mean opposite
        // things: a refusal deletes the install, and a server that is briefly unwell must not.
        else -> throw EsharqServerException(
            status = response.status.value,
            refusal = runCatching { EsharqRefusal.parse(response.body<ByteArray>()) }.getOrNull()
        )
    }
}