package io.github.revenge.xposed

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.compression.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeoutOrNull

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
 * What this loader sends as `X-Esharq-Location`.
 *
 * 🔴 "2", not "1", and the number is a promise to the server, not a label.
 *
 * 1.0.3 sent "1" and could only ever download from the link it was given. When a member's network
 * could not reach GitHub's CDN that was the end of it — no fallback, no copy on the phone, no Esharq
 * on any launch. The server now reads "1" as "a failure there costs this member their client" and
 * rarely sends such a loader to GitHub at all. "2" says the opposite: a failed direct download is
 * caught below and the file is fetched from us in the same attempt, so a link is always safe to hand
 * over. Sending "2" from a loader that could not do that would bring the lockout straight back.
 */
internal const val LOCATION_PROTOCOL = "2"

/**
 * A download from the link the server handed over did not produce the bundle.
 *
 * 🔴 It names the host and the reason, never the link. The link is signed: until it expires, whoever
 * reads it can fetch the file. Ktor puts the whole URL into its own messages, and those went straight
 * into the error dialog — the one a member screenshots and posts in a public channel — and into the
 * log. So nothing of Ktor's is carried here, not even as a cause.
 */
class DirectDownloadException(val host: String, val reason: String) : Exception("$host: $reason")

/** Thrown when an operation outlived the deadline it was given, whether or not it noticed. */
class DeadlineExceededException(millis: Long) : Exception("no answer within ${millis / 1000}s")

/**
 * How long a direct download may wait for a connection, and for the next bytes once connected.
 *
 * Short on purpose. The connection is the failure that locked a member out: the engine tries one of
 * the CDN's addresses and never the next, so a dead route stays dead for the whole budget. Four
 * seconds with no connection says the route is bad; waiting longer only eats into the time the
 * fallback needs to finish while the launch is still waiting.
 */
private const val DIRECT_CONNECT_TIMEOUT_MS = 4_000L
private const val DIRECT_STALL_TIMEOUT_MS = 6_000L

/**
 * Where work that must not be waited on forever runs.
 *
 * Separate from the caller on purpose, see [withHardDeadline].
 */
private val detached = CoroutineScope(SupervisorJob() + Dispatchers.IO)

/**
 * Runs [block] and stops waiting for it after [millis], whatever it is doing at the time.
 *
 * 🔴 A deadline on the caller's side, because Ktor's own request timeout is not one.
 *
 * Ktor's CIO engine resolves the host name by constructing a `java.net.InetSocketAddress`, which
 * blocks a thread in the system resolver *before* the connect timeout starts (Endpoint.connect in
 * ktor-client-cio 3.5.1). The request timeout reacts by cancelling the request's own job — and a job
 * is only finished once its body returns, so the call it belongs to still waits for the resolver to
 * let go. On a network whose DNS is slow or half-broken that can outlast every timeout set here.
 *
 * The caller, though, only ever waits in a cancellable `join`, so a deadline taken there returns on
 * time whatever the work is doing, and the work is told to stop. Plain `withTimeout` around the call
 * would do the same today; this also holds if the block itself ever blocks rather than suspends,
 * which `withTimeout` would have to wait out.
 */
internal suspend fun <T> withHardDeadline(millis: Long, block: suspend CoroutineScope.() -> T): T {
    val work = detached.async(block = block)
    try {
        withTimeoutOrNull(millis) { work.join() } ?: throw DeadlineExceededException(millis)
        return work.await()
    } finally {
        if (!work.isCompleted) work.cancel()
    }
}

/** A short, link-free reason for a failure, fit for a dialog and a log. */
internal fun describeFailure(e: Throwable): String = when (e) {
    is DirectDownloadException -> e.reason
    is DeadlineExceededException -> e.message ?: "deadline exceeded"
    is io.ktor.client.network.sockets.ConnectTimeoutException -> "no connection"
    is io.ktor.client.network.sockets.SocketTimeoutException -> "stalled"
    is HttpRequestTimeoutException -> "too slow"
    is java.net.UnknownHostException, is java.nio.channels.UnresolvedAddressException -> "name not resolved"
    is javax.net.ssl.SSLException -> "TLS failed"
    is java.io.EOFException -> "connection closed early"
    is java.io.IOException -> "network error (${e.javaClass.simpleName})"
    // A stall mid-body over TLS surfaces here, not as a socket timeout: the TLS layer ends the stream
    // early and Ktor then finds fewer bytes than the Content-Length promised. The message is made of
    // the two byte counts only, so it is safe to show.
    is IllegalStateException ->
        if (e.message?.startsWith("Content-Length mismatch") == true) "cut off (${e.message?.substringAfter(": ")})"
        else e.javaClass.simpleName
    else -> e.javaClass.simpleName
}

/** Any link in [text] reduced to its host. For messages that may reach a screenshot. */
internal fun withoutLinks(text: String): String =
    Regex("""https?://([^/?#\s,\]]+)[^\s,\]]*""").replace(text) { it.groupValues[1] }

/** The host of [url], for messages that must not carry the link itself. */
internal fun hostOf(url: String): String =
    runCatching { Url(url).host }.getOrNull()?.takeIf { it.isNotBlank() } ?: "download host"

/**
 * Whether [bytes] are an Esharq bundle rather than something else that answered 200.
 *
 * 🔴 Anything that answered 200 used to be written over the member's working copy: an HTML page from
 * a captive portal, a proxy's error page, a JSON answer the parser did not recognise. The copy that
 * was running is then gone, and what replaces it throws on the first line. `__ESHARQ_GRANT__` is the
 * gate itself — a bundle without it would trust nothing — and the bundle's release workflow already
 * refuses to publish a build that lacks it, so every genuine build carries it and nothing else does.
 *
 * Not a check for completeness: the name sits about 36 KB into a 3 MB file, so a body cut off later
 * still passes. Truncation is caught before this, by Ktor comparing the bytes read with the
 * Content-Length (DefaultTransform / checkContentLength) and by gzip's own framing on our stream.
 * Anything that reads the body differently — to a file, as a channel — must keep that guarantee.
 */
internal fun looksLikeBundle(bytes: ByteArray): Boolean {
    val marker = BUNDLE_MARKER
    val last = bytes.size - marker.size
    var i = 0
    while (i <= last) {
        var j = 0
        while (j < marker.size && bytes[i + j] == marker[j]) j++
        if (j == marker.size) return true
        i++
    }
    return false
}

private val BUNDLE_MARKER = "__ESHARQ_GRANT__".toByteArray(Charsets.US_ASCII)

/**
 * Fetch a file from a link the server handed us, carrying nothing of ours.
 *
 * 🔴 No bearer, deliberately. The link points at another host, and Ktor forwards headers across a
 * redirect — sending our receipt there would hand a credential to a party with no business holding
 * one. It is not needed either: the link is itself the permission, and it was only issued after
 * membership had been checked.
 *
 * Every way this can fail ends in a [DirectDownloadException] within [timeoutMillis] and a little:
 * a refused or silent connection, a stall, a slow CDN, a non-200 answer (the client does not treat
 * those as errors, so a 403 page used to arrive here as "the bundle"), or the engine simply never
 * returning. The caller falls back to our own server on any of them.
 */
internal suspend fun HttpClient.downloadFrom(url: String, timeoutMillis: Long): ByteArray {
    val host = hostOf(url)

    return try {
        withHardDeadline(timeoutMillis + 2_000) {
            val response = get(url) {
                timeout {
                    requestTimeoutMillis = timeoutMillis
                    connectTimeoutMillis = minOf(DIRECT_CONNECT_TIMEOUT_MS, timeoutMillis)
                    socketTimeoutMillis = minOf(DIRECT_STALL_TIMEOUT_MS, timeoutMillis)
                }
            }
            if (response.status != HttpStatusCode.OK) {
                throw DirectDownloadException(host, "HTTP ${response.status.value}")
            }
            response.body<ByteArray>()
        }
    } catch (e: DirectDownloadException) {
        throw e
    } catch (e: Throwable) {
        // Our own cancellation is not a failed download: let it through untouched.
        currentCoroutineContext().ensureActive()
        throw DirectDownloadException(host, describeFailure(e))
    }
}

/** What following a link came to. */
internal sealed class LinkOutcome {
    /** The bundle, straight from the link. */
    class Direct(val bytes: ByteArray, val etag: String?) : LinkOutcome()

    /** The link failed this phone, and [answer] is what our own server said instead. */
    class FellBack(val failure: DirectDownloadException, val answer: ETagFetchResult) : LinkOutcome()
}

/**
 * Follows a link the server handed over, and asks our server for the file if that does not produce
 * the bundle — in the same attempt.
 *
 * 🔴 The fallback is the whole reason this loader may say "2", and it is the one thing 1.0.3 did not
 * have. A member's network would not carry a connection to GitHub's CDN; their loader had no way back
 * to us and no copy on the phone, so Esharq never loaded, on any launch. Our server had just answered
 * that very phone, so it is the one host known to work.
 *
 * Kept apart from the updater, with the two fetches passed in, so a test can prove it still falls
 * back — a change that quietly dropped this would bring the lockout straight back, and nothing else
 * would notice.
 */
internal suspend fun followLink(
    link: ETagFetchResult.Located,
    budgetMillis: Long,
    download: suspend (url: String, budgetMillis: Long) -> ByteArray,
    fallback: suspend (DirectDownloadException) -> ETagFetchResult,
): LinkOutcome {
    val failure = try {
        val bytes = download(link.url, budgetMillis)
        if (looksLikeBundle(bytes)) return LinkOutcome.Direct(bytes, link.etag)
        DirectDownloadException(hostOf(link.url), "not an Esharq bundle (${bytes.size} bytes)")
    } catch (e: DirectDownloadException) {
        e
    }
    return LinkOutcome.FellBack(failure, fallback(failure))
}

internal suspend fun HttpClient.getWithETag(
    url: String,
    /** Ask the server for a link to the file rather than the file itself. */
    preferLocation: Boolean = false,
    etag: String?,
    timeoutMillis: Long? = null,
    bearer: String? = null,
    /**
     * Why this request asks for the file rather than a link, when that is because GitHub failed this
     * phone: "failed" for the fallback in the same attempt, "skipped" during the week after. Only
     * logged by the server — the routing is decided by the absence of `X-Esharq-Location`, exactly as
     * for a loader from before links — so the transfer those phones cost can be told apart from old
     * loaders instead of being counted with them.
     */
    direct: String? = null,
): ETagFetchResult {
    val response = get(url) {
        etag?.let { headers.append(HttpHeaders.IfNoneMatch, it) }
        bearer?.let { headers.append(HttpHeaders.Authorization, "Bearer $it") }
        if (preferLocation) headers.append("X-Esharq-Location", LOCATION_PROTOCOL)
        direct?.let { headers.append("X-Esharq-Direct", it) }
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