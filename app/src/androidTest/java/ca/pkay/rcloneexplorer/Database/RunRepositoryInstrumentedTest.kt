package ca.pkay.rcloneexplorer.Database

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import ca.pkay.rcloneexplorer.Items.Filter
import ca.pkay.rcloneexplorer.Items.FilterEntry
import ca.pkay.rcloneexplorer.Items.Task
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
        assertNull(first.filterSnapshot)

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

        val otherTask = ephemeralTask().apply {
            remotePath = "different-disposable-root"
        }
        val otherRun = repository.queueEphemeralTask(otherTask, requestedAt = requestedAt + 4L)
        assertThrows(RunRejectedException::class.java) {
            repository.claim(
                second.runId,
                second.ownerToken,
                RunTaskIdentity.EphemeralTask(otherTask)
            )
        }
        assertEquals(RunState.QUEUED, repository.get(second.runId)!!.state)
        assertEquals(RunState.QUEUED, repository.get(otherRun.runId)!!.state)
        assertThrows(RunRejectedException::class.java) {
            repository.claim(
                second.runId,
                second.ownerToken,
                RunTaskIdentity.LegacyTask(987_654L)
            )
        }
        assertEquals(RunState.QUEUED, repository.get(second.runId)!!.state)

        val claimed = repository.claim(
            second.runId,
            second.ownerToken,
            RunTaskIdentity.EphemeralTask(ephemeralTask())
        )
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

    @Test
    fun filterRulesAreFrozenOnTheRunAndClearedAfterTerminalState() {
        val repository = RunRepository(context)
        val database = DatabaseHandler(context)
        val originalRules = "+keep.txt${System.lineSeparator()}-exclude.tmp${System.lineSeparator()}"
        val filter = Filter(-1L).apply {
            title = "Disposable run filter"
            setFiltersRaw(originalRules)
        }
        database.createFilter(filter)

        val task = ephemeralTask().apply { filterId = filter.id }
        val run = repository.queueEphemeralTask(task)
        assertEquals(originalRules, run.filterSnapshot)

        filter.setFiltersRaw("+edited-after-queue.txt${System.lineSeparator()}")
        database.updateFilter(filter)
        val claimed = repository.claim(
            run.runId,
            run.ownerToken,
            RunTaskIdentity.EphemeralTask(task)
        )
        assertEquals(originalRules, claimed.filterSnapshot)
        val parsed = Filter(filter.id).apply { setFiltersRaw(claimed.filterSnapshot!!) }.getFilters()
        assertEquals(2, parsed.size)
        assertEquals(FilterEntry.FILTER_INCLUDE, parsed[0].filterType)
        assertEquals("keep.txt", parsed[0].filter)
        assertEquals(FilterEntry.FILTER_EXCLUDE, parsed[1].filterType)
        assertEquals("exclude.tmp", parsed[1].filter)

        assertTrue(repository.finish(run.runId, run.ownerToken, RunState.CANCELLED, "test complete"))
        assertNull(repository.get(run.runId)!!.filterSnapshot)

        filter.setFiltersRaw("")
        database.updateFilter(filter)
        val emptyRulesRun = repository.queueEphemeralTask(task)
        assertEquals("", emptyRulesRun.filterSnapshot)
        val emptyRulesClaim = repository.claim(
            emptyRulesRun.runId,
            emptyRulesRun.ownerToken,
            RunTaskIdentity.EphemeralTask(task)
        )
        assertEquals("", emptyRulesClaim.filterSnapshot)
        assertTrue(repository.finish(
            emptyRulesRun.runId,
            emptyRulesRun.ownerToken,
            RunState.CANCELLED,
            "test complete"
        ))
    }

    @Test
    fun legacyQueuedFilterSnapshotBackfillsOnlyWhenCurrentIdentityStillMatches() {
        val repository = RunRepository(context)
        val database = DatabaseHandler(context)
        val originalRules = "+original.txt${System.lineSeparator()}"
        val filter = Filter(-1L).apply {
            title = "Disposable migration filter"
            setFiltersRaw(originalRules)
        }
        database.createFilter(filter)
        val task = ephemeralTask().apply { filterId = filter.id }

        val unchangedRun = repository.queueEphemeralTask(task)
        clearQueuedFilterSnapshot(unchangedRun.runId)
        val backfilled = repository.claim(
            unchangedRun.runId,
            unchangedRun.ownerToken,
            RunTaskIdentity.EphemeralTask(task)
        )
        assertEquals(originalRules, backfilled.filterSnapshot)
        assertTrue(repository.finish(
            unchangedRun.runId,
            unchangedRun.ownerToken,
            RunState.CANCELLED,
            "test complete"
        ))

        filter.setFiltersRaw("+changed-before-claim.txt${System.lineSeparator()}")
        database.updateFilter(filter)
        val changedRun = repository.queueEphemeralTask(task)
        clearQueuedFilterSnapshot(changedRun.runId)
        filter.setFiltersRaw("+different-current-filter.txt${System.lineSeparator()}")
        database.updateFilter(filter)

        assertThrows(RunRejectedException::class.java) {
            repository.claim(
                changedRun.runId,
                changedRun.ownerToken,
                RunTaskIdentity.EphemeralTask(task)
            )
        }
        assertEquals(RunState.QUEUED, repository.get(changedRun.runId)!!.state)
        assertNull(repository.get(changedRun.runId)!!.filterSnapshot)
    }

    @Test
    fun queuedCancellationIsTerminalAndReleasesProfileOwnership() {
        val repository = RunRepository(context)
        val task = ephemeralTask()
        val queued = repository.queueEphemeralTask(task)

        assertTrue(repository.requestCancellation(queued.runId, queued.ownerToken))
        val cancelled = repository.get(queued.runId)!!
        assertEquals(RunState.CANCELLED, cancelled.state)
        assertEquals("Cancellation requested before execution", cancelled.reason)
        assertTrue(cancelled.cancellationRequested)
        assertTrue(cancelled.finishedAt != null)
        assertEquals(queued.ownerGeneration + 1L, cancelled.ownerGeneration)
        assertNull(cancelled.filterSnapshot)

        assertFalse(repository.requestCancellation(queued.runId, queued.ownerToken))
        assertFalse(repository.finish(queued.runId, queued.ownerToken, RunState.SUCCESS))
        assertThrows(RunRejectedException::class.java) {
            repository.claim(
                queued.runId,
                queued.ownerToken,
                RunTaskIdentity.EphemeralTask(task)
            )
        }

        val next = repository.queueEphemeralTask(task, requestedAt = queued.requestedAt + 1L)
        assertEquals(RunState.QUEUED, next.state)
        assertEquals(queued.profileId, next.profileId)
    }

    @Test
    fun preflightCancellationCannotAdvanceToRunningAndHasExplicitOutcome() {
        val repository = RunRepository(context)
        val task = ephemeralTask()
        val queued = repository.queueEphemeralTask(task)
        assertEquals(
            RunState.PREFLIGHT,
            repository.claim(
                queued.runId,
                queued.ownerToken,
                RunTaskIdentity.EphemeralTask(task)
            ).state
        )

        assertTrue(repository.requestCancellation(queued.runId, queued.ownerToken))
        assertFalse(repository.markRunning(queued.runId, queued.ownerToken))
        // A failed markRunning result is a cancellation outcome because native work was not admitted.
        assertTrue(repository.finish(queued.runId, queued.ownerToken, RunState.FAILED, "phase transition failed"))

        val cancelled = repository.get(queued.runId)!!
        assertEquals(RunState.CANCELLED, cancelled.state)
        assertEquals("Cancellation requested before execution", cancelled.reason)
        assertTrue(cancelled.cancellationRequested)
        assertTrue(cancelled.finishedAt != null)
    }

    @Test
    fun runningCancellationRemainsOwnedUntilWorkerReportsItsOutcome() {
        val repository = RunRepository(context)
        val task = ephemeralTask()
        val queued = repository.queueEphemeralTask(task)
        repository.claim(
            queued.runId,
            queued.ownerToken,
            RunTaskIdentity.EphemeralTask(task)
        )
        assertTrue(repository.markRunning(queued.runId, queued.ownerToken))

        assertTrue(repository.requestCancellation(queued.runId, queued.ownerToken))
        val cancellationPending = repository.get(queued.runId)!!
        assertEquals(RunState.RUNNING, cancellationPending.state)
        assertTrue(cancellationPending.cancellationRequested)
        assertNull(cancellationPending.finishedAt)

        assertTrue(repository.finish(queued.runId, queued.ownerToken, RunState.CANCELLED, "native cancellation confirmed"))
        assertEquals(RunState.CANCELLED, repository.get(queued.runId)!!.state)
    }

    @Test
    fun restartReconciliationFinalizesOldQueuedCancellationAndRevokesInterruptedOwner() {
        val repository = RunRepository(context)
        val oldQueuedTask = ephemeralTask().apply { remotePath = "disposable/cancelled-queued" }
        val oldQueued = repository.queueEphemeralTask(oldQueuedTask)
        setCancellationRequested(oldQueued.runId)

        val runningTask = ephemeralTask().apply { remotePath = "disposable/interrupted-running" }
        val running = repository.queueEphemeralTask(runningTask)
        repository.claim(
            running.runId,
            running.ownerToken,
            RunTaskIdentity.EphemeralTask(runningTask)
        )
        assertTrue(repository.markRunning(running.runId, running.ownerToken))
        val runningGeneration = repository.get(running.runId)!!.ownerGeneration

        repository.reconcileInterruptedRuns()

        val reconciledQueue = repository.get(oldQueued.runId)!!
        assertEquals(RunState.CANCELLED, reconciledQueue.state)
        assertEquals("Cancellation requested before execution", reconciledQueue.reason)
        assertTrue(reconciledQueue.cancellationRequested)
        assertTrue(reconciledQueue.finishedAt != null)
        assertEquals(oldQueued.ownerGeneration + 1L, reconciledQueue.ownerGeneration)
        assertNull(reconciledQueue.filterSnapshot)

        val interrupted = repository.get(running.runId)!!
        assertEquals(RunState.INTERRUPTED, interrupted.state)
        assertEquals(runningGeneration + 1L, interrupted.ownerGeneration)
        assertTrue(interrupted.finishedAt != null)
        assertNull(interrupted.filterSnapshot)
        assertFalse(repository.finish(running.runId, running.ownerToken, RunState.CANCELLED, "stale worker"))

        repository.reconcileInterruptedRuns()
        assertEquals(interrupted.ownerGeneration, repository.get(running.runId)!!.ownerGeneration)
        val next = repository.queueEphemeralTask(oldQueuedTask)
        assertEquals(RunState.QUEUED, next.state)
    }

    private fun clearQueuedFilterSnapshot(runId: String) {
        val database = DatabaseHandler(context)
        val db = database.writableDatabase
        db.execSQL(
            "UPDATE ${DatabaseInfo.RUN_TABLE_NAME} SET ${DatabaseInfo.RUN_COLUMN_FILTER_SNAPSHOT} = NULL WHERE ${DatabaseInfo.RUN_COLUMN_ID} = ?",
            arrayOf(runId)
        )
        db.close()
        database.close()
    }

    private fun setCancellationRequested(runId: String) {
        val database = DatabaseHandler(context)
        val db = database.writableDatabase
        db.execSQL(
            "UPDATE ${DatabaseInfo.RUN_TABLE_NAME} SET ${DatabaseInfo.RUN_COLUMN_CANCEL_REQUESTED} = 1 WHERE ${DatabaseInfo.RUN_COLUMN_ID} = ?",
            arrayOf(runId)
        )
        db.close()
        database.close()
    }

    private fun ephemeralTask() = Task(-1L).apply {
        title = "Disposable ephemeral sync"
        remoteId = "test-account"
        remotePath = "disposable/root"
        localPath = "/disposable/root"
        direction = 2 // SYNC_REMOTE_TO_LOCAL
    }
}
