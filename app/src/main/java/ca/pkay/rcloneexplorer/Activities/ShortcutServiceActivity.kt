package ca.pkay.rcloneexplorer.Activities

import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AlertDialog
import ca.pkay.rcloneexplorer.Database.DatabaseHandler
import ca.pkay.rcloneexplorer.util.ShortcutAuthorisation
import ca.pkay.rcloneexplorer.R
import ca.pkay.rcloneexplorer.workmanager.SyncManager
import ca.pkay.rcloneexplorer.workmanager.SyncWorker.Companion.EXTRA_TASK_ID
import ca.pkay.rcloneexplorer.workmanager.SyncWorker.Companion.TASK_SYNC_ACTION

class ShortcutServiceActivity : AppCompatActivity() {

    private val TAG = "ShortcutServiceActivity"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (intent.action != TASK_SYNC_ACTION || !intent.hasExtra(EXTRA_TASK_ID)) { finish(); return }
        val task = DatabaseHandler(this).getTask(intent.getLongExtra(EXTRA_TASK_ID, -1))
        if (task == null) { finish(); return }
        val start = {
            SyncManager(this).queue(task.id)
            Toast.makeText(this, getString(R.string.shortcut_start_service), Toast.LENGTH_SHORT).show()
            finish()
        }
        if (ShortcutAuthorisation.verify(this, task, intent.getStringExtra(ShortcutAuthorisation.EXTRA_CAPABILITY))) {
            start()
        } else {
            // Existing shortcuts and untrusted automation require visible consent.
            AlertDialog.Builder(this).setTitle("Run task?")
                .setMessage("An external request wants to run '${task.title}'.\n\n${task.localPath}\n${task.remoteId}:${task.remotePath}")
                .setNegativeButton(android.R.string.cancel) { _, _ -> finish() }
                .setPositiveButton("Run task") { _, _ -> start() }
                .setOnCancelListener { finish() }.show()
        }
    }
}
