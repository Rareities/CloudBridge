package ca.pkay.rcloneexplorer.workmanager

/** Runtime protection also covers old/imported task graphs not validated by an editor. */
internal object FollowupPolicy {
    const val MAX_CHAIN_LENGTH = 32
    fun canQueue(visited: List<Long>, next: Long): Boolean =
        visited.size < MAX_CHAIN_LENGTH && next !in visited && next != -1L
}
