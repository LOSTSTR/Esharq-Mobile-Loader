package io.github.revenge.xposed

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.util.Locale

/**
 * What the server says when it will not serve this launch.
 *
 * A phone has no console and nobody reads a log, so the refusal is the entire explanation the user
 * ever gets. It carries both languages and, when joining the server is the fix, the invite — the
 * loader shows it rather than failing quietly and leaving them to guess.
 */
@Serializable
data class EsharqRefusal(
    val reason: String = "unknown",
    @SerialName("message") val messageAr: String = "",
    @SerialName("message_en") val messageEn: String = "",
    val invite: String? = null,
) {
    /** Arabic on an Arabic phone, English otherwise. Falls back to whichever one is present. */
    val message: String
        get() {
            val arabic = Locale.getDefault().language == "ar"
            val preferred = if (arabic) messageAr else messageEn
            return preferred.ifBlank { messageAr.ifBlank { messageEn } }
        }

    /** The one refusal the user can act on: they are simply not in the server yet. */
    val isNotMember: Boolean get() = reason == "not_member"

    companion object {
        fun parse(body: ByteArray): EsharqRefusal =
            runCatching { RevengeJson.decodeFromString<EsharqRefusal>(body.decodeToString()) }
                .getOrDefault(EsharqRefusal())
    }
}
