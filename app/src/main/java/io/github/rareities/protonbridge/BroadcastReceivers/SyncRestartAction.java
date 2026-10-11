package io.github.rareities.protonbridge.BroadcastReceivers;

import static io.github.rareities.protonbridge.workmanager.SyncWorker.EXTRA_TASK_ID;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import io.github.rareities.protonbridge.workmanager.SyncManager;

/**
 * This class requires a receiver declaration in the manifest
 */
public class SyncRestartAction extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) return;
        io.github.rareities.protonbridge.Items.Task task = new io.github.rareities.protonbridge.Database.DatabaseHandler(context)
                .getTask(intent.getLongExtra(EXTRA_TASK_ID, -1));
        if (task == null || task.getBisyncUUID().isEmpty() ||
                !task.getBisyncUUID().equals(intent.getStringExtra("TASK_UUID"))) return;
        SyncManager sm = new SyncManager(context);
        sm.queue(task.getId());
    }
}
