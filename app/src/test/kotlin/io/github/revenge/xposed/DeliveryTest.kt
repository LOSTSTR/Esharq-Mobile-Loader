package io.github.revenge.xposed

import kotlinx.coroutines.runBlocking
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread
import kotlin.test.*

/**
 * The pieces that keep a member's copy of Esharq safe when the download goes wrong.
 *
 * Written after a member's loader could not reach GitHub's CDN and never loaded Esharq at all. The
 * loader now tells the server it can fall back ("2"), and the server then sends it to GitHub — so if
 * the fallback ever stopped working, the lockout would be back for every phone at once. These tests
 * are what notices. Everything here is local: no outside host is contacted.
 */
class DeliveryTest {
    private val bundle = "(()=>{var a=1;globalThis.__ESHARQ_GRANT__&&verify(a)})()".toByteArray()
    private val link = ETagFetchResult.Located(
        "https://release-assets.githubusercontent.com/x/bundle?sp=r&sig=SECRET&jwt=eyJ.x.y",
        "\"1-2\"",
    )

    // ── Recognising a bundle ────────────────────────────────────────────────────────────────────

    @Test
    fun `a real bundle is recognised by its gate`() {
        assertTrue(looksLikeBundle(bundle))
    }

    @Test
    fun `anything else that answered 200 is not a bundle`() {
        assertFalse(looksLikeBundle(ByteArray(0)))
        assertFalse(looksLikeBundle("<html><body>Sign in to the Wi-Fi</body></html>".toByteArray()))
        assertFalse(looksLikeBundle("""{"ok":true,"url":"https://example.com/x"}""".toByteArray()))
        // One byte short of the marker.
        assertFalse(looksLikeBundle("var x=__ESHARQ_GRANT_".toByteArray()))
    }

    @Test
    fun `the marker at the very end still counts`() {
        assertTrue(looksLikeBundle("x".repeat(10_000).toByteArray() + "__ESHARQ_GRANT__".toByteArray()))
    }

    // ── The fallback itself ─────────────────────────────────────────────────────────────────────

    @Test
    fun `a failed link falls back to our server in the same attempt`() {
        runBlocking {
            var askedWith: DirectDownloadException? = null
            val outcome = followLink(
                link, 10_000,
                download = { _, _ -> throw DirectDownloadException("release-assets.githubusercontent.com", "no connection") },
                fallback = { askedWith = it; ETagFetchResult.Fetched(bundle, "\"1-2\"") },
            )
            val fellBack = assertIs<LinkOutcome.FellBack>(outcome)
            assertEquals("no connection", fellBack.failure.reason)
            assertSame(fellBack.failure, askedWith, "the fallback is told why")
            assertIs<ETagFetchResult.Fetched>(fellBack.answer)
        }
    }

    @Test
    fun `a link that returns something other than the bundle also falls back`() {
        runBlocking {
            var fellBack = false
            val outcome = followLink(
                link, 10_000,
                download = { _, _ -> "<html>captive portal</html>".toByteArray() },
                fallback = { fellBack = true; ETagFetchResult.NotModified },
            )
            assertTrue(fellBack)
            val failure = assertIs<LinkOutcome.FellBack>(outcome).failure
            assertTrue(failure.reason.startsWith("not an Esharq bundle"), failure.reason)
            assertFalse("SECRET" in failure.message.orEmpty())
        }
    }

    @Test
    fun `a good link never touches our server`() {
        runBlocking {
            val outcome = followLink(
                link, 10_000,
                download = { _, _ -> bundle },
                fallback = { fail("fell back although the link delivered the bundle") },
            )
            val direct = assertIs<LinkOutcome.Direct>(outcome)
            assertContentEquals(bundle, direct.bytes)
            assertEquals("\"1-2\"", direct.etag)
        }
    }

    // ── Direct downloads against a local server ─────────────────────────────────────────────────

    /** A one-connection-at-a-time HTTP server that answers each request with [respond]. */
    private class LocalServer(private val respond: (path: String, socket: Socket) -> Unit) : AutoCloseable {
        private val server = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
        val base = "http://127.0.0.1:${server.localPort}"

        init {
            thread(isDaemon = true) {
                while (!server.isClosed) {
                    val socket = runCatching { server.accept() }.getOrNull() ?: break
                    thread(isDaemon = true) {
                        runCatching {
                            val input = socket.getInputStream().bufferedReader()
                            val requestLine = input.readLine() ?: return@runCatching
                            while (!input.readLine().isNullOrEmpty()) Unit
                            respond(requestLine.split(" ")[1], socket)
                        }
                    }
                }
            }
        }

        override fun close() = server.close()
    }

    private fun Socket.reply(head: String, body: ByteArray = ByteArray(0)) {
        getOutputStream().apply {
            write(head.replace("\n", "\r\n").toByteArray())
            write(body)
            flush()
        }
    }

    private fun assertNoSecret(e: Throwable) {
        val all = generateSequence(e) { it.cause }.joinToString(" | ") { it.message.orEmpty() }
        assertFalse("SECRET" in all, all)
        assertFalse("jwt" in all, all)
    }

    @Test
    fun `a 404 from the link is a failure, named by status`() {
        LocalServer { _, s -> s.reply("HTTP/1.1 404 Not Found\nContent-Length: 9\nConnection: close\n\n", "not found".toByteArray()); s.close() }.use { srv ->
            val e = assertFailsWith<DirectDownloadException> {
                runBlocking { httpClient.downloadFrom("${srv.base}/x?sig=SECRET&jwt=y", 5_000) }
            }
            assertEquals("HTTP 404", e.reason)
            assertEquals("127.0.0.1", e.host)
            assertNoSecret(e)
        }
    }

    @Test
    fun `a server that never answers is given up on within the budget`() {
        val held = mutableListOf<Socket>()
        LocalServer { _, s -> synchronized(held) { held += s } }.use { srv ->
            val started = System.currentTimeMillis()
            val e = assertFailsWith<DirectDownloadException> {
                runBlocking { httpClient.downloadFrom("${srv.base}/x?sig=SECRET", 1_500) }
            }
            val waited = System.currentTimeMillis() - started
            assertTrue(waited < 5_000, "waited ${waited}ms on a 1.5 s budget")
            assertNoSecret(e)
        }
        synchronized(held) { held.forEach { runCatching { it.close() } } }
    }

    @Test
    fun `a body cut off before its promised length is a failure, not a bundle`() {
        LocalServer { _, s ->
            s.reply("HTTP/1.1 200 OK\nContent-Length: 100000\nConnection: close\n\n", bundle)
            s.close()
        }.use { srv ->
            val e = assertFailsWith<DirectDownloadException> {
                runBlocking { httpClient.downloadFrom("${srv.base}/x?sig=SECRET", 5_000) }
            }
            assertNoSecret(e)
        }
    }

    @Test
    fun `a refused connection fails at once`() {
        val port = ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { it.localPort }
        val started = System.currentTimeMillis()
        val e = assertFailsWith<DirectDownloadException> {
            runBlocking { httpClient.downloadFrom("http://127.0.0.1:$port/x?sig=SECRET", 5_000) }
        }
        assertTrue(System.currentTimeMillis() - started < 3_000)
        assertNoSecret(e)
    }

    @Test
    fun `a good answer is returned whole`() {
        LocalServer { _, s ->
            s.reply("HTTP/1.1 200 OK\nContent-Type: application/octet-stream\nContent-Length: ${bundle.size}\nConnection: close\n\n", bundle)
            s.close()
        }.use { srv ->
            val bytes = runBlocking { httpClient.downloadFrom("${srv.base}/x?sig=SECRET", 5_000) }
            assertContentEquals(bundle, bytes)
        }
    }

    // ── Messages that may end up in a screenshot ────────────────────────────────────────────────

    @Test
    fun `a signed link never survives into a message`() {
        val ktor = "Connect timeout has expired [url=https://release-assets.githubusercontent.com/github-production-release-asset/1325609076/abc?sp=r&sv=2018&sig=SECRET&jwt=eyJ.x.y, connect_timeout=unknown ms]"
        val shown = withoutLinks(ktor)
        assertFalse("SECRET" in shown, shown)
        assertFalse("jwt" in shown, shown)
        assertTrue("release-assets.githubusercontent.com" in shown, shown)
        assertEquals("nothing to strip", withoutLinks("nothing to strip"))
    }

    @Test
    fun `a link with no path is stripped too`() {
        val shown = withoutLinks("failed [url=https://cdn.example?sig=SECRET, connect_timeout=4000 ms]")
        assertFalse("SECRET" in shown, shown)
        assertTrue("cdn.example" in shown, shown)
    }

    @Test
    fun `a direct download failure names the host and the reason only`() {
        val e = DirectDownloadException("release-assets.githubusercontent.com", "no connection")
        assertEquals("release-assets.githubusercontent.com: no connection", e.message)
        assertNull(e.cause)
    }

    @Test
    fun `a stall cut short by TLS reads as cut off`() {
        val e = IllegalStateException("Content-Length mismatch: expected 3028558 bytes, but received 1048576 bytes")
        assertEquals("cut off (expected 3028558 bytes, but received 1048576 bytes)", describeFailure(e))
    }

    // ── Deadlines ───────────────────────────────────────────────────────────────────────────────

    @Test
    fun `the deadline holds even when the work ignores cancellation`() {
        runBlocking {
            val started = System.currentTimeMillis()
            val failure = assertFailsWith<DeadlineExceededException> {
                withHardDeadline(300) {
                    // Blocking, not suspending: exactly the kind of wait that cancellation cannot reach.
                    @Suppress("BlockingMethodInNonBlockingContext")
                    Thread.sleep(3_000)
                }
            }
            val waited = System.currentTimeMillis() - started
            assertTrue(waited < 1_500, "waited ${waited}ms for a 300ms deadline")
            assertNotNull(failure.message)
        }
    }

    @Test
    fun `work that finishes in time returns its value and its failures unchanged`() {
        runBlocking {
            assertEquals(42, withHardDeadline(2_000) { 42 })
            val thrown = assertFailsWith<IllegalStateException> { withHardDeadline(2_000) { error("boom") } }
            assertEquals("boom", thrown.message)
        }
    }

    @Test
    fun `the loader promises a fallback to the server`() {
        // "1" tells the server this loader cannot fall back, and it will then avoid GitHub for it.
        assertEquals("2", LOCATION_PROTOCOL)
    }
}
