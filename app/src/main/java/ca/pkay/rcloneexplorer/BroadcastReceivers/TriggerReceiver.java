package ca.pkay.rcloneexplorer.BroadcastReceivers;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import ca.pkay.rcloneexplorer.Services.TriggerService;
import ca.pkay.rcloneexplorer.util.FLog;

public class TriggerReceiver extends BroadcastReceiver {

    private static final String TAG = "TriggerReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        if(intent != null && TriggerService.TRIGGER_RECIEVE.equals(intent.getAction())){
            long i = intent.getLongExtra(TriggerService.TRIGGER_ID, -1);
            FLog.e(TAG, "Start Trigger: "+i);
            if(i==-1)
                return;

            ca.pkay.rcloneexplorer.workmanager.ScheduleDispatchWorker.enqueue(context, i);
        }
    }

}
