package ca.pkay.rcloneexplorer.Services;

import android.annotation.SuppressLint;
import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.preference.PreferenceManager;

import java.util.Calendar;
import java.util.TimeZone;
import ca.pkay.rcloneexplorer.scheduling.ScheduleCalculator;

import ca.pkay.rcloneexplorer.BroadcastReceivers.TriggerReceiver;
import ca.pkay.rcloneexplorer.Database.DatabaseHandler;
import ca.pkay.rcloneexplorer.Items.Trigger;
import ca.pkay.rcloneexplorer.R;
import ca.pkay.rcloneexplorer.notifications.AppErrorNotificationManager;
import ca.pkay.rcloneexplorer.util.PermissionManager;
import ca.pkay.rcloneexplorer.workmanager.SyncManager;

public class TriggerService extends Service {

    private DatabaseHandler dbHandler;
    private Context context;

    public static String TRIGGER_RECIEVE = "TRIGGER_RECIEVE";
    public static String TRIGGER_ID = "TRIGGER_ID";

    public static String CHANNEL_ID = "CHANNEL_ID";
    public static int SERVICE_NOTIFICATION_ID = 42;

    //Required for Servicecall
    public TriggerService() {}

    public TriggerService(Context c) {
        this.dbHandler = new DatabaseHandler(c);
        this.context = c;
    }

    public void queueTrigger(){
        long epoch = ca.pkay.rcloneexplorer.util.ConfigEpoch.current(context);
        for(Trigger t : dbHandler.getAllTrigger()){
            queueSingleTrigger(t, epoch);
        }
    }

    public void queueSingleTrigger(Trigger trigger){
        queueSingleTrigger(trigger, ca.pkay.rcloneexplorer.util.ConfigEpoch.current(context));
    }

    private void queueSingleTrigger(Trigger trigger, long epoch) {
        if (epoch != ca.pkay.rcloneexplorer.util.ConfigEpoch.current(context)) return;
        if (!trigger.isEnabled()) {
            cancelTrigger(trigger.getId());
        } else if (trigger.getType() == Trigger.TRIGGER_TYPE_SCHEDULE) {
            queueSingleScheduleTrigger(trigger, epoch);
        } else if (trigger.getType() == Trigger.TRIGGER_TYPE_INTERVAL) {
            queueSingleIntervalTrigger(trigger, epoch);
        } else {
            cancelTrigger(trigger.getId());
            Log.w("TriggerService", "Unsupported trigger type");
        }
    }

    @SuppressLint("ScheduleExactAlarm") // this is caught by the PermissionManager itself
    private void queueSingleScheduleTrigger(Trigger trigger, long epoch){
        if(trigger.isEnabled()){
            final long timeToTrigger;
            try {
                timeToTrigger = ScheduleCalculator.nextDaily(System.currentTimeMillis(),
                        trigger.getTime(), trigger.getWeekdays(), TimeZone.getDefault());
            } catch (IllegalArgumentException e) {
                cancelTrigger(trigger.getId());
                Log.w("TriggerService", "Invalid daily schedule");
                return;
            }
            if (timeToTrigger < 0) {
                cancelTrigger(trigger.getId());
                return;
            }

            AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
            PendingIntent pi = getIntent(trigger.getId(), epoch);
            am.cancel(pi);

            SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(context);
            boolean allowWhileIdle = sharedPreferences.getBoolean(context.getString(R.string.shared_preferences_allow_sync_trigger_while_idle), false);

            if((new PermissionManager(context)).grantedAlarms()) {
                if (allowWhileIdle) {
                    am.setExactAndAllowWhileIdle(
                            AlarmManager.RTC_WAKEUP,
                            timeToTrigger,
                            pi
                    );
                } else {
                    am.setExact(
                            AlarmManager.RTC_WAKEUP,
                            timeToTrigger,
                            pi
                    );
                }
            } else {
                new AppErrorNotificationManager(context).showNotification();
            }
        }
    }

    private void queueSingleIntervalTrigger(Trigger trigger, long epoch){
        if(trigger.isEnabled()){

            final long intervalMillis;
            try {
                intervalMillis = ScheduleCalculator.intervalMillis(trigger.getTime());
            } catch (IllegalArgumentException e) {
                cancelTrigger(trigger.getId());
                Log.w("TriggerService", "Invalid interval schedule");
                return;
            }
            long timeToTrigger = System.currentTimeMillis();

            AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
            PendingIntent pi = getIntent(trigger.getId(), epoch);
            am.cancel(pi);
            am.setInexactRepeating(
                    AlarmManager.RTC_WAKEUP,
                    timeToTrigger+intervalMillis,
                    intervalMillis,
                    pi
            );
        }
    }

    public void cancelTrigger(long triggerID){
        AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        am.cancel(getIntent(triggerID));
    }

    private void startTask(Trigger trigger, long epoch){
        if (!trigger.isEnabled()) return;
        boolean skipBecauseOfWeekday;
        //account for monday beeing 1 and sunday beeing 0. Therefor we need to offset by 2
        int day = Calendar.getInstance().get(Calendar.DAY_OF_WEEK)-2;

        //check for sundays. Calendar starts with sunday.
        if(day==-1){
            skipBecauseOfWeekday = !trigger.isEnabledAtDay(6);
        }else{
            skipBecauseOfWeekday = !trigger.isEnabledAtDay(day);
        }

        if(skipBecauseOfWeekday){
            return;
        }

        SyncManager sm = new SyncManager(this.context);
        sm.queueAtEpoch(trigger.getTriggerTarget(), epoch);
    }

    private PendingIntent getIntent(long triggerId){
        return getIntent(triggerId, ca.pkay.rcloneexplorer.util.ConfigEpoch.current(context));
    }

    private PendingIntent getIntent(long triggerId, long epoch) {
        Intent i = new Intent(context, TriggerReceiver.class);
        i.setAction(TRIGGER_RECIEVE);
        // Epoch is part of PendingIntent identity, so an old rescheduler cannot overwrite a new alarm.
        i.setData(android.net.Uri.parse("cloudbridge://trigger/" + triggerId + "/" + epoch));
        i.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        i.putExtra(TRIGGER_ID, triggerId);
        i.putExtra(ca.pkay.rcloneexplorer.util.ConfigEpoch.WORK_KEY, epoch);

        // URI identity also distinguishes IDs that collide after the request-code int cast.
        return PendingIntent.getBroadcast(context, (int) triggerId, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        createNotification();
        long id = intent == null ? -1 : intent.getLongExtra(TRIGGER_ID, -1);
        this.dbHandler = new DatabaseHandler(getBaseContext());
        this.context = getBaseContext();
        long epoch = intent == null ? -1 : intent.getLongExtra(ca.pkay.rcloneexplorer.util.ConfigEpoch.WORK_KEY, -1);
        if (epoch != ca.pkay.rcloneexplorer.util.ConfigEpoch.current(context)) {
            stopForeground(true);
            stopSelf(startId);
            return Service.START_NOT_STICKY;
        }
        Trigger t = dbHandler.getTrigger(id);

        // this can happen if the trigger was scheduled, but then deleted.
        if(t == null) {
            stopForeground(true);
            stopSelf(startId);
            return Service.START_NOT_STICKY;
        }

        try {
            startTask(t, epoch);
            queueSingleTrigger(t, epoch);
        } finally {
            stopForeground(true);
            stopSelf(startId);
        }
        return Service.START_NOT_STICKY;
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void createNotification(){
        createNotificationChannel();
        Notification notification;
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            notification = new Notification.Builder(this, CHANNEL_ID)
                    .setContentTitle(getText(R.string.notification_triggerservice_title))
                    .setContentText(getText(R.string.notification_triggerservice_description))
                    .setSmallIcon(R.drawable.ic_launcher_foreground)
                    .build();
        } else {
            NotificationCompat.Builder notificationBuilder = new NotificationCompat.Builder(this)
                    .setContentTitle(getText(R.string.notification_triggerservice_title))
                    .setContentText(getText(R.string.notification_triggerservice_description))
                    .setSmallIcon(R.drawable.ic_launcher_foreground);
            notification = notificationBuilder.build();
        }
        startForeground(SERVICE_NOTIFICATION_ID, notification);
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.notification_triggerservice_title),
                    NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription(getString(R.string.notification_triggerservice_description));

            NotificationManager notificationManager = getSystemService(NotificationManager.class);
            notificationManager.createNotificationChannel(channel);
        }
    }

}
