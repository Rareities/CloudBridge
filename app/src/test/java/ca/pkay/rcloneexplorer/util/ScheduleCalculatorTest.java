package ca.pkay.rcloneexplorer.util;

import org.junit.Test;
import java.time.Instant;
import java.util.TimeZone;
import static org.junit.Assert.*;

public class ScheduleCalculatorTest {
    private long instant(String value) { return Instant.parse(value).toEpochMilli(); }

    @Test public void sameMinuteAtDifferentHourDoesNotSkipToday() {
        long next = ScheduleCalculator.nextDailyTime(instant("2026-10-11T09:15:12Z"), 10 * 60 + 15, TimeZone.getTimeZone("UTC"));
        assertEquals(instant("2026-10-11T10:15:00Z"), next);
    }
    @Test public void exactDueTimeAlwaysAdvancesToAvoidAlarmLoop() {
        long next = ScheduleCalculator.nextDailyTime(instant("2026-10-11T10:15:00Z"), 10 * 60 + 15, TimeZone.getTimeZone("UTC"));
        assertEquals(instant("2026-10-12T10:15:00Z"), next);
    }
    @Test public void onlySelectedWeekdaysAreScheduled() {
        long next = ScheduleCalculator.nextWeeklyTime(instant("2026-10-11T09:00:00Z"), 8 * 60, 1, TimeZone.getTimeZone("UTC"));
        assertEquals(instant("2026-10-12T08:00:00Z"), next);
    }
    @Test public void dstSpringGapAdvancesThroughGap() {
        long next = ScheduleCalculator.nextDailyTime(instant("2026-09-26T12:00:00Z"), 150, TimeZone.getTimeZone("Pacific/Auckland"));
        assertEquals(instant("2026-09-26T14:30:00Z"), next);
    }
    @Test public void dstFallFoldUsesLaterOccurrence() {
        long next = ScheduleCalculator.nextDailyTime(instant("2026-04-04T11:00:00Z"), 150, TimeZone.getTimeZone("Pacific/Auckland"));
        assertEquals(instant("2026-04-04T14:30:00Z"), next);
    }
    @Test public void missedIntervalsCoalesceWithoutChangingPhase() {
        assertEquals(10_000L + 4 * 60_000L, ScheduleCalculator.nextInterval(190_001L, 1, 10_000L));
        assertEquals(Integer.MAX_VALUE * 60_000L, ScheduleCalculator.intervalMillis(Integer.MAX_VALUE));
    }
    @Test public void quietHoursHandleOvernightAndBoundaries() {
        TimeZone utc = TimeZone.getTimeZone("UTC");
        assertEquals(instant("2026-10-12T07:00:00Z"), ScheduleCalculator.afterQuietHours(instant("2026-10-11T23:00:00Z"), 22 * 60, 7 * 60, utc));
        long end = instant("2026-10-12T07:00:00Z");
        assertEquals(end, ScheduleCalculator.afterQuietHours(end, 22 * 60, 7 * 60, utc));
    }
    @Test public void invalidSchedulesDoNotSilentlyChooseDefaults() {
        assertThrows(IllegalArgumentException.class, () -> ScheduleCalculator.nextWeeklyTime(0, 100, 0, TimeZone.getTimeZone("UTC")));
        assertThrows(IllegalArgumentException.class, () -> ScheduleCalculator.intervalMillis(0));
        assertThrows(IllegalArgumentException.class, () -> ScheduleCalculator.resolveZone("Pacific/NotAPlace"));
        assertThrows(IllegalArgumentException.class, () -> ScheduleCalculator.afterQuietHours(0, 10, 10, TimeZone.getTimeZone("UTC")));
    }
}
