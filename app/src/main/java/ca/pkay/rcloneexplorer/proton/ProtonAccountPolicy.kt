package ca.pkay.rcloneexplorer.proton

import java.security.MessageDigest
import java.util.Locale

/** Only well-defined account failures request login action; filenames are never input here. */
object ProtonAccountPolicy {
    enum class Action { RECONNECT, CHALLENGE, TWO_FACTOR, MAILBOX_PASSWORD }

    @JvmStatic fun identity(username: String): String {
        require(username.isNotBlank()) { "Proton account username is missing" }
        return MessageDigest.getInstance("SHA-256").digest(username.trim().lowercase(Locale.ROOT)
            .toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }

    @JvmStatic fun classify(text: String): Action? {
        val value = text.takeLast(8192).lowercase(Locale.ROOT)
        if (Regex("permission denied|no such file|not a directory|quota exceeded|signature verification|network timeout|directory not found")
                .containsMatchIn(value)) return null
        return when {
            Regex("captcha (?:required|challenge|verification)|human verification (?:required|failed)|verification challenge (?:required|failed)").containsMatchIn(value) -> Action.CHALLENGE
            Regex("(?:invalid|incorrect|expired)[^\\n]{0,40}(?:2fa|two.factor|otp|verification code)|(?:2fa|two.factor|otp)[^\\n]{0,40}(?:required|invalid|incorrect)").containsMatchIn(value) -> Action.TWO_FACTOR
            Regex("(?:invalid|incorrect)[^\\n]{0,40}mailbox password|mailbox password[^\\n]{0,40}(?:required|invalid|incorrect)").containsMatchIn(value) -> Action.MAILBOX_PASSWORD
            Regex("invalid credentials|authentication failed|invalid_grant|(?:session|refresh token|access token)[^\\n]{0,40}(?:revoked|expired|invalid)").containsMatchIn(value) -> Action.RECONNECT
            else -> null
        }
    }

    @JvmStatic fun message(action: Action): String = when (action) {
        Action.CHALLENGE -> "Proton requires human verification. Resolve the challenge through a supported Proton client, then verify the connection in this task."
        Action.TWO_FACTOR -> "Proton requires a valid one-time code. Open Proton connection in this task to reconnect."
        Action.MAILBOX_PASSWORD -> "Proton requires the correct mailbox password. Edit the remote login, then verify its connection."
        Action.RECONNECT -> "Proton authentication needs attention. Automatic jobs for this account are paused. Open Proton connection in this task to reconnect."
    }
}
