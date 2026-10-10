package ca.pkay.rcloneexplorer.scheduling;

import java.util.Calendar;
import java.util.TimeZone;

/** Pure civil-time calculation. Minutes use the existing backup/database format.
 * Missing DST times move forward by the gap; repeated times use Calendar's later occurrence.
 */
public final class ScheduleCalculator {
    private ScheduleCalculator() { }

    public static long nextDaily(long nowMillis, int minuteOfDay, int weekdays, TimeZone zone) {
        if (minuteOfDay < 0 || minuteOfDay >= 1440 || (weekdays & ~127) != 0) {
            throw new IllegalArgumentException("Invalid daily schedule");
        }
        if (weekdays == 0) return -1;
        Calendar day = Calendar.getInstance(zone);
        day.setTimeInMillis(nowMillis);
        // Use noon for calendar-date advancement, independently of a DST-normalized candidate.
        day.set(Calendar.HOUR_OF_DAY, 12);
        day.set(Calendar.MINUTE, 0);
        day.set(Calendar.SECOND, 0);
        day.set(Calendar.MILLISECOND, 0);
        for (int offset = 0; offset <= 7; offset++) {
            int mondayBasedDay = (day.get(Calendar.DAY_OF_WEEK) + 5) % 7;
            if ((weekdays & (1 << mondayBasedDay)) != 0) {
                Calendar candidate = (Calendar) day.clone();
                candidate.set(Calendar.HOUR_OF_DAY, minuteOfDay / 60);
                candidate.set(Calendar.MINUTE, minuteOfDay % 60);
                long next = candidate.getTimeInMillis();
                if (next > nowMillis) return next;
            }
            day.add(Calendar.DAY_OF_MONTH, 1);
        }
        throw new IllegalStateException("No future schedule occurrence");
    }

    public static long quietDelay(long nowMillis, int startMinute, int endMinute, TimeZone zone) {
        if (startMinute == -1 && endMinute == -1) return 0;
        if (startMinute < 0 || startMinute > 1439 || endMinute < 0 || endMinute > 1439 || startMinute == endMinute) {
            throw new IllegalArgumentException("Invalid quiet hours");
        }
        Calendar now = Calendar.getInstance(zone);
        now.setTimeInMillis(nowMillis);
        int minute = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE);
        boolean quiet = startMinute < endMinute ? minute >= startMinute && minute < endMinute
                : minute >= startMinute || minute < endMinute;
        if (!quiet) return 0;
        Calendar end = (Calendar) now.clone();
        end.set(Calendar.HOUR_OF_DAY, endMinute / 60);
        end.set(Calendar.MINUTE, endMinute % 60);
        end.set(Calendar.SECOND, 0);
        end.set(Calendar.MILLISECOND, 0);
        if (end.getTimeInMillis() <= nowMillis) end.add(Calendar.DAY_OF_MONTH, 1);
        return end.getTimeInMillis() - nowMillis;
    }

    public static long nextInterval(long nowMillis, long previousDue, int minutes) {
        long interval = intervalMillis(minutes);
        if (previousDue <= 0) return checkedAdd(nowMillis, interval);
        if (previousDue > nowMillis) return previousDue;
        long periods = (nowMillis - previousDue) / interval + 1;
        return checkedAdd(previousDue, checkedMultiply(periods, interval));
    }

    private static long checkedAdd(long a, long b) {
        if (b > 0 && a > Long.MAX_VALUE - b) throw new IllegalArgumentException("Schedule time overflow");
        return a + b;
    }
    private static long checkedMultiply(long a, long b) {
        if (a > Long.MAX_VALUE / b) throw new IllegalArgumentException("Schedule time overflow");
        return a * b;
    }

    public static long intervalMillis(int minutes) {
        if (minutes <= 0) throw new IllegalArgumentException("Interval must be positive");
        return minutes * 60_000L;
    }
}
