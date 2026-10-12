package io.github.rareities.protonbridge.Services;

import android.annotation.SuppressLint;
import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.util.Log;
import androidx.preference.PreferenceManager;
import io.github.rareities.protonbridge.BroadcastReceivers.TriggerReceiver;
import io.github.rareities.protonbridge.Database.DatabaseHandler;
import io.github.rareities.protonbridge.Items.Trigger;
import io.github.rareities.protonbridge.R;
import io.github.rareities.protonbridge.util.ScheduleCalculator;
import io.github.rareities.protonbridge.workmanager.SyncManager;
import java.util.Calendar;
import java.util.TimeZone;

/** Alarm adapter. Dispatch now runs through WorkManager, without starting a foreground service. */
public final class TriggerService {
    public static final String TRIGGER_RECIEVE = "TRIGGER_RECIEVE";
    public static final String TRIGGER_ID = "TRIGGER_ID";
    private static final Object SCHEDULE_LOCK = new Object();
    private Context context;
    private DatabaseHandler dbHandler;

    public TriggerService(Context context) {
        this.context = context.getApplicationContext();
        this.dbHandler = new DatabaseHandler(this.context);
    }

    public void queueTrigger() {
        synchronized (SCHEDULE_LOCK) {
            long now = System.currentTimeMillis();
            for (Trigger trigger : dbHandler.getAllTrigger()) {
                if (trigger.isEnabled() && trigger.getNextDue() > 0 && trigger.getNextDue() <= now)
                    queueDueTask(trigger);
                schedule(trigger, now);
            }
        }
    }

    public void queueSingleTrigger(Trigger trigger) {
        synchronized (SCHEDULE_LOCK) { schedule(trigger, System.currentTimeMillis()); }
    }

    public void receiveTrigger(long id) {
        synchronized (SCHEDULE_LOCK) {
            Trigger trigger = dbHandler.getTrigger(id);
            if (trigger == null || !trigger.isEnabled()) { cancelTrigger(id); return; }
            long now = System.currentTimeMillis();
            // A stale AlarmManager delivery must never execute a newly edited
            // or not-yet-scheduled task. Only a persisted, due occurrence runs.
            if (trigger.getNextDue() <= 0 || trigger.getNextDue() > now) {
                schedule(trigger, now);
                return;
            }
            queueDueTask(trigger);
            schedule(trigger, now);
        }
    }

    private void queueDueTask(Trigger trigger) {
        if (trigger.getType() == Trigger.TRIGGER_TYPE_INTERVAL) {
            Calendar now = Calendar.getInstance(ScheduleCalculator.resolveZone(trigger.getZoneId()));
            int weekday = (now.get(Calendar.DAY_OF_WEEK) + 5) % 7;
            if (!trigger.isEnabledAtDay(weekday)) return;
        }
        new SyncManager(context).queue(trigger);
    }

    @SuppressLint("ScheduleExactAlarm")
    private void schedule(Trigger trigger, long now) {
        AlarmManager alarm = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (alarm == null) throw new IllegalStateException("AlarmManager is unavailable; retry schedule dispatch");
        PendingIntent pending = getIntent(trigger.getId());
        if (!trigger.isEnabled()) {
            alarm.cancel(pending);
            cancelLegacyIntent(trigger.getId(), alarm);
            trigger.setNextDue(0);
            dbHandler.updateTrigger(trigger);
            return;
        }
        final long due;
        try {
            if (dbHandler.getTask(trigger.getTriggerTarget()) == null) throw new IllegalArgumentException("Scheduled task no longer exists");
            TimeZone zone = ScheduleCalculator.resolveZone(trigger.getZoneId());
            if (trigger.getType() == Trigger.TRIGGER_TYPE_SCHEDULE) {
                due = ScheduleCalculator.nextWeeklyTime(now, trigger.getTime(), trigger.getWeekdays() & 127, zone);
            } else if (trigger.getType() == Trigger.TRIGGER_TYPE_INTERVAL) {
                if (trigger.getTime() < 15) throw new IllegalArgumentException("Scheduled intervals must be at least 15 minutes");
                due = ScheduleCalculator.nextInterval(now, trigger.getTime(), trigger.getNextDue());
            } else throw new IllegalArgumentException("Unsupported schedule type");
        } catch (IllegalArgumentException invalid) {
            alarm.cancel(pending);
            cancelLegacyIntent(trigger.getId(), alarm);
            trigger.setEnabled(false);
            trigger.setNextDue(0);
            dbHandler.updateTrigger(trigger);
            Log.e("TriggerService", "Schedule disabled: " + invalid.getMessage());
            return;
        }
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(context);
        boolean idle = preferences.getBoolean(context.getString(R.string.shared_preferences_allow_sync_trigger_while_idle), false);
        boolean exact = trigger.getType() == Trigger.TRIGGER_TYPE_SCHEDULE &&
                (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarm.canScheduleExactAlarms());
        // Keep the existing alarm in place until the new schedule is known valid.
        // AlarmManager replaces the same PendingIntent on successful set().
        try {
            if (exact && idle) alarm.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, due, pending);
            else if (exact) alarm.setExact(AlarmManager.RTC_WAKEUP, due, pending);
            else if (idle) alarm.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, due, pending);
            else alarm.set(AlarmManager.RTC_WAKEUP, due, pending);
        } catch (SecurityException denied) {
            // Permission can be revoked between checking and scheduling. Keep a deferrable fallback.
            alarm.set(AlarmManager.RTC_WAKEUP, due, pending);
        }
        cancelLegacyIntent(trigger.getId(), alarm);
        // Only acknowledge the next occurrence once AlarmManager accepted it.
        // Platform failures propagate to ScheduleDispatchWorker so WorkManager can retry.
        trigger.setNextDue(due);
        dbHandler.updateTrigger(trigger);
    }

    public void cancelTrigger(long id) {
        AlarmManager alarm = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (alarm != null) {
            alarm.cancel(getIntent(id));
            cancelLegacyIntent(id, alarm);
        }
    }

    private PendingIntent getIntent(long id) {
        Intent intent = new Intent(context, TriggerReceiver.class).setAction(TRIGGER_RECIEVE)
                .setData(Uri.parse("protonbridge://schedule/" + id)).putExtra(TRIGGER_ID, id);
        return PendingIntent.getBroadcast(context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private void cancelLegacyIntent(long id, AlarmManager alarm) {
        Intent old = new Intent(context, TriggerReceiver.class).setAction(TRIGGER_RECIEVE);
        PendingIntent pending = PendingIntent.getBroadcast(context, (int) id, old,
                PendingIntent.FLAG_NO_CREATE | PendingIntent.FLAG_IMMUTABLE);
        if (pending != null) { alarm.cancel(pending); pending.cancel(); }
    }

}
