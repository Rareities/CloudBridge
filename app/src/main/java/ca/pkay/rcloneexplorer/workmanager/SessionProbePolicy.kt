package ca.pkay.rcloneexplorer.workmanager

import java.util.Locale

/** Explicit provider capability gate for the background credential-health probe. */
internal object SessionProbePolicy {
    private val customProbeProviders = setOf("protondrive", "internxt")

    @JvmStatic
    fun shouldProbe(
        providerType: String?,
        isOAuthProvider: Boolean,
        hasToken: Boolean,
        hasAccessToken: Boolean,
        hasTotpSecret: Boolean
    ): Boolean {
        val provider = providerType?.trim()?.lowercase(Locale.ROOT).orEmpty()
        if (provider.isEmpty() || (!isOAuthProvider && provider !in customProbeProviders)) {
            return false
        }
        val hasStoredCredential = hasToken || hasAccessToken ||
                (provider == "internxt" && hasTotpSecret)
        return hasStoredCredential
    }
}
