package ca.pkay.rcloneexplorer.util

import android.app.Application
import ca.pkay.rcloneexplorer.Items.Task
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class EffectiveBisyncTaskTest {
    @Test fun resolvedDefaultsBindApprovalWithoutResettingOrEditingSavedOptions() {
        val saved = Task(42).apply {
            title = "OAuth account sync"; direction = 6; localPath = "/tmp/left"
            remoteId = "drive"; remotePath = "folder"; bisyncCheckAccess = true
            bisyncAdvancedOptions = "--checkers=2"; transfers = null
        }
        val before = saved.asJSON().toString()
        val first = BisyncPolicy.effectiveTask(saved, 4)
        val second = BisyncPolicy.effectiveTask(saved, 8)
        assertEquals(saved.bisyncUUID, first.bisyncUUID)
        assertEquals(saved.title, first.title)
        assertEquals(saved.remotePath, first.remotePath)
        assertEquals(saved.bisyncAdvancedOptions, first.bisyncAdvancedOptions)
        assertTrue(first.bisyncCheckAccess)
        assertEquals(before, saved.asJSON().toString())
        assertNotEquals(BisyncPolicy.fingerprint(first, emptyList(), "account-generation"),
            BisyncPolicy.fingerprint(second, emptyList(), "account-generation"))
    }
}
