package ca.pkay.rcloneexplorer.workmanager

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Calendar
import java.util.GregorianCalendar
import java.util.Locale
import java.util.TimeZone

class ScheduleTimeCalculatorTest {
    private val utc = TimeZone.getTimeZone("UTC")

    @Test
    fun sameMinuteEarlierInTheHourKeepsTodaysOccurrence() {
        val now = instant(2024, Calendar.JANUARY, 8, 9, 15, 30, utc)
        val expected = instant(2024, Calendar.JANUARY, 8, 10, 15, 0, utc)

        assertEquals(expected, ScheduleTimeCalculator.nextOccurrence(now, 10 * 60 + 15, 0x7f, utc))
    }

    @Test
    fun occurrenceAtCurrentMinuteIsStrictlyScheduledForNextEnabledDay() {
        val now = instant(2024, Calendar.JANUARY, 8, 10, 15, 0, utc)
        val expected = instant(2024, Calendar.JANUARY, 9, 10, 15, 0, utc)

        assertEquals(expected, ScheduleTimeCalculator.nextOccurrence(now, 10 * 60 + 15, 0x7f, utc))
    }

    @Test
    fun pastOccurrenceSkipsToNextSelectedWeekday() {
        val now = instant(2024, Calendar.JANUARY, 8, 10, 0, 0, utc) // Monday
        val wednesdayOnly = 1 shl 2
        val expected = instant(2024, Calendar.JANUARY, 10, 9, 0, 0, utc)

        assertEquals(expected, ScheduleTimeCalculator.nextOccurrence(now, 9 * 60, wednesdayOnly, utc))
    }

    @Test
    fun sundayBitIsCorrectAcrossWeekBoundary() {
        val now = instant(2024, Calendar.JANUARY, 13, 23, 59, 0, utc) // Saturday
        val sundayOnly = 1 shl 6
        val expected = instant(2024, Calendar.JANUARY, 14, 0, 1, 0, utc)

        assertEquals(expected, ScheduleTimeCalculator.nextOccurrence(now, 1, sundayOnly, utc))
    }

    @Test
    fun noWeekdaysOrInvalidMinuteHasNoOccurrence() {
        val now = instant(2024, Calendar.JANUARY, 8, 9, 0, 0, utc)

        assertNull(ScheduleTimeCalculator.nextOccurrence(now, 10, 0, utc))
        assertNull(ScheduleTimeCalculator.nextOccurrence(now, -1, 0x7f, utc))
        assertNull(ScheduleTimeCalculator.nextOccurrence(now, 24 * 60, 0x7f, utc))
        assertNull(ScheduleTimeCalculator.nextOccurrence(now, 10, -1, utc))
        assertNull(ScheduleTimeCalculator.nextOccurrence(now, 10, 0x80, utc))
    }

    @Test
    fun daylightSavingGapUsesCalendarLocalTimeNormalization() {
        val losAngeles = TimeZone.getTimeZone("America/Los_Angeles")
        val now = instant(2024, Calendar.MARCH, 10, 0, 0, 0, losAngeles)
        val next = ScheduleTimeCalculator.nextOccurrence(now, 2 * 60 + 30, 1 shl 6, losAngeles)
        val local = GregorianCalendar(losAngeles, Locale.ROOT).apply { timeInMillis = requireNotNull(next) }

        assertEquals(Calendar.SUNDAY, local.get(Calendar.DAY_OF_WEEK))
        assertEquals(3, local.get(Calendar.HOUR_OF_DAY))
        assertEquals(30, local.get(Calendar.MINUTE))
    }

    private fun instant(
        year: Int,
        month: Int,
        day: Int,
        hour: Int,
        minute: Int,
        second: Int,
        timeZone: TimeZone
    ): Long = GregorianCalendar(timeZone, Locale.ROOT).apply {
        clear()
        set(year, month, day, hour, minute, second)
    }.timeInMillis
}
