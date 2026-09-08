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
    /**
     * How long a download may take.
     *
     * These were 10 and 5 seconds, chosen when the bundle was small enough that it never mattered.
     * It is now megabytes of Arabic dictionary, and the arithmetic turned against them quietly:
     * five seconds meant demanding 588 KB/s sustained from a phone on mobile data before an update
     * would be accepted at all. Below that bar the download is abandoned every single time, so the
     * user stays on the build they have — for good, with nothing saying why.
     *
     * The cold budget is the one that decides whether somebody installing today gets Esharq at all,
     * so it is the more generous of the two. The cached budget can afford to be shorter, because
     * failing it costs only an update, but not as short as it was.
     *
     * Note that TIMEOUT is also what RevengeScriptLoader blocks the launch on, so raising it is not
     * free: it is the longest the app can sit waiting before starting without the mod.
     */
    internal val TIMEOUT = 20.seconds
    private val TIMEOUT_CACHED = 15.seconds

    /**
     * The budget when the user asked for it themselves, by tapping Retry.
     *
     * 🔴 That path used to pass `null` and a comment said the timeout was disabled — but the client
     * installs `HttpTimeout` with no defaults, so Ktor's own request timeout applied instead. The
     * attempt a user waits for on purpose therefore had the *shortest* budget of the three, which is
     * the opposite of what was intended and what the comment claimed.
     */
    private val USER_DOWNLOAD_TIMEOUT = 90.seconds

    /**
     * How long to wait before each further attempt.
     *
     * The first attempt runs the instant Discord starts, which is the worst moment to ask for a
     * network: wifi may not have associated, and a phone waking from doze throttles its first
     * sockets. Three seconds covers that.
     *
     * 🔴 The second pause is far longer because the failure it answers is a different one. A 503
     * from our own server is not a phone that is not ready — it is an outage measured in tens of
     * seconds, and asking again three seconds later only spends battery to be told the same thing.
     */
    private val RETRY_DELAYS = listOf(3.seconds, 15.seconds)

    /**
     * Whether this launch has already spent one unit of the offline budget.
     *
     * 🔴 The budget is counted in *launches* — that is the whole reason a clock cannot cheat it. But
     * the counter used to fire once per failed attempt, and the Retry button re-enters the same
     * path, so a few taps during one server outage plus the launch itself reached
     * MAX_UNVERIFIED_LAUNCHES and deleted a perfectly good cached bundle. A user pressing the button
     * the dialog offers them must not be able to destroy their own install.
     */
    @Volatile
    private var countedThisLaunch = false
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
    private lateinit var unverifiedLaunches: File

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
        unverifiedLaunches = File(filesDir, RevengeConstants.UNVERIFIED_LAUNCHES_FILE)

        install = readEsharqInstall(appInfo, renewedToken)
    }

    private fun readUnverified(): Int =
        runCatching { unverifiedLaunches.readText().trim().toInt() }.getOrDefault(0)

    /**
     * Counts a launch the server never answered, and gives up on the cache once too many pile up.
     *
     * The counter, not the grant's expiry, is what closes the offline hole: an expiry is checked
     * against a clock the user controls, while a launch that has happened cannot be un-happened.
     */
    private fun countUnverifiedLaunch() {
        if (countedThisLaunch) return
        countedThisLaunch = true

        val count = readUnverified() + 1
        runCatching { AtomicFile(unverifiedLaunches).writeBytes(count.toString().toByteArray()) }

        if (count >= RevengeConstants.MAX_UNVERIFIED_LAUNCHES) {
            log.w("No answer from the server in $count launches; dropping the cached bundle")
            bundle.delete()
            etag.delete()
            grantPreload.delete()
        }
    }

    /** The server answered, so the streak is over. */
    private fun clearUnverifiedLaunches() {
        runCatching { unverifiedLaunches.delete() }

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
            // 🔴 A loop, not a recursive call, and the difference is not stylistic.
            //
            // Retrying used to mean launching `downloadScript` again as a separate coroutine and
            // returning from this one. Two things followed, both bad:
            //
            //   * The `finally` completed `downloadReady` the moment the first attempt gave up, so
            //     the launch stopped waiting and Discord started without Esharq even when the retry
            //     was about to succeed a second later — and the success dialog was suppressed too,
            //     so the user sat in plain Discord with a working bundle on disk and no hint that
            //     reopening would fix it.
            //
            //   * The fresh call cleared the retry flag as soon as the *grant* succeeded, before the
            //     bundle was fetched. A bundle-only failure — the tiny grant works, the three
            //     megabytes time out — therefore retried without end, every few seconds, for the
            //     life of the process, and never once reached the counter or the error dialog.
            //     Nothing was shown and nothing stopped.
            //
            // Kept in one coroutine the budget is a local number nothing can reset, and the launch
            // waits for the real outcome.
            var attempt = 0

            while (true) {
                try {
                    attemptDownload(userInitiated, showDialog)
                    return@launch
                } catch (e: Throwable) {
                    log.e("Failed to download script (attempt ${attempt + 1})", e)

                    if (attempt >= RETRY_DELAYS.size) {
                        // A network failure is not a refusal, so a bad connection does not cost the
                        // user their client — but it is counted, and enough of them in a row drop
                        // the cache anyway. Otherwise leaving the server and switching the network
                        // off would keep Esharq running for good, and the grant's expiry could not
                        // stop it: that is checked against a clock the same person sets.
                        countUnverifiedLaunch()
                        showErrorDialog(e)
                        return@launch
                    }

                    val pause = RETRY_DELAYS[attempt]
                    log.i("Retrying in ${pause.inWholeSeconds}s")
                    delay(pause)
                    attempt++
                }
            }
        } finally {
            _downloadReady.complete(Unit)
        }
    }

    /**
     * One whole attempt: the grant, then the bundle.
     *
     * Throws on anything the caller should consider retrying, and returns normally when the outcome
     * is settled — including a refusal, which is an answer and must never be tried again.
     */
    private suspend fun attemptDownload(userInitiated: Boolean, showDialog: Boolean) {

        val token = install?.token
        if (token == null) {
            // No receipt in this APK: the module was not installed by the Esharq installer.
            // There is nothing to fall back to, and that is the point.
            log.w("No Esharq install token in this APK; nothing will be loaded")
            clearAuthorisedFiles()
            return
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

        // The server answered at all, which is what the streak counts.
        clearUnverifiedLaunches()

        if (grant is ETagFetchResult.Refused) {
            log.w("Refused: ${grant.refusal.reason}")
            clearAuthorisedFiles(grant.refusal)
            showRefusalDialog(grant.refusal)
            return
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
            // Ask for a link rather than the file. Three megabytes through the server is metered
            // traffic that pauses the whole site when it runs out, over a file already sitting on a
            // host built to serve it.
            preferLocation = true,
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

            is ETagFetchResult.Located -> {
                // Fetched without our bearer, deliberately.
                //
                // 🔴 The link points at somebody else's host, and Ktor forwards headers across a
                // redirect. Sending our receipt there would hand a credential to a party that has no
                // business holding one — so this request carries nothing of ours at all. It does not
                // need to: the link is itself the permission, and the server only issued it after
                // checking membership.
                log.i("Fetching bundle from the location the server gave")

                val budget = when {
                    userInitiated -> USER_DOWNLOAD_TIMEOUT
                    bundle.exists() -> TIMEOUT_CACHED
                    else -> TIMEOUT
                }

                val bytes = httpClient.downloadFrom(result.url, budget.inWholeMilliseconds)

                if (bytes.isEmpty()) error("The bundle arrived empty")

                AtomicFile(bundle).writeBytes(bytes)
                result.etag?.let(etag::writeText) ?: etag.delete()

                log.i("Bundle updated (${bytes.size} bytes)")
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
    }

    /** The device's language, asked once per dialog rather than written out at each call site. */
    private fun isArabic(): Boolean = java.util.Locale.getDefault().language == "ar"

    private fun showUpdateDialog() = withAppActivity { activity ->
        activity.runOnUiThread {
            AlertDialog.Builder(activity)
                // 🔴 Said in the user's language, like everything else.
                //
                // These two were left in English — and still called the project "Revenge" — while
                // the dialog beside them was translated. The success one is what a user sees right
                // after tapping Retry, so it sits on the very path they just walked.
                .setTitle(if (isArabic()) "نُزّل تحديث إشراق" else "Esharq update downloaded")
                .setMessage(
                    if (isArabic()) "أعد تشغيل ديسكورد ليأخذ التحديث مفعوله."
                    else "Reload Discord for the update to take effect."
                )
                .setPositiveButton(if (isArabic()) "إعادة التشغيل" else "Reload") { d, _ -> reloadApp(); d.dismiss() }
                .setNegativeButton(if (isArabic()) "لاحقاً" else "Later") { d, _ -> d.dismiss() }
                .show()
        }
    }

    private fun showSuccessDialog() = withAppActivity { activity ->
        activity.runOnUiThread {
            AlertDialog.Builder(activity)
                .setTitle(if (isArabic()) "تمّ التحديث" else "Esharq updated")
                .setMessage(
                    if (isArabic()) "أعد تشغيل ديسكورد ليأخذ التحديث مفعوله."
                    else "Reload Discord for the update to take effect."
                )
                .setPositiveButton(if (isArabic()) "إعادة التشغيل" else "Reload") { d, _ -> reloadApp(); d.dismiss() }
                .setNegativeButton(if (isArabic()) "لاحقاً" else "Later") { d, _ -> d.dismiss() }
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

    /**
     * What the user is told when the check could not be made.
     *
     * The old wording was wrong in three ways at once, and a user saw all three at the same time.
     * It said "Revenge", which is not the name of the thing they installed. It said the update had
     * failed, in English, to someone using an Arabic client. And it said it on a launch where
     * nothing was actually broken: a failed check does not remove the copy already on the phone —
     * clearAuthorisedFiles is called for a refusal, never for a network error — so Esharq loads and
     * runs exactly as before, and the only thing lost is a chance to pick up a newer build.
     *
     * Alarming somebody about a working app teaches them to dismiss every dialog it ever shows,
     * including the one that matters. So the message now depends on what is actually true: whether
     * a usable copy is sitting there. The technical detail is kept, at the end, because it is the
     * only thing anybody can send back when something really is wrong.
     */
    private fun showErrorDialog(e: Throwable) = withAppActivity { activity ->
        activity.runOnUiThread {
            val arabic = java.util.Locale.getDefault().language == "ar"
            val running = bundle.exists() && grantPreload.exists()

            val title = when {
                running && arabic -> "إشراق يعمل — تعذّر التحقّق من التحديث"
                running -> "Esharq is running — could not check for updates"
                arabic -> "تعذّر تحميل إشراق"
                else -> "Could not load Esharq"
            }

            val body = when {
                running && arabic ->
                    "لم يُتَح الاتّصال بخادم إشراق، فيعمل التطبيق من النسخة المحفوظة على جهازك. " +
                        "لا شيء مفقود، وسيُلتقط أيّ تحديث عند أوّل اتّصال ناجح."
                running ->
                    "The Esharq server could not be reached, so the app is running from the copy " +
                        "already on your device. Nothing is missing; a newer build will be picked " +
                        "up on the next successful check."
                arabic ->
                    "لم يُتَح الاتّصال بخادم إشراق، ولا توجد نسخة محفوظة على الجهاز — " +
                        "فديسكورد يعمل بلا إشراق حتى ينجح الاتّصال."
                else ->
                    "The Esharq server could not be reached and there is no saved copy on this " +
                        "device, so Discord is running without Esharq until a check succeeds."
            }

            // 🔴 The server's own sentence, when it sent one.
            //
            // A 503 used to surface as Ktor's English "Bad response: HttpResponse[…]" even though
            // the server had written the explanation in Arabic and English and sent it in the body.
            // That text is now carried on the exception, so what the user reads is what we wrote.
            val fromServer = (e as? EsharqServerException)?.refusal?.message?.takeIf { it.isNotBlank() }
            val detail = fromServer ?: e.message ?: e.stackTraceToString()

            AlertDialog.Builder(activity)
                .setTitle(title)
                .setMessage(body + "\n\n" + (if (arabic) "التفصيل: " else "Detail: ") + detail)
                .setNegativeButton(if (arabic) "حسناً" else "Dismiss") { d, _ -> d.dismiss() }
                .setPositiveButton(if (arabic) "أعد المحاولة" else "Retry") { d, _ ->
                    downloadScript(userInitiated = true)
                    Toast.makeText(
                        activity,
                        if (arabic) "تُعاد المحاولة في الخلفية…" else "Retrying in the background…",
                        Toast.LENGTH_SHORT
                    ).show()
                    d.dismiss()
                }
                .setNeutralButton(if (arabic) "الاستعادة" else "Recovery") { d, _ ->
                    showRecoveryAlert(activity); d.dismiss()
                }
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
