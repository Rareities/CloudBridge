package io.github.rareities.protonbridge.util;

import java.util.Calendar;
import java.util.TimeZone;

/** Pure schedule calculations shared by the editor and alarm adapter. */
public final class ScheduleCalculator {
    private ScheduleCalculator() {}

    public static long nextDailyTime(long now, int minutes, TimeZone zone) {
        return nextWeeklyTime(now, minutes, 127, zone);
    }

    /** Monday is bit zero. DST gaps advance through the gap; folds use the later occurrence. */
    public static long nextWeeklyTime(long now, int minutes, int weekdays, TimeZone zone) {
        if (minutes < 0 || minutes >= 1440) throw new IllegalArgumentException("Time must be between 00:00 and 23:59");
        if ((weekdays & 127) == 0 || (weekdays & ~127) != 0) throw new IllegalArgumentException("Select at least one valid weekday");
        Calendar date = Calendar.getInstance(zone);
        date.setTimeInMillis(now);
        date.set(Calendar.HOUR_OF_DAY, 12);
        date.set(Calendar.MINUTE, 0);
        date.set(Calendar.SECOND, 0);
        date.set(Calendar.MILLISECOND, 0);
        for (int offset = 0; offset <= 7; offset++) {
            int weekday = (date.get(Calendar.DAY_OF_WEEK) + 5) % 7;
            if ((weekdays & (1 << weekday)) != 0) {
                Calendar candidate = Calendar.getInstance(zone);
                candidate.clear();
                candidate.set(date.get(Calendar.YEAR), date.get(Calendar.MONTH), date.get(Calendar.DAY_OF_MONTH), minutes / 60, minutes % 60, 0);
                long due = candidate.getTimeInMillis();
                if (due > now) return due;
            }
            date.add(Calendar.DAY_OF_MONTH, 1);
        }
        throw new IllegalArgumentException("Cannot calculate next occurrence");
    }

    public static long intervalMillis(int minutes) {
        if (minutes <= 0) throw new IllegalArgumentException("Interval must be positive");
        return minutes * 60_000L;
    }

    /** Coalesce missed intervals and keep the original cadence rather than drifting after each run. */
    public static long nextInterval(long now, int minutes, long previousDue) {
        long period = intervalMillis(minutes);
        if (previousDue <= 0) return Math.addExact(now, period);
        if (previousDue > now) return previousDue;
        long skipped = (now - previousDue) / period + 1;
        return Math.addExact(previousDue, Math.multiplyExact(skipped, period));
    }

    public static long afterQuietHours(long now, int start, int end, TimeZone zone) {
        if (start == -1 && end == -1) return now;
        if (start < 0 || start >= 1440 || end < 0 || end >= 1440 || start == end)
            throw new IllegalArgumentException("Quiet hours require two different valid times");
        Calendar current = Calendar.getInstance(zone);
        current.setTimeInMillis(now);
        int minute = current.get(Calendar.HOUR_OF_DAY) * 60 + current.get(Calendar.MINUTE);
        boolean quiet = start < end ? minute >= start && minute < end : minute >= start || minute < end;
        if (!quiet) return now;
        Calendar due = (Calendar) current.clone();
        if (start > end && minute >= start) due.add(Calendar.DAY_OF_MONTH, 1);
        due.set(Calendar.HOUR_OF_DAY, end / 60);
        due.set(Calendar.MINUTE, end % 60);
        due.set(Calendar.SECOND, 0);
        due.set(Calendar.MILLISECOND, 0);
        return due.getTimeInMillis();
    }

    public static TimeZone resolveZone(String id) {
        if (id == null || id.trim().isEmpty()) return TimeZone.getDefault();
        for (String known : TimeZone.getAvailableIDs()) if (known.equals(id)) return TimeZone.getTimeZone(id);
        throw new IllegalArgumentException("Unknown timezone: " + id);
    }
}
