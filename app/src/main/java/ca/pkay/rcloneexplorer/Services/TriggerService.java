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
import android.net.Uri;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.preference.PreferenceManager;

import java.util.Calendar;

import ca.pkay.rcloneexplorer.BroadcastReceivers.TriggerReceiver;
import ca.pkay.rcloneexplorer.Database.DatabaseHandler;
import ca.pkay.rcloneexplorer.Database.TriggerStateLock;
import ca.pkay.rcloneexplorer.Items.Trigger;
import ca.pkay.rcloneexplorer.R;
import ca.pkay.rcloneexplorer.notifications.AppErrorNotificationManager;
import ca.pkay.rcloneexplorer.util.PermissionManager;
import ca.pkay.rcloneexplorer.workmanager.ScheduleTimeCalculator;
import ca.pkay.rcloneexplorer.workmanager.ScheduledTriggerExecutionPolicy;
import ca.pkay.rcloneexplorer.workmanager.SyncManager;

public class TriggerService extends Service {

    private DatabaseHandler dbHandler;
    private Context context;

    public static String TRIGGER_RECIEVE = "TRIGGER_RECIEVE";
    public static String TRIGGER_ID = "TRIGGER_ID";
    public static String ALARM_TARGET_ID = "TRIGGER_ALARM_TARGET_ID";
    public static String ALARM_TYPE = "TRIGGER_ALARM_TYPE";
    public static String ALARM_TIME = "TRIGGER_ALARM_TIME";
    public static String ALARM_WEEKDAYS = "TRIGGER_ALARM_WEEKDAYS";

    public static String CHANNEL_ID = "CHANNEL_ID";
    public static int SERVICE_NOTIFICATION_ID = 42;

    //Required for Servicecall
    public TriggerService() {}

    public TriggerService(Context c) {
        this.dbHandler = new DatabaseHandler(c);
        this.context = c;
    }

    /**
     * Releases the database helper used by the short-lived scheduling facade. The Android
     * service instance also calls this from {@link #onDestroy()}; callers that construct the
     * facade with {@link #TriggerService(Context)} must close it explicitly.
     */
    public void close() {
        if (dbHandler != null) {
            dbHandler.close();
            dbHandler = null;
        }
    }

    public void queueTrigger(){
        synchronized (TriggerStateLock.MONITOR) {
            java.util.List<Trigger> triggers = dbHandler.getAllTrigger();
            for(Trigger t : triggers){
                queueCurrentTrigger(t);
            }
            // Upgrade legacy PendingIntents only after every current trigger has a new,
            // collision-free identity. Old identities used (int) triggerId and could alias.
            for (Trigger trigger : triggers) {
                cancelLegacyTriggerAlarm(trigger.getId());
            }
        }
    }

    /** @return true only when every scheduled trigger was armed or required no alarm. */
    public boolean queueScheduleTriggers(){
        synchronized (TriggerStateLock.MONITOR) {
            boolean allReconciled = true;
            for(Trigger t : dbHandler.getAllTrigger()){
                if(t.getType() == Trigger.TRIGGER_TYPE_SCHEDULE) {
                    if (!queueCurrentTrigger(t)) {
                        allReconciled = false;
                    }
                }
            }
            return allReconciled;
        }
    }

    public void queueSingleTrigger(Trigger trigger){
        synchronized (TriggerStateLock.MONITOR) {
            Trigger current = dbHandler.getTrigger(trigger.getId());
            if (current == null || !current.isEnabled()) {
                cancelTrigger(trigger.getId());
                return;
            }
            queueCurrentTrigger(current);
        }

    }

    /** Reconcile a trigger from a snapshot read while holding TriggerStateLock.MONITOR. */
    private boolean queueCurrentTrigger(Trigger trigger) {
        if(trigger.getType() == Trigger.TRIGGER_TYPE_SCHEDULE) {
            return queueSingleScheduleTrigger(trigger);
        } else {
            queueSingleIntervalTrigger(trigger);
            return true;
        }
    }

    @SuppressLint("ScheduleExactAlarm") // this is caught by the PermissionManager itself
    private boolean queueSingleScheduleTrigger(Trigger trigger){
        AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        PendingIntent pi = getIntent(trigger);
        am.cancel(pi);
        if (!trigger.isEnabled()) {
            return true;
        }

        long now = System.currentTimeMillis();
        Long nextOccurrence = ScheduleTimeCalculator.nextOccurrence(
                now,
                trigger.getTime(),
                trigger.getWeekdays()
        );
        if(nextOccurrence == null) {
            return true;
        }

        SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(context);
        boolean allowWhileIdle = sharedPreferences.getBoolean(context.getString(R.string.shared_preferences_allow_sync_trigger_while_idle), false);

        if((new PermissionManager(context)).grantedAlarms()) {
            if (allowWhileIdle) {
                am.setExactAndAllowWhileIdle(
                        AlarmManager.RTC_WAKEUP,
                        nextOccurrence,
                        pi
                );
            } else {
                am.setExact(
                        AlarmManager.RTC_WAKEUP,
                        nextOccurrence,
                        pi
                );
            }
            return true;
        }

        new AppErrorNotificationManager(context).showNotification();
        return false;
    }

    private void queueSingleIntervalTrigger(Trigger trigger){
        AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        PendingIntent pi = getIntent(trigger);
        // Reconciliation must remove a previously queued alarm even when the trigger was disabled.
        am.cancel(pi);

        Long intervalMillis = TriggerDispatchPolicy.intervalMillisIfEnabled(
                trigger.isEnabled(), trigger.getTime());
        if (intervalMillis == null) return;

        long timeToTrigger = System.currentTimeMillis();
        am.setInexactRepeating(
                AlarmManager.RTC_WAKEUP,
                timeToTrigger + intervalMillis,
                intervalMillis,
                pi
        );
    }

    public void cancelTrigger(long triggerID){
        if (triggerID <= 0) {
            return;
        }
        synchronized (TriggerStateLock.MONITOR) {
            AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
            am.cancel(getIntent(triggerID));
            cancelLegacyTriggerAlarm(triggerID);
        }
    }

    private void cancelLegacyTriggerAlarm(long triggerID) {
        AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        PendingIntent legacy = PendingIntent.getBroadcast(context, (int) triggerID,
                getLegacyIntent(triggerID),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        am.cancel(legacy);
        legacy.cancel();
    }

    private void startTask(Trigger trigger){
        synchronized (TriggerStateLock.MONITOR) {
            Trigger current = dbHandler.getTrigger(trigger.getId());
            if (current == null || !current.isEnabled()) {
                cancelTrigger(trigger.getId());
                return;
            }
            // The trigger model uses Monday=0 through Sunday=6; Calendar numbers Sunday first.
            int calendarDay = Calendar.getInstance().get(Calendar.DAY_OF_WEEK);
            int weekday = ScheduledTriggerExecutionPolicy.weekdayFromCalendar(calendarDay);
            boolean dayEnabled = current.isEnabledAtDay(weekday);
            if (!TriggerDispatchPolicy.shouldDispatch(current.isEnabled(), dayEnabled)) {
                return;
            }

            SyncManager sm = new SyncManager(this.context);
            sm.queue(current);
        }
    }

    private PendingIntent getIntent(Trigger trigger){
        long triggerId = trigger.getId();
        Intent i = getIdentityIntent(triggerId);
        i.putExtra(TRIGGER_ID, triggerId);
        // A broadcast already delivered before an edit must not run the newly edited task.
        i.putExtra(ALARM_TARGET_ID, trigger.getTriggerTarget());
        i.putExtra(ALARM_TYPE, trigger.getType());
        i.putExtra(ALARM_TIME, trigger.getTime());
        i.putExtra(ALARM_WEEKDAYS, trigger.getWeekdays());

        return PendingIntent.getBroadcast(context, 0, i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private PendingIntent getIntent(long triggerId){
        Intent intent = getIdentityIntent(triggerId);
        intent.putExtra(TRIGGER_ID, triggerId);
        return PendingIntent.getBroadcast(context, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private Intent getIdentityIntent(long triggerId) {
        Intent i = new Intent(context, TriggerReceiver.class);
        i.setAction(TRIGGER_RECIEVE);
        i.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        i.setData(Uri.parse(TriggerAlarmIdentityPolicy.dataUri(triggerId)));
        return i;
    }

    private Intent getLegacyIntent(long triggerId) {
        Intent intent = new Intent(context, TriggerReceiver.class);
        intent.setAction(TRIGGER_RECIEVE);
        intent.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        intent.putExtra(TRIGGER_ID, triggerId);
        return intent;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        createNotification();
        if (intent == null) {
            close();
            stopForeground(true);
            return Service.START_NOT_STICKY;
        }
        long id = intent.getLongExtra(TRIGGER_ID, -1);
        close();
        this.dbHandler = new DatabaseHandler(getBaseContext());
        this.context = getBaseContext();
        if (id <= 0) {
            close();
            stopForeground(true);
            return Service.START_NOT_STICKY;
        }
        synchronized (TriggerStateLock.MONITOR) {
            Trigger t = dbHandler.getTrigger(id);

            // this can happen if the trigger was scheduled, but then deleted.
            if(t == null) {
                cancelTrigger(id);
                stopForeground(true);
                return Service.START_NOT_STICKY;
            }

            // An alarm may already have been delivered when the user disables its trigger.
            if (!t.isEnabled()) {
                cancelTrigger(id);
                stopForeground(true);
                return Service.START_NOT_STICKY;
            }

            boolean hasSnapshot = intent.hasExtra(ALARM_TARGET_ID)
                    && intent.hasExtra(ALARM_TYPE)
                    && intent.hasExtra(ALARM_TIME)
                    && intent.hasExtra(ALARM_WEEKDAYS);
            boolean snapshotMatches = TriggerDispatchPolicy.alarmConfigurationMatches(
                    hasSnapshot,
                    intent.getLongExtra(ALARM_TARGET_ID, Long.MIN_VALUE),
                    intent.getIntExtra(ALARM_TYPE, Integer.MIN_VALUE),
                    intent.getIntExtra(ALARM_TIME, Integer.MIN_VALUE),
                    intent.getIntExtra(ALARM_WEEKDAYS, Integer.MIN_VALUE),
                    t.getTriggerTarget(),
                    t.getType(),
                    t.getTime(),
                    t.getWeekdays()
            );
            if (!snapshotMatches) {
                // This also upgrades a pending pre-snapshot alarm without launching it.
                queueSingleTrigger(t);
                stopForeground(true);
                return Service.START_NOT_STICKY;
            }

            startTask(t);
            queueSingleTrigger(t);
        }
        stopForeground(true);
        return Service.START_NOT_STICKY;
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        close();
        super.onDestroy();
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
