package ca.pkay.rcloneexplorer.proton

import java.security.MessageDigest
import java.util.Locale

/** No raw diagnostic or credential is persisted; only classified user-action reasons. */
object ProtonAccountPolicy {
    enum class Action { RECONNECT, CHALLENGE, TWO_FACTOR, MAILBOX_PASSWORD }
    fun identity(username: String): String {
        require(username.isNotBlank()) { "Proton account username is missing" }
        val normalized = username.trim().lowercase(Locale.ROOT)
        return MessageDigest.getInstance("SHA-256").digest(normalized.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }
    fun classify(text: String): Action? {
        val message = text.take(8192).lowercase(Locale.ROOT)
        if (Regex("permission denied|no such file|not a directory|quota exceeded|signature verification|network timeout").containsMatchIn(message)) return null
        return when {
            Regex("captcha (?:required|challenge|verification)|human verification (?:required|failed)|verification challenge (?:required|failed)").containsMatchIn(message) -> Action.CHALLENGE
            Regex("(?:invalid|incorrect|expired)[^\\n]{0,40}(?:2fa|two.factor|otp)|(?:2fa|two.factor|otp)[^\\n]{0,40}(?:required|invalid|incorrect)").containsMatchIn(message) -> Action.TWO_FACTOR
            Regex("(?:invalid|incorrect)[^\\n]{0,40}mailbox password|mailbox password[^\\n]{0,40}(?:required|invalid|incorrect)").containsMatchIn(message) -> Action.MAILBOX_PASSWORD
            Regex("invalid credentials|authentication failed|invalid_grant|(?:session|refresh token|access token)[^\\n]{0,40}(?:revoked|expired|invalid)").containsMatchIn(message) -> Action.RECONNECT
            else -> null
        }
    }
}
