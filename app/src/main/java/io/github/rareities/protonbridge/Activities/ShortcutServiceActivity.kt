package io.github.rareities.protonbridge.Activities

import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AlertDialog
import io.github.rareities.protonbridge.Database.DatabaseHandler
import io.github.rareities.protonbridge.util.ShortcutAuthorisation
import io.github.rareities.protonbridge.R
import io.github.rareities.protonbridge.workmanager.SyncManager
import io.github.rareities.protonbridge.workmanager.SyncWorker.Companion.EXTRA_TASK_ID
import io.github.rareities.protonbridge.workmanager.SyncWorker.Companion.TASK_SYNC_ACTION

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
