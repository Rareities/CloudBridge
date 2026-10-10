package ca.pkay.rcloneexplorer.scheduling

import kotlinx.serialization.Serializable
import java.util.TimeZone

/** Automatic-job policy. Empty timezone follows the device; quiet hours never affect manual jobs. */
@Serializable
data class SchedulePolicy(
    val version: Int = 1,
    val timezoneId: String = "",
    val quietStartMinute: Int = -1,
    val quietEndMinute: Int = -1,
    val unmeteredOnly: Boolean = false,
    val batteryNotLow: Boolean = true,
    val chargingOnly: Boolean = false,
    val exactTime: Boolean = false,
    val maxRuntimeMinutes: Int = 300,
    val maxRetries: Int = 3
) {
    fun validate() {
        require(version == 1) { "Unsupported schedule policy version" }
        require(timezoneId.isEmpty() || timezoneId in TimeZone.getAvailableIDs()) { "Unknown timezone" }
        require((quietStartMinute == -1 && quietEndMinute == -1) ||
            (quietStartMinute in 0..1439 && quietEndMinute in 0..1439 && quietStartMinute != quietEndMinute)) {
            "Quiet hours require different start/end times, or both disabled"
        }
        require(maxRuntimeMinutes in 1..300) { "Runtime must be between 1 and 300 minutes" }
        require(maxRetries in 0..10) { "Retries must be between 0 and 10" }
    }
    fun timezone(): TimeZone = if (timezoneId.isEmpty()) TimeZone.getDefault() else TimeZone.getTimeZone(timezoneId)
    fun quietDelay(now: Long): Long {
        validate()
        return ScheduleCalculator.quietDelay(now, quietStartMinute, quietEndMinute, timezone())
    }
}
