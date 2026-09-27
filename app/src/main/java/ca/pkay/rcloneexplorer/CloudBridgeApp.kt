package ca.pkay.rcloneexplorer

import android.app.Application
import android.util.Log
import androidx.work.Configuration
import ca.pkay.rcloneexplorer.Database.DatabaseHandler
import ca.pkay.rcloneexplorer.Database.RunRepository
import ca.pkay.rcloneexplorer.util.BackupArchiveStager
import ca.pkay.rcloneexplorer.util.FLog

/**
 * Application entry point.
 *
 * Provides a custom [Configuration] for WorkManager so that more transfer workers
 * (uploads/downloads launched from the file explorer) can be scheduled concurrently than
 * the stock defaults allow. See the transmission-speed audit (item 4).
 *
 * The default `androidx.startup` `WorkManagerInitializer` is disabled in `AndroidManifest.xml`
 * so that this configuration is honored; WorkManager then initializes lazily on first use.
 */
class CloudBridgeApp : Application(), Configuration.Provider {

    override fun onCreate() {
        super.onCreate()
        // Remove bounded leftovers from an interrupted backup import before a new import can
        // create another private snapshot. The stager only matches its own exact temp names.
        try {
            BackupArchiveStager.cleanupOrphanedArchives(filesDir)
        } catch (e: Exception) {
            FLog.e("CloudBridgeApp", "Staged backup cleanup failed", e)
        }
        // Reconcile legacy numeric tasks before any scheduler/worker can claim them. A native
        // process from a previous app lifetime cannot be proven stopped, so active rows become
        // conservative recovery state rather than being silently retried.
        try {
            val database = DatabaseHandler(this)
            try {
                database.reconcileLegacyProfiles()
            } finally {
                database.close()
            }
            RunRepository(this).reconcileInterruptedRuns()
        } catch (e: Exception) {
            FLog.e("CloudBridgeApp", "Durable run-state reconciliation failed", e)
        }
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setMinimumLoggingLevel(Log.INFO)
            .setMaxSchedulerLimit(50)
            .build()
}
