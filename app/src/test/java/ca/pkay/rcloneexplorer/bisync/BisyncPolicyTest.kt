package ca.pkay.rcloneexplorer.bisync

import ca.pkay.rcloneexplorer.Items.Task
import org.junit.Assert.*
import org.junit.Test

class BisyncPolicyTest {
    private fun task() = Task(1).apply {
        stableUUID = "9018924c-8f09-4ba5-bd35-c4fdc3bc653a"
        localPath = "/tmp/cloudbridge/photos"
        remoteId = "proton"
        remotePath = "/photos"
    }

    @Test fun identityChangesWhenEndpointsOrDeletionPolicyChange() {
        val original = task()
        val digest = BisyncPolicy.fingerprint(original)
        assertNotEquals(digest, BisyncPolicy.fingerprint(task().apply { remotePath = "/other" }))
        assertNotEquals(digest, BisyncPolicy.fingerprint(task().apply { bisyncMaxDelete = 20 }))
        assertEquals(digest, BisyncPolicy.fingerprint(task().apply { title = "renamed"; wifionly = true }))
    }

    @Test fun normalizationKeepsSameNativeEndpointIdentity() {
        assertEquals(BisyncPolicy.fingerprint(task()),
            BisyncPolicy.fingerprint(task().apply { localPath = "/tmp/cloudbridge/./photos" }))
    }

    @Test(expected = IllegalArgumentException::class)
    fun refusesFilterChangesUntilSafeMigrationExists() {
        BisyncPolicy.validate(task().apply { filterId = 2 })
    }

    @Test(expected = IllegalArgumentException::class)
    fun refusesDestructiveUnboundedDeletion() {
        BisyncPolicy.validate(task().apply { bisyncMaxDelete = 100 })
    }

    @Test(expected = IllegalArgumentException::class)
    fun refusesSAFOrRelativeLocalRoot() {
        BisyncPolicy.validate(task().apply { localPath = "content://documents/tree/photos" })
    }

    @Test(expected = IllegalArgumentException::class)
    fun refusesWorkdirTraversalInJobIdentity() {
        BisyncPolicy.validate(task().apply { stableUUID = "../../other" })
    }
}
