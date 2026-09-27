package ca.pkay.rcloneexplorer.Database

import ca.pkay.rcloneexplorer.Items.Task

/** The exact task-payload identity that a queued run is allowed to claim. */
sealed class RunTaskIdentity {
    data class LegacyTask(val taskId: Long) : RunTaskIdentity()
    data class EphemeralTask(val task: Task) : RunTaskIdentity()
}
