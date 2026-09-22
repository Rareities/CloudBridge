package ca.pkay.rcloneexplorer

import android.app.Application
import android.util.Log
import androidx.work.Configuration
import ca.pkay.rcloneexplorer.Database.DatabaseHandler
import ca.pkay.rcloneexplorer.Database.RunRepository

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
        // Reconcile legacy numeric tasks before any scheduler/worker can claim them. A native
        // process from a previous app lifetime cannot be proven stopped, so active rows become
        // conservative recovery state rather than being silently retried.
        try {
            DatabaseHandler(this).reconcileLegacyProfiles()
            RunRepository(this).reconcileInterruptedRuns()
        } catch (e: Exception) {
            Log.e("CloudBridgeApp", "Durable run-state reconciliation failed", e)
        }
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setMinimumLoggingLevel(Log.INFO)
            .setMaxSchedulerLimit(50)
            .build()
}
