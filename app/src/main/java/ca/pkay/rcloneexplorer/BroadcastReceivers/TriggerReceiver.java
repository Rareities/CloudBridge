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
        FLog.e(TAG, "Received Intent");

        if(intent != null && TriggerService.TRIGGER_RECIEVE.equals(intent.getAction())){
            long i = intent.getLongExtra(TriggerService.TRIGGER_ID, -1);
            FLog.e(TAG, "Start Trigger: "+i);
            if(i==-1)
                return;

            Intent service = new Intent(context, TriggerService.class);
            service.setAction(TriggerService.TRIGGER_RECIEVE);
            service.putExtra(TriggerService.TRIGGER_ID, i);
            if (intent.hasExtra(TriggerService.ALARM_TARGET_ID)) {
                service.putExtra(TriggerService.ALARM_TARGET_ID,
                        intent.getLongExtra(TriggerService.ALARM_TARGET_ID, Long.MIN_VALUE));
            }
            if (intent.hasExtra(TriggerService.ALARM_TYPE)) {
                service.putExtra(TriggerService.ALARM_TYPE,
                        intent.getIntExtra(TriggerService.ALARM_TYPE, Integer.MIN_VALUE));
            }
            if (intent.hasExtra(TriggerService.ALARM_TIME)) {
                service.putExtra(TriggerService.ALARM_TIME,
                        intent.getIntExtra(TriggerService.ALARM_TIME, Integer.MIN_VALUE));
            }
            if (intent.hasExtra(TriggerService.ALARM_WEEKDAYS)) {
                service.putExtra(TriggerService.ALARM_WEEKDAYS,
                        intent.getIntExtra(TriggerService.ALARM_WEEKDAYS, Integer.MIN_VALUE));
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(service);
            }else{
                context.startService(service);
            }
        }
    }

}
