package io.github.rareities.protonbridge.util

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import io.github.rareities.protonbridge.Items.Task
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class RunHistoryIdentityTest {
    @Test fun reusedDatabaseIdDoesNotInheritAnotherTasksHistory() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val first = Task(301).apply { bisyncUUID = UUID.randomUUID().toString() }
        val restored = Task(301).apply { bisyncUUID = UUID.randomUUID().toString() }
        RunHistory.record(context, first, "first-run", "run", "SUCCESS", 1, 0, "first job")
        assertEquals(1, RunHistory.read(context, first).length())
        assertEquals(0, RunHistory.read(context, restored).length())
        RunHistory.record(context, restored, "other-run", "run", "FAILED", 2, 1, "restored job")
        assertEquals("first job", RunHistory.read(context, first).getJSONObject(0).getString("message"))
        assertEquals("restored job", RunHistory.read(context, restored).getJSONObject(0).getString("message"))
    }
}
