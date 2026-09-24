package ca.pkay.rcloneexplorer.workmanager;

import org.junit.Test;

import java.util.Calendar;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ScheduledTriggerExecutionPolicyTest {
    @Test
    public void manualWorkWithoutTriggerIdIsUnaffected() {
        assertTrue(ScheduledTriggerExecutionPolicy.shouldLaunch(null, false, false, false));
        assertTrue(ScheduledTriggerExecutionPolicy.shouldLaunch(null, null, false, false));
    }

    @Test
    public void scheduledWorkRequiresPersistedEnabledTrigger() {
        assertTrue(ScheduledTriggerExecutionPolicy.shouldLaunch(17L, true, true, true));
        assertFalse(ScheduledTriggerExecutionPolicy.shouldLaunch(17L, false, true, true));
        assertFalse(ScheduledTriggerExecutionPolicy.shouldLaunch(17L, null, true, true));
        assertFalse(ScheduledTriggerExecutionPolicy.shouldLaunch(Long.MIN_VALUE, true, true, true));
    }

    @Test
    public void editedConfigurationAndDisabledWeekdayRejectQueuedWork() {
        assertFalse(ScheduledTriggerExecutionPolicy.shouldLaunch(17L, true, false, true));
        assertFalse(ScheduledTriggerExecutionPolicy.shouldLaunch(17L, true, true, false));
        assertTrue(ScheduledTriggerExecutionPolicy.shouldLaunch(17L, true, true, true));
    }

    @Test
    public void calendarWeekdaysMapToMondayZeroThroughSundaySix() {
        assertEquals(0, ScheduledTriggerExecutionPolicy.weekdayFromCalendar(Calendar.MONDAY));
        assertEquals(1, ScheduledTriggerExecutionPolicy.weekdayFromCalendar(Calendar.TUESDAY));
        assertEquals(2, ScheduledTriggerExecutionPolicy.weekdayFromCalendar(Calendar.WEDNESDAY));
        assertEquals(3, ScheduledTriggerExecutionPolicy.weekdayFromCalendar(Calendar.THURSDAY));
        assertEquals(4, ScheduledTriggerExecutionPolicy.weekdayFromCalendar(Calendar.FRIDAY));
        assertEquals(5, ScheduledTriggerExecutionPolicy.weekdayFromCalendar(Calendar.SATURDAY));
        assertEquals(6, ScheduledTriggerExecutionPolicy.weekdayFromCalendar(Calendar.SUNDAY));
        assertEquals(-1, ScheduledTriggerExecutionPolicy.weekdayFromCalendar(0));
    }
}
