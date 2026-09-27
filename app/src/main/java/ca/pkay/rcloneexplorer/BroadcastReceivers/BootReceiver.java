package ca.pkay.rcloneexplorer.BroadcastReceivers;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import ca.pkay.rcloneexplorer.Services.TriggerService;

public class BootReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || intent.getAction() == null) {
            return;
        }
        TriggerService triggerService = new TriggerService(context);
        try {
            if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
                triggerService.queueTrigger();
            } else if (Intent.ACTION_MY_PACKAGE_REPLACED.equals(intent.getAction())) {
                // Rebuild every alarm identity after an app update so alarms created by the
                // legacy 32-bit request-code scheme cannot keep running under ambiguous IDs.
                triggerService.queueTrigger();
            } else if (Intent.ACTION_TIME_CHANGED.equals(intent.getAction())
                    || Intent.ACTION_TIMEZONE_CHANGED.equals(intent.getAction())) {
                triggerService.queueScheduleTriggers();
            }
        } finally {
            triggerService.close();
        }
    }
}
