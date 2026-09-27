package ca.pkay.rcloneexplorer.workmanager

import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/** Pure wall-clock calculation for a trigger's next enabled local weekday. */
object ScheduleTimeCalculator {
    private const val MINUTES_PER_DAY = 24 * 60
    private const val WEEKDAY_MASK = 0x7f

    /**
     * Returns the first scheduled local time strictly after [afterMillis], or null when the
     * configured minute or weekday mask is invalid. Weekday bits use Monday=0 through Sunday=6.
     */
    @JvmStatic
    @JvmOverloads
    fun nextOccurrence(
        afterMillis: Long,
        minuteOfDay: Int,
        weekdaysMask: Int,
        timeZone: TimeZone = TimeZone.getDefault()
    ): Long? {
        if (minuteOfDay !in 0 until MINUTES_PER_DAY) return null
        if (weekdaysMask < 0 || (weekdaysMask and WEEKDAY_MASK) != weekdaysMask) return null
        val enabledWeekdays = weekdaysMask
        if (enabledWeekdays == 0) return null

        val now = Calendar.getInstance(timeZone, Locale.ROOT).apply {
            timeInMillis = afterMillis
        }

        // There are only seven local weekdays. Eight candidates also handles the exact-time
        // boundary without relying on an assumption about Calendar's current weekday mapping.
        for (dayOffset in 0..7) {
            val candidate = (now.clone() as Calendar).apply {
                add(Calendar.DAY_OF_YEAR, dayOffset)
                set(Calendar.HOUR_OF_DAY, minuteOfDay / 60)
                set(Calendar.MINUTE, minuteOfDay % 60)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }
            val weekdayBit = when (candidate.get(Calendar.DAY_OF_WEEK)) {
                Calendar.MONDAY -> 0
                Calendar.TUESDAY -> 1
                Calendar.WEDNESDAY -> 2
                Calendar.THURSDAY -> 3
                Calendar.FRIDAY -> 4
                Calendar.SATURDAY -> 5
                Calendar.SUNDAY -> 6
                else -> return null
            }
            val occurrence = candidate.timeInMillis
            if ((enabledWeekdays and (1 shl weekdayBit)) != 0 && occurrence > afterMillis) {
                return occurrence
            }
        }
        return null
    }
}
