package ca.pkay.rcloneexplorer

import androidx.test.core.app.ApplicationProvider
import android.content.Context
import ca.pkay.rcloneexplorer.util.DurableFiles
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.IOException

/** Exercises real Android directory descriptors, which Robolectric does not implement. */
class RestoreDirectoryDurabilityTest {
    @Test fun privateDirectoryBarrierWorksAndMissingDirectoryFailsClosed() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        require(context.packageName.endsWith(".debug")) { "Disposable debug installation only" }
        ca.pkay.rcloneexplorer.Database.DatabaseHandler(context).use { helper ->
            helper.writableDatabase.rawQuery("PRAGMA synchronous", null).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("Restore database commits must use FULL synchronization", 2, cursor.getInt(0))
            }
        }
        val root = File(context.filesDir, "durability-device-fixture").apply { mkdirs() }
        try {
            File(root, "content").writeText("private disposable fixture")
            DurableFiles.syncFileIfPresent(File(root, "content"))
            DurableFiles.syncDirectory(root)
            DurableFiles.syncDirectory(context.filesDir)
            assertThrows(IOException::class.java) { DurableFiles.syncDirectory(File(root, "missing")) }
        } finally { root.deleteRecursively() }
    }
}
