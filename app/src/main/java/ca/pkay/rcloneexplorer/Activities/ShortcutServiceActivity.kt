package ca.pkay.rcloneexplorer.Activities

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import ca.pkay.rcloneexplorer.R
import ca.pkay.rcloneexplorer.util.FLog
import ca.pkay.rcloneexplorer.util.ShortcutCapabilities
import ca.pkay.rcloneexplorer.workmanager.SyncManager
import ca.pkay.rcloneexplorer.workmanager.SyncWorker.Companion.EXTRA_TASK_ID
import ca.pkay.rcloneexplorer.workmanager.SyncWorker.Companion.TASK_SYNC_ACTION

class ShortcutServiceActivity : AppCompatActivity() {

    private val TAG = "ShortcutServiceActivity"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        moveTaskToBack(true)

        val id = taskIdFromIntent()
        val capability = intent.getStringExtra(ShortcutCapabilities.EXTRA_CAPABILITY)
        if (intent.action == TASK_SYNC_ACTION && id != null
            && ShortcutCapabilities.isValid(this, id, capability)) {
            SyncManager(this).queue(id)
            Toast.makeText(this, getString(R.string.shortcut_start_service), Toast.LENGTH_SHORT).show()
        } else {
            FLog.w(TAG, "Rejected shortcut launch: invalid action or capability")
            Toast.makeText(this, getString(R.string.shortcut_missing_id), Toast.LENGTH_SHORT).show()
        }

        finish()
    }

    private fun taskIdFromIntent(): Long? {
        val extras = intent.extras ?: return null
        if (!extras.containsKey(EXTRA_TASK_ID)) {
            return null
        }
        val rawId = extras.getLong(EXTRA_TASK_ID, Long.MIN_VALUE)
        return rawId.takeIf { it > 0L }
    }
}
