package ca.pkay.rcloneexplorer

import android.app.Application
import android.util.Log
import androidx.work.Configuration

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
        ca.pkay.rcloneexplorer.util.ConfigStaging.recover(this)
        // Recover before WorkManager's lazy initialisation can start any saved task.
        try {
            ca.pkay.rcloneexplorer.Database.json.BackupRestore.recoverIfNeeded(this)
        } catch (failure: Exception) {
            // Keep the journal and native-launch guard. The UI can show recovery
            // guidance instead of crashing repeatedly before it can be displayed.
            ca.pkay.rcloneexplorer.util.FLog.e("CloudBridgeApp", "Configuration recovery requires attention: ${failure.javaClass.simpleName}")
        }
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setMinimumLoggingLevel(Log.INFO)
            .setMaxSchedulerLimit(50)
            .build()
}
