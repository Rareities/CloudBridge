package ca.pkay.rcloneexplorer.workmanager;

import java.util.Calendar;

/** Pure fail-closed gate for worker requests originating from persisted triggers. */
public final class ScheduledTriggerExecutionPolicy {
    private ScheduledTriggerExecutionPolicy() {}

    public static boolean shouldLaunch(
            Long triggerId,
            Boolean persistedEnabled,
            boolean queuedConfigurationMatches,
            boolean currentDayEnabled) {
        return triggerId == null || (triggerId != Long.MIN_VALUE && Boolean.TRUE.equals(persistedEnabled)
                && queuedConfigurationMatches
                && currentDayEnabled);
    }

    public static int weekdayFromCalendar(int calendarDay) {
        switch (calendarDay) {
            case Calendar.MONDAY: return 0;
            case Calendar.TUESDAY: return 1;
            case Calendar.WEDNESDAY: return 2;
            case Calendar.THURSDAY: return 3;
            case Calendar.FRIDAY: return 4;
            case Calendar.SATURDAY: return 5;
            case Calendar.SUNDAY: return 6;
            default: return -1;
        }
    }
}
