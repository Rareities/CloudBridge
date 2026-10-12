package io.github.rareities.protonbridge.util
import android.app.Application
import androidx.test.core.app.ApplicationProvider
import io.github.rareities.protonbridge.Rclone
import io.github.rareities.protonbridge.Items.RemoteItem
import io.github.rareities.protonbridge.Items.Task
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class BisyncConflictArgumentsTest {
    @Test fun newerActuallyComparesModtimeAndKeepsLosingCopies() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val rclone = object : Rclone(context) {
            override fun getRemoteItemFromName(name: String): RemoteItem = RemoteItem(name, "webdav")
        }
        val task = Task(1).apply { remoteId = "fixture"; localPath = "/tmp/left"; remotePath = "right"; transfers = 1 }
        val plain = rclone.bisyncArguments(task, File(context.filesDir, "baseline"), "run", arrayListOf(), false).toList()
        assertTrue(plain.contains("--compare=size,checksum"))
        assertTrue(plain.contains("--conflict-resolve=none"))
        task.bisyncAdvancedOptions = "--conflict-resolve newer"
        val args = rclone.bisyncArguments(task, File(context.filesDir, "baseline"), "run", arrayListOf(), false).toList()
        assertTrue(args.contains("--compare=size,modtime,checksum"))
        assertEquals(listOf("--conflict-resolve=newer"), args.filter { it.startsWith("--conflict-resolve=") })
        assertTrue(args.contains("--conflict-loser=num"))
        assertTrue(args.contains("--require-checksum"))
        assertTrue(args.contains("--checksum"))
        val current = BisyncPolicy.fingerprint(task, emptyList(), "fixture")
        task.bisyncAdvancedOptions = "--conflict-resolve=newer"
        assertEquals(current, BisyncPolicy.fingerprint(task, emptyList(), "fixture"))
        task.bisyncAdvancedOptions = "--conflict-resolve older"
        assertNotEquals(current, BisyncPolicy.fingerprint(task, emptyList(), "fixture"))
    }
}
