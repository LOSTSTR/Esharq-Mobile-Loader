package io.github.revenge.xposed

import android.content.pm.ApplicationInfo
import kotlinx.serialization.Serializable
import java.io.File
import java.util.zip.ZipFile

/**
 * Who this copy of Esharq Mobile belongs to.
 *
 * The Esharq installer signs the user in with Discord, asks Discord whether that account is in the
 * Esharq server, and only then writes this into the APK it builds. So the token is not a claim the
 * phone makes about itself — it is the server's receipt, and every request carries it.
 *
 * A module side-loaded without the installer has no receipt, which is the whole point: there is
 * nothing to strip out and no offline path, because the mod itself was never shipped along with it.
 */
@Serializable
data class EsharqInstall(
    val token: String,
)

/**
 * The receipt this launch should use.
 *
 * A renewed token on disk always wins: the one baked into the APK expires, and an APK cannot
 * rewrite itself, so without this an install would stop working a month after it was made even
 * though the user never left the server. Falls back to the baked copy on a first launch, and
 * returns null when neither exists — which the caller treats as "not authorised", not as an error.
 */
internal fun readEsharqInstall(appInfo: ApplicationInfo, renewed: File): EsharqInstall? =
    readRenewedToken(renewed) ?: readBakedInstall(appInfo)

private fun readRenewedToken(renewed: File): EsharqInstall? =
    runCatching { renewed.takeIf { it.isFile }?.readText()?.trim() }
        .getOrNull()
        ?.takeIf { it.isNotBlank() }
        ?.let(::EsharqInstall)

private fun readBakedInstall(appInfo: ApplicationInfo): EsharqInstall? {
    val apk = appInfo.sourceDir ?: return null

    return runCatching {
        ZipFile(apk).use { zip ->
            val entry = zip.getEntry(RevengeConstants.INSTALL_ASSET) ?: return null
            val text = zip.getInputStream(entry).bufferedReader().use { it.readText() }
            RevengeJson.decodeFromString<EsharqInstall>(text)
        }
    }.getOrNull()?.takeIf { it.token.isNotBlank() }
}
