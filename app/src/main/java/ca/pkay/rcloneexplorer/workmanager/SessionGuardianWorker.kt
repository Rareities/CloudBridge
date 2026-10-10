package ca.pkay.rcloneexplorer.workmanager

import android.content.Context
import androidx.preference.PreferenceManager
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import ca.pkay.rcloneexplorer.Rclone
import ca.pkay.rcloneexplorer.notifications.AppErrorNotificationManager
import ca.pkay.rcloneexplorer.util.FLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Session Guardian Worker - Proactively checks session health for OAuth-enabled remotes.
 *
 * Optional, explicitly enabled maintenance only. Normal operation discovers failures on demand.
 * Listing failures are classified conservatively; quota, path and crypto failures are not expiry.
 */
class SessionGuardianWorker(
    private val mContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(mContext, workerParams) {

    companion object {
        private const val TAG = "SessionGuardian"
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        if (!PreferenceManager.getDefaultSharedPreferences(mContext)
                .getBoolean(SessionGuardianScheduler.ENABLED_PREFERENCE, false)) {
            return@withContext Result.success()
        }
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

            var oauthRemotesChecked = 0
            var failedHealthChecks = 0

            // Dump config to find OAuth-enabled remotes
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

                    // Check if remote has OAuth token or TOTP secret
                    val hasToken = remoteConfig.has("token") ||
                                  remoteConfig.has("access_token") ||
                                  remoteConfig.has("client_access_token") ||
                                  remoteConfig.has("client_refresh_token")

                    if (!hasToken) {
                        // Not an OAuth/2FA remote, skip health check
                        continue
                    }

                    oauthRemotesChecked++
                    FLog.d(TAG, "Checking session health for remote: $remoteName")

                    // Probe health using lsd with max-depth 1
                    // This initializes the backend and may refresh authentication; it is opt-in.
                    val result = rclone.listDirectories(remoteName, 1)

                    if (result.isSuccess) {
                        FLog.d(TAG, "Session healthy for remote: $remoteName")
                    } else if (result.isNetworkError) {
                        // DNS failure, timeout, connection refused — NOT an auth problem.
                        // Don't alarm the user; the next periodic run will retry.
                        FLog.w(TAG, "Network error checking remote: $remoteName (exit code: ${result.exitCode}), skipping notification.")
                        FLog.w(TAG, "Network error for $remoteName, not a session issue")
                    } else if (SessionHealthPolicy.requiresReconnect(result.stderr)) {
                        // rclone returns process exit codes (0/1/...) rather than HTTP status codes.
                        // A non-zero, non-network result means the probe failed after backend retry/re-auth attempts.
                        FLog.w(TAG, "Health check failed for remote: $remoteName (exit code: ${result.exitCode}). Manual reconnect may be required.")
                        failedHealthChecks++
                        val notifyManager = AppErrorNotificationManager(mContext)
                        notifyManager.showSessionExpiredNotification(remoteName)
                    } else {
                        FLog.w(TAG, "Remote check failed without verified authentication failure; exit ${result.exitCode}")
                    }

                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    FLog.e(TAG, "Error checking remote ${remote.name}: ${e.javaClass.simpleName}", e)
                    FLog.e(TAG, "Error checking remote ${remote.name}", e)
                }
            }

            FLog.d(TAG, "Session Guardian completed. Checked $oauthRemotesChecked OAuth remotes, failed checks: $failedHealthChecks")
            FLog.d(TAG, "Session Guardian completed. Checked: $oauthRemotesChecked, Failed: $failedHealthChecks")

        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            FLog.e(TAG, "Session Guardian failed: ${e.javaClass.simpleName}", e)
            FLog.e(TAG, "Session Guardian failed", e)
            // Don't return failure - we want the worker to continue scheduling
        }

        return@withContext Result.success()
    }
}
