package ca.pkay.rcloneexplorer.Database

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import ca.pkay.rcloneexplorer.Items.Task
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class RunRepositoryInstrumentedTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().context
        context.deleteDatabase(DatabaseInfo.DATABASE_NAME)
    }

    @After
    fun tearDown() {
        context.deleteDatabase(DatabaseInfo.DATABASE_NAME)
    }

    @Test
    fun ephemeralRunIdentityCoalescesAndDispatchFailureOnlyDefersQueuedOwner() {
        val repository = RunRepository(context)
        val requestedAt = System.currentTimeMillis()
        val first = repository.queueEphemeralTask(ephemeralTask(), requestedAt = requestedAt)

        assertThrows(RunRejectedException::class.java) {
            repository.queueEphemeralTask(ephemeralTask(), requestedAt = requestedAt + 1L)
        }
        assertTrue(repository.deferQueuedDispatch(
            first.runId,
            first.ownerToken,
            "test dispatch rejection",
            finishedAt = requestedAt + 2L
        ))
        assertEquals(RunState.DEFERRED, repository.get(first.runId)!!.state)

        val second = repository.queueEphemeralTask(ephemeralTask(), requestedAt = requestedAt + 3L)
        assertEquals(RunState.QUEUED, second.state)
        assertEquals(first.profileId, second.profileId)

        val claimed = repository.claim(second.runId, second.ownerToken)
        assertEquals(RunState.PREFLIGHT, claimed.state)
        assertFalse(repository.deferQueuedDispatch(
            second.runId,
            second.ownerToken,
            "late enqueue failure",
            finishedAt = requestedAt + 4L
        ))
        assertEquals(RunState.PREFLIGHT, repository.get(second.runId)!!.state)
        assertTrue(repository.finish(second.runId, second.ownerToken, RunState.CANCELLED, "test owner finished"))

        val third = repository.queueEphemeralTask(ephemeralTask(), requestedAt = requestedAt + 5L)
        assertTrue(repository.finishQueuedBeforeExecution(
            third.runId,
            third.ownerToken,
            RunState.BLOCKED,
            "invalid persisted request",
            finishedAt = requestedAt + 6L
        ))
        assertEquals(RunState.BLOCKED, repository.get(third.runId)!!.state)
    }

    private fun ephemeralTask() = Task(-1L).apply {
        title = "Disposable ephemeral sync"
        remoteId = "test-account"
        remotePath = "disposable/root"
        localPath = "/disposable/root"
        direction = 2 // SYNC_REMOTE_TO_LOCAL
    }
}
