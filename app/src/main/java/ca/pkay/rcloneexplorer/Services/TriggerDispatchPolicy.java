package ca.pkay.rcloneexplorer.Services;

/** Pure eligibility rules shared by persisted trigger scheduling and delivery. */
final class TriggerDispatchPolicy {
    private static final long MILLIS_PER_MINUTE = 60_000L;

    private TriggerDispatchPolicy() {}

    static Long intervalMillisIfEnabled(boolean enabled, int intervalMinutes) {
        if (!enabled || intervalMinutes <= 0) return null;
        return (long) intervalMinutes * MILLIS_PER_MINUTE;
    }

    static boolean shouldDispatch(boolean enabled, boolean dayEnabled) {
        return enabled && dayEnabled;
    }

    static boolean alarmConfigurationMatches(
            boolean hasSnapshot,
            long alarmTargetId,
            int alarmType,
            int alarmTime,
            int alarmWeekdays,
            long currentTargetId,
            int currentType,
            int currentTime,
            int currentWeekdays) {
        return hasSnapshot
                && alarmTargetId == currentTargetId
                && alarmType == currentType
                && alarmTime == currentTime
                && alarmWeekdays == currentWeekdays;
    }
}
