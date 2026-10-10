package ca.pkay.rcloneexplorer.Activities

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import ca.pkay.rcloneexplorer.R
import ca.pkay.rcloneexplorer.Database.DatabaseHandler
import ca.pkay.rcloneexplorer.util.TaskShortcutAuthorization
import ca.pkay.rcloneexplorer.workmanager.SyncManager
import ca.pkay.rcloneexplorer.workmanager.SyncWorker.Companion.EXTRA_TASK_ID
import ca.pkay.rcloneexplorer.workmanager.SyncWorker.Companion.TASK_SYNC_ACTION

class ShortcutServiceActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val taskId = intent.getLongExtra(EXTRA_TASK_ID, -1)
        val capability = intent.getStringExtra(TaskShortcutAuthorization.EXTRA_CAPABILITY)
        if (intent.action == TASK_SYNC_ACTION &&
            TaskShortcutAuthorization.isAuthorized(this, taskId, capability) &&
            DatabaseHandler(this).getTask(taskId) != null) {
            SyncManager(applicationContext).queue(taskId)
            Toast.makeText(this, getString(R.string.shortcut_start_service), Toast.LENGTH_SHORT).show()
        } else {
            // Old task-ID-only shortcuts must be recreated from the task menu.
            Toast.makeText(this, getString(R.string.shortcut_not_authorized), Toast.LENGTH_SHORT).show()
        }
        finish()
    }
}
