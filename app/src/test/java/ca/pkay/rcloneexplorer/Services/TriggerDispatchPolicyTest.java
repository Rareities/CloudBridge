package ca.pkay.rcloneexplorer.Services;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class TriggerDispatchPolicyTest {
    @Test
    public void disabledOrInvalidIntervalDoesNotSchedule() {
        assertNull(TriggerDispatchPolicy.intervalMillisIfEnabled(false, 15));
        assertNull(TriggerDispatchPolicy.intervalMillisIfEnabled(true, 0));
        assertNull(TriggerDispatchPolicy.intervalMillisIfEnabled(true, -1));
    }

    @Test
    public void enabledIntervalUsesLongMillisecondsWithoutIntegerOverflow() {
        assertEquals(Long.valueOf(60_000L), TriggerDispatchPolicy.intervalMillisIfEnabled(true, 1));
        assertEquals(Long.valueOf((long) Integer.MAX_VALUE * 60_000L),
                TriggerDispatchPolicy.intervalMillisIfEnabled(true, Integer.MAX_VALUE));
    }

    @Test
    public void dispatchRequiresEnabledTriggerOnEnabledDay() {
        assertFalse(TriggerDispatchPolicy.shouldDispatch(false, true));
        assertFalse(TriggerDispatchPolicy.shouldDispatch(true, false));
        assertTrue(TriggerDispatchPolicy.shouldDispatch(true, true));
    }

    @Test
    public void alarmSnapshotMustMatchCurrentTargetAndSchedule() {
        assertTrue(TriggerDispatchPolicy.alarmConfigurationMatches(
                true, 12L, 0, 480, 127, 12L, 0, 480, 127));
        assertFalse(TriggerDispatchPolicy.alarmConfigurationMatches(
                false, 12L, 0, 480, 127, 12L, 0, 480, 127));
        assertFalse(TriggerDispatchPolicy.alarmConfigurationMatches(
                true, 13L, 0, 480, 127, 12L, 0, 480, 127));
        assertFalse(TriggerDispatchPolicy.alarmConfigurationMatches(
                true, 12L, 1, 480, 127, 12L, 0, 480, 127));
        assertFalse(TriggerDispatchPolicy.alarmConfigurationMatches(
                true, 12L, 0, 481, 127, 12L, 0, 480, 127));
        assertFalse(TriggerDispatchPolicy.alarmConfigurationMatches(
                true, 12L, 0, 480, 126, 12L, 0, 480, 127));
    }
}
