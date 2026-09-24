package ca.pkay.rcloneexplorer.workmanager

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import ca.pkay.rcloneexplorer.Rclone
import ca.pkay.rcloneexplorer.notifications.AppErrorNotificationManager
import ca.pkay.rcloneexplorer.util.FLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Session Guardian Worker - Proactively checks session health for credential-capable remotes.
 *
 * This worker runs periodically to detect expired tokens before the user needs them.
 * It uses rclone config dump to identify remotes with token or totp_secret fields,
 * then probes their health using rclone lsd. If the token is expired, the Go backend's
 * reAuthorize logic will automatically attempt to refresh it during the lsd command.
 */
class SessionGuardianWorker(
    private val mContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(mContext, workerParams) {

    companion object {
        private const val TAG = "SessionGuardian"
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val rclone = Rclone(mContext)

        try {
            FLog.d(TAG, "Session Guardian started")
            FLog.d(TAG, "Checking session health for all remotes")

            // Get all remotes
            val remotes = rclone.getRemotes()
            if (remotes.isEmpty()) {
                FLog.d(TAG, "No remotes configured, skipping health check")
                return@withContext Result.success()
            }

            var credentialRemotesChecked = 0
            var failedHealthChecks = 0

            // Dump config to inspect stored credentials after provider capability is checked.
            val configDump = rclone.configDump()
            if (configDump == null || configDump.isEmpty()) {
                FLog.e(TAG, "Failed to dump rclone config")
                return@withContext Result.success()
            }

            val configJson = JSONObject(configDump)

            // Iterate through all remotes
            for (remote in remotes) {
                val remoteName = remote.name
                try {
                    val remoteConfig = configJson.optJSONObject(remoteName)
                    if (remoteConfig == null) {
                        continue
                    }

                    // Use the app's provider capability list, with explicit custom-provider
                    // exceptions for Proton Drive and Internxt. A token-shaped field alone is
                    // not enough to make an arbitrary backend eligible for background probing.
                    if (!SessionProbePolicy.shouldProbe(
                            remote.typeReadable,
                            remote.isOAuth(),
                            remoteConfig.has("token"),
                            remoteConfig.has("access_token"),
                            remoteConfig.has("totp_secret")
                        )) {
                        continue
                    }

                    credentialRemotesChecked++
                    FLog.d(TAG, "Checking session health for remote: $remoteName")

                    // Probe health using lsd with max-depth 1
                    // This is a lightweight operation that will trigger reAuthorize in Go backend if needed
                    val result = rclone.listDirectories(remoteName, 1)

                    if (result.isSuccess) {
                        FLog.d(TAG, "Session healthy for remote: $remoteName")
                    } else if (result.isNetworkError) {
                        // DNS failure, timeout, connection refused - NOT an auth problem.
                        // Don't alarm the user; the next periodic run will retry.
                        FLog.w(TAG, "Network error checking remote: $remoteName (exit code: ${result.exitCode}), skipping notification")
                    } else if (result.isAuthenticationError) {
                        // This notification opens the app's explicit re-auth path. Emit it only
                        // when the sanitized native error classifier positively identifies auth.
                        FLog.w(TAG, "Authentication failure checking remote: $remoteName (exit code: ${result.exitCode})")
                        failedHealthChecks++
                        AppErrorNotificationManager(mContext).showSessionExpiredNotification(remoteName)
                    } else if (result.isRateLimited) {
                        FLog.w(TAG, "Provider rate-limited health probe for remote: $remoteName; skipping notification")
                        failedHealthChecks++
                    } else if (result.isIntegrityError) {
                        FLog.e(TAG, "Integrity failure checking remote: $remoteName; skipping re-auth notification")
                        failedHealthChecks++
                    } else {
                        // Unknown provider errors are not evidence that a session expired.
                        FLog.w(TAG, "Health probe failed for remote: $remoteName (exit code: ${result.exitCode}, category: ${result.failureCategory}); skipping re-auth notification")
                        failedHealthChecks++
                    }

                } catch (e: Exception) {
                    FLog.e(TAG, "Error checking remote ${remote.name}", e)
                }
            }

            FLog.d(TAG, "Session Guardian completed. Checked: $credentialRemotesChecked, Failed: $failedHealthChecks")

        } catch (e: Exception) {
            FLog.e(TAG, "Session Guardian failed", e)
            // Don't return failure - we want the worker to continue scheduling
        }

        return@withContext Result.success()
    }
}
