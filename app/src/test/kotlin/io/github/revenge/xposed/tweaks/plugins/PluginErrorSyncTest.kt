package io.github.revenge.xposed.tweaks.plugins

import android.app.Activity
import android.content.Context
import android.content.pm.ApplicationInfo
import io.github.revenge.bridge.RevengeBridge
import io.github.revenge.plugins.PluginScope
import io.github.revenge.plugins.Version
import io.github.revenge.xposed.api.HostScope
import io.github.revenge.xposed.tweaks.bridge.RevengeBridgeRegistry
import io.github.revenge.xposed.tweaks.plugins.internal.DISCORD_VERSION
import kotlinx.coroutines.Job
import java.io.File
import java.nio.file.Files
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.*

/**
 * A plugin error that cannot be delivered to JS must not escape the loader's error-sync collector.
 *
 * Drives the real boot path: the `pluginLoader` tweak, `loadPlugin`, a real internal plugin
 * (Prevent OTA Updates, saved as enabled) whose `start()` throws at boot, and the real
 * [RevengeBridgeRegistry.callJSMethod], which throws "Bridge not ready" before React is up —
 * exactly what happens on the phone at boot, when every tweak runs before the React instance exists.
 *
 * Without the `runCatching` in `loadPlugin`'s `errorSyncJob` the exception leaves the collector,
 * the collector's job fails, and kotlinx.coroutines hands the exception to the worker thread's
 * uncaught-exception handler (on Android that is the process-killing default handler).
 */
class PluginErrorSyncTest {
    private companion object {
        const val PREVENT_OTA_ID = "revenge.discord.prevent-ota-updates"
        const val WAIT_SECONDS = 5L
    }

    private val dataDir: File = Files.createTempDirectory("revenge-error-sync-test").toFile()

    /** Exceptions that reached a thread's uncaught-exception handler (i.e. escaped a coroutine). */
    private val uncaught = LinkedBlockingQueue<Pair<String, Throwable>>()
    private var previousHandler: Thread.UncaughtExceptionHandler? = null

    /**
     * Records every JS call, then hands it to the real registry. Bridge methods registered by the
     * tweak are kept here so the test does not touch the global registry's method table.
     */
    private class SpyBridge : RevengeBridge {
        val methods = ConcurrentHashMap<String, (List<Any?>) -> Any?>()
        val asyncMethods = ConcurrentHashMap<String, suspend (List<Any?>) -> Any?>()

        /** (method, args, what the real bridge threw) for every JS call attempt. */
        val jsCalls = LinkedBlockingQueue<Triple<String, List<Any?>, Throwable?>>()

        override fun registerMethod(name: String, handler: (args: List<Any?>) -> Any?) {
            methods[name] = handler
        }

        override fun registerAsyncMethod(name: String, handler: suspend (args: List<Any?>) -> Any?) {
            asyncMethods[name] = handler
        }

        override suspend fun callJSMethod(name: String, args: List<Any?>): Any? {
            val result = runCatching { RevengeBridgeRegistry.callJSMethod(name, args) }
            jsCalls += Triple(name, args, result.exceptionOrNull())
            return result.getOrThrow()
        }
    }

    private val bridge = SpyBridge()

    private val host = object : HostScope {
        override val modulePath = ""
        override val appInfo = ApplicationInfo().apply { dataDir = this@PluginErrorSyncTest.dataDir.absolutePath }

        // The test class path has kotlin.jvm.functions.Function0 but no com.discord.* classes, so
        // Prevent OTA Updates' start() throws ClassNotFoundException for BundleUpdater.
        override val classLoader: ClassLoader = PluginErrorSyncTest::class.java.classLoader!!
        override val bridge: RevengeBridge = this@PluginErrorSyncTest.bridge
        override fun withAppContext(block: (Context) -> Unit) {}
        override fun withAppActivity(block: (Activity) -> Unit) {}
    }

    @BeforeTest
    fun setUp() {
        // Assigned at runtime by DiscordVersionRetriever, so tests have to provide a value themselves.
        DISCORD_VERSION = Version.parse("345.9")

        previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e -> uncaught += t.name to e }

        // Saved as enabled, so it loads at boot like any plugin a user switched on.
        PluginStatesStore.resetForTests()
        PluginStatesStore.ensureLoaded(dataDir.absolutePath)
            .setPluginFlags(PREVENT_OTA_ID, setOf(PluginFlags.ENABLED))
    }

    @AfterTest
    fun tearDown() {
        Thread.setDefaultUncaughtExceptionHandler(previousHandler)
        PluginStatesStore.resetForTests()
        dataDir.deleteRecursively()
    }

    @Test
    fun `an undeliverable plugin error neither escapes nor ends error sync for that plugin`() {
        pluginLoader.applyTo(host)

        // 1. The plugin really errored and the loader really tried to tell JS, and the real bridge refused.
        val first = bridge.awaitErrored()
            ?: fail("No $EVENT_PLUGIN_ERRORED call within ${WAIT_SECONDS}s: the path under test never ran")
        assertEquals(PREVENT_OTA_ID, first.second[0])
        val firstError = assertNotNull(first.third, "callJSMethod was expected to throw (bridge not ready)")
        assertIs<IllegalStateException>(firstError)
        assertTrue("Bridge not ready" in firstError.message.orEmpty(), firstError.message)
        assertEquals(1, (first.second[1] as List<*>).size)

        // 2. The same plugin errors again. Its collector must still be there to report it.
        val entry = loadedEntry(PREVENT_OTA_ID)
        val scope = entry.field("scope") as PluginScope
        val errorSyncJob = entry.field("errorSyncJob") as Job
        assertTrue(scope.errors.tryEmit(RuntimeException("second error")))

        val second = bridge.awaitErrored()

        uncaught.peek()?.let { (thread, e) ->
            fail("The exception escaped the error-sync collector to thread '$thread': $e")
        }
        assertNotNull(second, "Error sync for $PREVENT_OTA_ID died after the first undeliverable error")
        assertTrue(errorSyncJob.isActive, "errorSyncJob is no longer active: $errorSyncJob")
        // The payload is the whole replay cache, so JS gets both errors once it is reachable.
        assertEquals(2, (second.second[1] as List<*>).size)
    }

    private fun SpyBridge.awaitErrored(): Triple<String, List<Any?>, Throwable?>? {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_SECONDS)
        while (true) {
            val left = deadline - System.nanoTime()
            if (left <= 0) return null
            val call = jsCalls.poll(left, TimeUnit.NANOSECONDS) ?: return null
            if (call.first == EVENT_PLUGIN_ERRORED) return call
        }
    }

    /**
     * `registry` and `LoadedPlugin` are private to PluginLoader.kt, and a second error can only be
     * raised on the *same* scope from inside, so they are reached reflectively. Fails loudly if renamed.
     */
    private fun loadedEntry(id: String): Any {
        val facade = Class.forName("io.github.revenge.xposed.tweaks.plugins.PluginLoaderKt")
        val registry = facade.getDeclaredField("registry").apply { isAccessible = true }.get(null)!!

        @Suppress("UNCHECKED_CAST")
        val loaded = registry.field("loaded") as Map<String, Any>
        return loaded[id] ?: fail("$id is not loaded; loaded: ${loaded.keys}")
    }

    private fun Any.field(name: String): Any? =
        javaClass.getDeclaredField(name).apply { isAccessible = true }.get(this)
}
