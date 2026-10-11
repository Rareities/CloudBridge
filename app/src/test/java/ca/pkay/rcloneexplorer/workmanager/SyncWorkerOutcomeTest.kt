package ca.pkay.rcloneexplorer.workmanager

import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import androidx.test.core.app.ApplicationProvider
import androidx.work.Data
import androidx.work.ListenableWorker
import androidx.work.testing.TestWorkerBuilder
import ca.pkay.rcloneexplorer.Database.DatabaseHandler
import ca.pkay.rcloneexplorer.Items.Task
import ca.pkay.rcloneexplorer.util.RunHistory
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import java.util.concurrent.Executor

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class SyncWorkerOutcomeTest {
    private val context: Application get() = ApplicationProvider.getApplicationContext()

    private fun offlineTask(attempt: Int): Pair<Task, SyncWorker> {
        val connectivity = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        Shadows.shadowOf(connectivity).setActiveNetworkInfo(null)
        val task = DatabaseHandler(context).createTask(Task(0).apply { title = "Offline task"; direction = 3 })
        val worker = TestWorkerBuilder.from(context, SyncWorker::class.java, Executor { it.run() })
            .setInputData(Data.Builder().putLong(SyncWorker.TASK_ID, task.id).build())
            .setRunAttemptCount(attempt).build()
        return task to worker
    }

    @Test fun exhaustedNetworkRetriesProduceOneFailedHistoryRecord() {
        val (task, worker) = offlineTask(6)
        val result = worker.doWork()
        assertTrue(result is ListenableWorker.Result.Failure)
        val history = RunHistory.read(context, task)
        assertEquals(1, history.length())
        assertEquals("FAILED", history.getJSONObject(0).getString("status"))
        assertTrue(history.getJSONObject(0).getString("message").contains("network"))
        assertTrue(history.getJSONObject(0).isNull("exitCode"))
    }

    @Test fun transientNetworkDelayDoesNotClaimTerminalFailureOrSuccess() {
        val (task, worker) = offlineTask(1)
        assertTrue(worker.doWork() is ListenableWorker.Result.Retry)
        assertEquals(0, RunHistory.read(context, task).length())
    }
}
