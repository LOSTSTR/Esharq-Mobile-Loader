package io.github.revenge.xposed.tweaks

import android.app.AlertDialog
import android.util.AtomicFile
import android.widget.Toast
import androidx.core.util.writeBytes
import io.github.revenge.logger
import io.github.revenge.reloadApp
import io.github.revenge.xposed.*
import io.github.revenge.xposed.tweaks.base.withAppActivity
import io.github.revenge.xposed.tweaks.plugins.internal.showRecoveryAlert
import kotlinx.coroutines.*
import kotlinx.serialization.Serializable
import java.io.File
import kotlin.time.Duration.Companion.seconds

@Serializable
data class CustomLoadUrl(
    val enabled: Boolean = false,
    val url: String = "",
)

@Serializable
data class LoaderConfig(
    val customLoadUrl: CustomLoadUrl = CustomLoadUrl(),
)

/**
 * Updater for the JS bundle.
 *
 * Handles configuration, downloading, caching, and user-facing retry/recovery dialogs.
 * The actual loading of the bundle is handled by [revengeScriptLoader].
 */
object RevengeUpdater {
    internal val TIMEOUT = 10.seconds
    private val TIMEOUT_CACHED = 5.seconds
    private const val ETAG_PATH = "etag.txt"
    private const val CONFIG_PATH = "loader.json"

    private val log = logger("esharqUpdater")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var config = LoaderConfig()

    /** The server's receipt for this install, read once out of the APK. Null means unauthorised. */
    @Volatile
    private var install: EsharqInstall? = null

    private lateinit var bundle: File
    private lateinit var etag: File
    private lateinit var configFile: File
    private lateinit var grantPreload: File
    private lateinit var renewedToken: File

    private val _downloadReady = CompletableDeferred<Unit>()

    /**
     * Completes after the *first* download attempt finishes (success or any terminal failure).
     * [revengeScriptLoader] joins on this before falling through to its fallback bundle.
     */
    val downloadReady: Deferred<Unit> = _downloadReady

    internal fun init(dataDir: String, appInfo: android.content.pm.ApplicationInfo) {
        val cacheDir = File(dataDir, RevengeConstants.CACHE_DIR).apply { mkdirs() }
        val filesDir = File(dataDir, RevengeConstants.FILES_DIR).apply { mkdirs() }
        val preloadsDir = File(filesDir, RevengeConstants.PRELOADS_DIR).apply { mkdirs() }

        bundle = File(cacheDir, RevengeConstants.MAIN_SCRIPT_FILE)
        etag = File(cacheDir, ETAG_PATH)
        configFile = File(filesDir, CONFIG_PATH)
        grantPreload = File(preloadsDir, RevengeConstants.GRANT_PRELOAD_FILE)
        renewedToken = File(filesDir, RevengeConstants.RENEWED_TOKEN_FILE)

        install = readEsharqInstall(appInfo, renewedToken)

        config = runCatching {
            if (configFile.exists()) RevengeJson.decodeFromString<LoaderConfig>(configFile.readText())
            else LoaderConfig()
        }.getOrDefault(LoaderConfig())
    }

    /**
     * Leaves nothing behind that could run on the next launch.
     *
     * Called when the server refuses. Deleting the bundle is what makes leaving the Esharq server
     * actually take effect: without it the last authorised copy would keep running offline forever.
     */
    private fun clearAuthorisedFiles(refusal: EsharqRefusal? = null) {
        bundle.delete()
        etag.delete()
        grantPreload.delete()

        // Leaving the server keeps the receipt: membership is re-checked on every request anyway,
        // so rejoining restores everything on the next launch with no second trip through the
        // installer. A rejected receipt is a different matter — drop it so the copy baked into the
        // APK gets its turn.
        if (refusal != null && !refusal.isNotMember) renewedToken.delete()
    }

    fun resetLoaderConfig() {
        if (::configFile.isInitialized && configFile.exists()) configFile.delete()
    }

    /**
     * Trigger a download. If [userInitiated] is true (retry from the error dialog), the timeout
     * is disabled and a success dialog is shown on the next available activity.
     */
    fun downloadScript(userInitiated: Boolean = false, showDialog: Boolean = true): Job = scope.launch {
        try {
            val token = install?.token
            if (token == null) {
                // No receipt in this APK: the module was not installed by the Esharq installer.
                // There is nothing to fall back to, and that is the point.
                log.w("No Esharq install token in this APK; nothing will be loaded")
                clearAuthorisedFiles()
                return@launch
            }

            // The grant is asked for first, and it is tiny. It is what the bundle checks before it
            // touches Discord, so a stale one must never outlive a refusal: re-fetching it every
            // launch is what turns "left the server" into "stops working" with no revocation list
            // to maintain. It also means a refusal costs one small request, not a whole bundle.
            val grant = httpClient.getWithETag(
                url = RevengeConstants.GRANT_URL,
                etag = null,
                timeoutMillis = if (userInitiated) null else TIMEOUT.inWholeMilliseconds,
                bearer = token,
            )

            if (grant is ETagFetchResult.Refused) {
                log.w("Refused: ${grant.refusal.reason}")
                clearAuthorisedFiles(grant.refusal)
                showRefusalDialog(grant.refusal)
                return@launch
            }

            if (grant is ETagFetchResult.Fetched) {
                AtomicFile(grantPreload).writeBytes(grant.bytes)
                grant.renewedToken?.let { AtomicFile(renewedToken).writeBytes(it.toByteArray()) }
            }

            // The custom URL stays for development only. In a release build it would be a way to
            // point a member's authorised loader at an unauthorised bundle.
            val url = config.customLoadUrl.takeIf { it.enabled && BuildConfig.DEBUG }?.url
                ?: RevengeConstants.BUNDLE_URL
            log.i("Fetching JS bundle from: $url")

            val result = httpClient.getWithETag(
                url = url,
                etag = if (etag.exists() && bundle.exists()) etag.readText() else null,
                timeoutMillis = if (userInitiated) null
                else if (bundle.exists()) TIMEOUT_CACHED.inWholeMilliseconds else TIMEOUT.inWholeMilliseconds,
                bearer = token,
            )

            when (result) {
                is ETagFetchResult.Fetched -> {
                    AtomicFile(bundle).writeBytes(result.bytes)

                    result.etag?.let(etag::writeText) ?: etag.delete()

                    log.i("Bundle updated (${result.bytes.size} bytes)")
                    if (showDialog) {
                        if (userInitiated) showSuccessDialog() else showUpdateDialog()
                    }
                }

                ETagFetchResult.NotModified -> log.i("Server responded with 304, no changes")

                is ETagFetchResult.Refused -> {
                    log.w("Refused: ${result.refusal.reason}")
                    clearAuthorisedFiles(result.refusal)
                    showRefusalDialog(result.refusal)
                }
            }
        } catch (e: Throwable) {
            // A network failure is not a refusal: the cached bundle stays, and the grant it already
            // holds still has to be inside its own validity window for anything to run.
            log.e("Failed to download script", e)
            showErrorDialog(e)
        } finally {
            _downloadReady.complete(Unit)
        }
    }

    private fun showUpdateDialog() = withAppActivity { activity ->
        activity.runOnUiThread {
            AlertDialog.Builder(activity)
                .setTitle("Revenge Update Downloaded")
                .setMessage("A reload is required for changes to take effect.")
                .setPositiveButton("Reload") { d, _ -> reloadApp(); d.dismiss() }
                .setNegativeButton("Later") { d, _ -> d.dismiss() }
                .show()
        }
    }

    private fun showSuccessDialog() = withAppActivity { activity ->
        activity.runOnUiThread {
            AlertDialog.Builder(activity)
                .setTitle("Revenge Update Successful")
                .setMessage("A reload is required for changes to take effect.")
                .setPositiveButton("Reload") { d, _ -> reloadApp(); d.dismiss() }
                .setNegativeButton("Later") { d, _ -> d.dismiss() }
                .show()
        }
    }

    /**
     * The only explanation the user ever gets, so it says what happened and — when they can fix it
     * by joining the server — offers the way there instead of leaving them to find it.
     */
    private fun showRefusalDialog(refusal: EsharqRefusal) = withAppActivity { activity ->
        activity.runOnUiThread {
            val arabic = java.util.Locale.getDefault().language == "ar"
            val builder = AlertDialog.Builder(activity)
                .setTitle(if (arabic) "إشراق للجوال" else "Esharq Mobile")
                .setMessage(refusal.message)
                .setNegativeButton(if (arabic) "حسناً" else "OK") { d, _ -> d.dismiss() }

            refusal.invite?.takeIf { refusal.isNotMember }?.let { invite ->
                builder.setPositiveButton(if (arabic) "انضم للخادم" else "Join the server") { d, _ ->
                    runCatching {
                        activity.startActivity(
                            android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(invite))
                        )
                    }
                    d.dismiss()
                }
            }

            builder.show()
        }
    }

    private fun showErrorDialog(e: Throwable) = withAppActivity { activity ->
        activity.runOnUiThread {
            AlertDialog.Builder(activity)
                .setTitle("Revenge Update Failed")
                .setMessage(
                    """
                    Unable to download the latest version of Revenge.
                    This is usually caused by bad network connection.

                    Error: ${e.message ?: e.stackTraceToString()}
                    """.trimIndent()
                )
                .setNegativeButton("Dismiss") { d, _ -> d.dismiss() }
                .setPositiveButton("Retry Update") { d, _ ->
                    downloadScript(userInitiated = true)
                    Toast.makeText(activity, "Retrying download in background...", Toast.LENGTH_SHORT).show()
                    d.dismiss()
                }
                .setNeutralButton("Recovery") { d, _ -> showRecoveryAlert(activity); d.dismiss() }
                .show()
        }
    }
}

/**
 * Wires [RevengeUpdater] into the lifecycle. Loads the loader config once the target [android.content.Context]
 * is available, then kicks off the first download.
 */
val revengeUpdater by tweak {
    withAppContext { ctx ->
        RevengeUpdater.init(ctx.dataDir.absolutePath, appInfo)
        RevengeUpdater.downloadScript(userInitiated = false, showDialog = false)
    }
}
