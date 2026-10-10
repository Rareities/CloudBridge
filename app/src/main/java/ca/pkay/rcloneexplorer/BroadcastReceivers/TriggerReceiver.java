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

        if (intent == null || !TriggerService.TRIGGER_RECIEVE.equals(intent.getAction())) return;
        long epoch = intent.getLongExtra(ca.pkay.rcloneexplorer.util.ConfigEpoch.WORK_KEY, -1);
        if (epoch != ca.pkay.rcloneexplorer.util.ConfigEpoch.current(context)) {
            // Retire stale repeating alarms after one ignored delivery; do not wake forever.
            android.app.PendingIntent stale = android.app.PendingIntent.getBroadcast(context,
                    (int) intent.getLongExtra(TriggerService.TRIGGER_ID, -1), intent,
                    android.app.PendingIntent.FLAG_NO_CREATE | android.app.PendingIntent.FLAG_IMMUTABLE);
            if (stale != null) {
                android.app.AlarmManager alarms = (android.app.AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
                if (alarms != null) alarms.cancel(stale);
                stale.cancel();
            }
            return;
        }
        if (TriggerService.TRIGGER_RECIEVE.equals(intent.getAction())) {
            long i = intent.getLongExtra(TriggerService.TRIGGER_ID, -1);
            FLog.e(TAG, "Start Trigger: "+i);
            if(i==-1)
                return;

            ca.pkay.rcloneexplorer.Items.Trigger trigger = new ca.pkay.rcloneexplorer.Database.DatabaseHandler(context).getTrigger(i);
            if (trigger != null) ca.pkay.rcloneexplorer.scheduling.ScheduleCoordinator.alarmDelivered(context, i, epoch);
        }
    }

}
