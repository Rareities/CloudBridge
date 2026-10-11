package io.github.rareities.protonbridge.BroadcastReceivers;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import io.github.rareities.protonbridge.Services.TriggerService;
import io.github.rareities.protonbridge.util.FLog;

public class TriggerReceiver extends BroadcastReceiver {

    private static final String TAG = "TriggerReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        if(intent != null && TriggerService.TRIGGER_RECIEVE.equals(intent.getAction())){
            long i = intent.getLongExtra(TriggerService.TRIGGER_ID, -1);
            FLog.e(TAG, "Start Trigger: "+i);
            if(i==-1)
                return;

            io.github.rareities.protonbridge.workmanager.ScheduleDispatchWorker.enqueue(context, i);
        }
    }

}
