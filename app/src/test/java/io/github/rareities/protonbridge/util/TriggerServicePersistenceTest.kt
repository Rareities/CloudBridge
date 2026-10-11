package io.github.rareities.protonbridge.util

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
import io.github.rareities.protonbridge.Database.DatabaseHandler
import io.github.rareities.protonbridge.Items.Task
import io.github.rareities.protonbridge.Items.Trigger
import io.github.rareities.protonbridge.Services.TriggerService
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class TriggerServicePersistenceTest {
    private val context: Application get() = ApplicationProvider.getApplicationContext()

    private fun scheduledTask(): Trigger {
        val db = DatabaseHandler(context)
        val task = db.createTask(Task(0).apply { title = "Transfer"; direction = 1 })
        return db.createTrigger(Trigger(Trigger.TRIGGER_ID_DOESNTEXIST).apply {
            title = "Daily"
            triggerTarget = task.id
            type = Trigger.TRIGGER_TYPE_SCHEDULE
            time = 12 * 60
            isEnabled = true
            nextDue = 123L
        })
    }

    @Test fun acceptedAlarmPersistsItsNextOccurrence() {
        val trigger = scheduledTask()
        TriggerService(context).queueSingleTrigger(trigger)
        val saved = DatabaseHandler(context).getTrigger(trigger.id)!!
        assertTrue(saved.nextDue > System.currentTimeMillis())
        assertEquals(saved.nextDue, trigger.nextDue)
    }

    @Test fun missingAlarmServicePropagatesAndDoesNotAdvanceDue() {
        val trigger = scheduledTask()
        val missingAlarm = object : ContextWrapper(context) {
            override fun getApplicationContext(): Context = this
            override fun getSystemService(name: String): Any? =
                if (name == Context.ALARM_SERVICE) null else super.getSystemService(name)
        }
        assertThrows(IllegalStateException::class.java) {
            TriggerService(missingAlarm).queueSingleTrigger(trigger)
        }
        assertEquals(123L, DatabaseHandler(context).getTrigger(trigger.id)!!.nextDue)
    }
}
