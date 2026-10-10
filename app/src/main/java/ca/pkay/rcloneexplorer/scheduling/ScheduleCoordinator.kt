package ca.pkay.rcloneexplorer.scheduling

import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.content.Context
import androidx.work.*
import ca.pkay.rcloneexplorer.Database.DatabaseHandler
import ca.pkay.rcloneexplorer.Items.Trigger
import ca.pkay.rcloneexplorer.Services.TriggerService
import ca.pkay.rcloneexplorer.BroadcastReceivers.TriggerReceiver
import ca.pkay.rcloneexplorer.util.ConfigEpoch
import ca.pkay.rcloneexplorer.workmanager.SyncManager
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.concurrent.TimeUnit

/** Durable due dates coalesce missed intervals; unique task work prevents an automatic backlog. */
object ScheduleCoordinator {
    private fun state(c: Context) = c.getSharedPreferences("schedule_due", Context.MODE_PRIVATE)
    private fun fingerprint(t: Trigger, epoch: Long) = "$epoch:${t.type}:${t.time}:${t.getWeekdays().toInt()}:${t.triggerTarget}:${Json.encodeToString(t.schedulePolicy)}"
    @SuppressLint("ScheduleExactAlarm")
    @JvmStatic @Synchronized fun schedule(c: Context, t: Trigger) {
        if (!t.isEnabled) { cancel(c,t.id); return }
        t.schedulePolicy.validate()
        val epoch = ConfigEpoch.current(c)
        val prefs = state(c)
        val key = t.id.toString()
        val signature = fingerprint(t, epoch)
        val now = System.currentTimeMillis()
        val due = if (prefs.getString("${key}_signature", "") == signature) prefs.getLong(key, 0) else 0
        val next = if (due > 0) due else calculate(t, now, 0)
        if (next < 0 || t.getWeekdays().toInt()==0) { cancel(c,t.id); return }
        check(prefs.edit().putLong(key,next).putString("${key}_signature",signature).commit())
        val delay=(next-now).coerceAtLeast(0).coerceAtLeast(t.schedulePolicy.quietDelay(now)).coerceAtLeast(weekdayDelay(t,now))
        val alarm=c.getSystemService(Context.ALARM_SERVICE) as? AlarmManager
        if(t.schedulePolicy.exactTime && alarm!=null && (Build.VERSION.SDK_INT<31 || alarm.canScheduleExactAlarms())) {
            try {
                WorkManager.getInstance(c).cancelUniqueWork("schedule-trigger-${t.id}")
                alarm.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP,now+delay,alarmIntent(c,t.id,epoch))
                return
            } catch(denied:SecurityException) { /* revoked permission: fall back to deferrable work */ }
        }
        val request = OneTimeWorkRequestBuilder<ScheduledDispatchWorker>()
            .setInputData(workDataOf("trigger" to t.id, "epoch" to epoch, "due" to next, "signature" to signature))
            .setInitialDelay(delay,TimeUnit.MILLISECONDS).build()
        WorkManager.getInstance(c).enqueueUniqueWork("schedule-trigger-${t.id}",ExistingWorkPolicy.REPLACE,request)
    }
    private fun weekdayDelay(t:Trigger,now:Long):Long {
        if(t.getWeekdays().toInt()==0) return Long.MAX_VALUE/2
        val c=java.util.Calendar.getInstance(t.schedulePolicy.timezone()).apply { timeInMillis=now }
        val day=(c.get(java.util.Calendar.DAY_OF_WEEK)+5)%7
        return if(t.getWeekdays().toInt() and (1 shl day)!=0) 0 else
            (ScheduleCalculator.nextDaily(now,0,t.getWeekdays().toInt(),t.schedulePolicy.timezone())-now).coerceAtLeast(0)
    }
    private fun alarmIntent(c:Context,id:Long,epoch:Long):PendingIntent {
        val intent=Intent(c,TriggerReceiver::class.java).setAction(TriggerService.TRIGGER_RECIEVE)
            .setData(android.net.Uri.parse("cloudbridge://trigger/$id/$epoch"))
            .putExtra(TriggerService.TRIGGER_ID,id).putExtra(ConfigEpoch.WORK_KEY,epoch)
        return PendingIntent.getBroadcast(c,id.toInt(),intent,PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }
    @JvmStatic @Synchronized fun alarmDelivered(c:Context,id:Long,epoch:Long) {
        if(epoch!=ConfigEpoch.current(c)) return
        val prefs=state(c)
        val due=prefs.getLong(id.toString(),-1)
        val signature=prefs.getString("${id}_signature","") ?: ""
        val request=OneTimeWorkRequestBuilder<ScheduledDispatchWorker>().setInputData(
            workDataOf("trigger" to id,"epoch" to epoch,"due" to due,"signature" to signature)).build()
        WorkManager.getInstance(c).enqueueUniqueWork("schedule-trigger-$id",ExistingWorkPolicy.KEEP,request)
    }
    private fun calculate(t: Trigger, now: Long, oldDue: Long): Long = when(t.type) {
        Trigger.TRIGGER_TYPE_SCHEDULE -> ScheduleCalculator.nextDaily(now,t.time,t.getWeekdays().toInt(),t.schedulePolicy.timezone())
        Trigger.TRIGGER_TYPE_INTERVAL -> ScheduleCalculator.nextInterval(now,oldDue,t.time)
        else -> -1
    }
    @JvmStatic @Synchronized fun cancel(c:Context,id:Long) {
        (c.getSystemService(Context.ALARM_SERVICE) as? AlarmManager)?.cancel(alarmIntent(c,id,ConfigEpoch.current(c)))
        WorkManager.getInstance(c).cancelUniqueWork("schedule-trigger-$id")
        check(state(c).edit().remove(id.toString()).remove("${id}_signature").commit())
    }
    @JvmStatic fun nextRun(c:Context,t:Trigger):Long {
        if (!t.isEnabled) return -1
        val prefs=state(c)
        return if(prefs.getString("${t.id}_signature","")==fingerprint(t,ConfigEpoch.current(c)))
            prefs.getLong(t.id.toString(),-1) else calculate(t,System.currentTimeMillis(),0)
    }
    @JvmStatic @Synchronized fun resetCivil(c:Context) {
        val prefs=state(c)
        val editor=prefs.edit()
        DatabaseHandler(c).allTrigger.filter { it.type==Trigger.TRIGGER_TYPE_SCHEDULE }.forEach {
            editor.remove(it.id.toString()).remove("${it.id}_signature")
        }
        check(editor.commit())
    }
    @Synchronized fun dispatch(c:Context,id:Long,epoch:Long,due:Long,signature:String):Boolean {
        if(epoch!=ConfigEpoch.current(c)) return true
        val t=DatabaseHandler(c).getTrigger(id) ?: return true
        if(!t.isEnabled || fingerprint(t,epoch)!=signature) return true
        val prefs=state(c)
        if(prefs.getLong(id.toString(),-1)!=due) return true
        val now=System.currentTimeMillis()
        val quiet=t.schedulePolicy.quietDelay(now)
        if(now<due || quiet>0 || weekdayDelay(t,now)>0) { schedule(c,t); return true }
        // Enqueue before advancing. A crash can repeat enqueue, but KEEP coalesces an outstanding task.
        SyncManager(c).queueScheduled(t,epoch)
        check(prefs.edit().putLong(id.toString(),calculate(t,now,due)).commit())
        schedule(c,t)
        return true
    }
}

class ScheduledDispatchWorker(c:Context,p:WorkerParameters):Worker(c,p) {
    override fun doWork():Result = try {
        if(ScheduleCoordinator.dispatch(applicationContext,inputData.getLong("trigger",-1),
                inputData.getLong("epoch",-1),inputData.getLong("due",-1),
                inputData.getString("signature") ?: "")) Result.success() else Result.retry()
    } catch(e:Exception) { Result.failure() }
}
