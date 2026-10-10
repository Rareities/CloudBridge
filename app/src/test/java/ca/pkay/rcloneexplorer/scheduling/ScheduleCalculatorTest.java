package ca.pkay.rcloneexplorer.scheduling;

import org.junit.Test;
import java.util.Calendar;
import java.util.TimeZone;
import static org.junit.Assert.*;

public class ScheduleCalculatorTest {
    private long time(String zone, int year, int month, int day, int hour, int minute) {
        Calendar c = Calendar.getInstance(TimeZone.getTimeZone(zone));
        c.clear();
        c.set(year, month - 1, day, hour, minute);
        return c.getTimeInMillis();
    }

    @Test public void sameMinuteDifferentHourDoesNotAdd24Hours() {
        long now = time("UTC", 2026, 10, 10, 14, 30) + 27_456;
        assertEquals(time("UTC", 2026, 10, 11, 9, 30),
                ScheduleCalculator.nextDaily(now, 570, 127, TimeZone.getTimeZone("UTC")));
    }

    @Test public void exactOccurrenceAdvancesAndClearsSubMinuteFields() {
        long now = time("UTC", 2026, 10, 10, 9, 30);
        assertEquals(time("UTC", 2026, 10, 11, 9, 30),
                ScheduleCalculator.nextDaily(now, 570, 127, TimeZone.getTimeZone("UTC")));
    }

    @Test public void calendarDaysRespectSpringDst() {
        long now = time("Europe/Berlin", 2026, 3, 28, 9, 30);
        long next = ScheduleCalculator.nextDaily(now, 570, 127, TimeZone.getTimeZone("Europe/Berlin"));
        assertEquals(time("Europe/Berlin", 2026, 3, 29, 9, 30), next);
        assertEquals(23 * 60 * 60_000L, next - now);
    }

    @Test public void missingDstTimeMovesForwardByGap() {
        assertEquals(time("Europe/Berlin", 2026, 3, 29, 3, 30),
                ScheduleCalculator.nextDaily(time("Europe/Berlin", 2026, 3, 28, 23, 0),
                        150, 127, TimeZone.getTimeZone("Europe/Berlin")));
    }

    @Test public void repeatedDstTimeUsesLaterOccurrence() {
        assertEquals(time("UTC", 2026, 10, 25, 1, 30),
                ScheduleCalculator.nextDaily(time("Europe/Berlin", 2026, 10, 24, 23, 0),
                        150, 127, TimeZone.getTimeZone("Europe/Berlin")));
    }

    @Test public void weekdaysAndDisabledMaskAreRespected() {
        long now = time("UTC", 2026, 10, 10, 14, 30);
        assertEquals(time("UTC", 2026, 10, 12, 9, 30),
                ScheduleCalculator.nextDaily(now, 570, 1, TimeZone.getTimeZone("UTC")));
        assertEquals(-1, ScheduleCalculator.nextDaily(now, 570, 0, TimeZone.getTimeZone("UTC")));
    }

    @Test public void longIntervalsDoNotOverflow() {
        assertEquals(3_000_000_000L, ScheduleCalculator.intervalMillis(50_000));
        assertEquals(128_849_018_820_000L, ScheduleCalculator.intervalMillis(Integer.MAX_VALUE));
    }

    @Test(expected = IllegalArgumentException.class) public void rejectsZeroInterval() {
        ScheduleCalculator.intervalMillis(0);
    }
    @Test(expected = IllegalArgumentException.class) public void rejectsInvalidClock() {
        ScheduleCalculator.nextDaily(0, 1440, 127, TimeZone.getTimeZone("UTC"));
    }
    @Test public void quietHoursCrossMidnightAndBoundaries() {
        TimeZone zone=TimeZone.getTimeZone("UTC");
        assertEquals(9*60*60_000L,ScheduleCalculator.quietDelay(time("UTC",2026,10,10,22,0),1320,420,zone));
        assertEquals(6*60*60_000L,ScheduleCalculator.quietDelay(time("UTC",2026,10,11,1,0),1320,420,zone));
        assertEquals(0,ScheduleCalculator.quietDelay(time("UTC",2026,10,11,7,0),1320,420,zone));
        assertEquals(0,ScheduleCalculator.quietDelay(time("UTC",2026,10,11,12,0),-1,-1,zone));
    }
    @Test public void quietHoursUseCivilDstEnd() {
        assertEquals(8*60*60_000L,ScheduleCalculator.quietDelay(time("Europe/Berlin",2026,3,28,22,0),1320,420,TimeZone.getTimeZone("Europe/Berlin")));
    }
    @Test public void missedIntervalsCoalesceWithOriginalAnchor() {
        assertEquals(601_000L,ScheduleCalculator.nextInterval(599_999L,1_000L,1));
        assertEquals(61_000L,ScheduleCalculator.nextInterval(1_000L,1_000L,1));
        assertEquals(61_000L,ScheduleCalculator.nextInterval(2_000L,61_000L,1));
    }
    @Test(expected=IllegalArgumentException.class) public void dueDateOverflowRejected() {
        ScheduleCalculator.nextInterval(Long.MAX_VALUE-1,0,1);
    }
    @Test(expected=IllegalArgumentException.class) public void quietHoursMustHaveAnEnd() {
        ScheduleCalculator.quietDelay(0,100,100,TimeZone.getTimeZone("UTC"));
    }
}
