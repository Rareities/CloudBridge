package ca.pkay.rcloneexplorer.workmanager

/** A failed listing is not evidence of expired authentication. */
internal object SessionHealthPolicy {
    private val authentication = Regex(
        "invalid_grant|invalid credentials|authentication failed|unauthorized|" +
        "(?:refresh|access) token[^\\n]*(?:expired|revoked|invalid)|" +
        "(?:http|status(?: code)?)[ :=]+401\\b", RegexOption.IGNORE_CASE)
    fun requiresReconnect(stderr: String): Boolean = authentication.containsMatchIn(stderr)
}
