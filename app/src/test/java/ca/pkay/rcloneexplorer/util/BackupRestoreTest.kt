package ca.pkay.rcloneexplorer.util

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import ca.pkay.rcloneexplorer.Database.DatabaseHandler
import ca.pkay.rcloneexplorer.Database.json.BackupArchive
import ca.pkay.rcloneexplorer.Database.json.BackupRestore
import ca.pkay.rcloneexplorer.Database.json.Exporter
import ca.pkay.rcloneexplorer.Database.json.SharedPreferencesBackup
import ca.pkay.rcloneexplorer.Items.Task
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class BackupRestoreTest {
    private val context: Application get() = ApplicationProvider.getApplicationContext()
    private fun zip(entries: Map<String, String>): ByteArray {
        val bytes = ByteArrayOutputStream()
        ZipOutputStream(bytes).use { output ->
            for ((name, content) in entries) {
                output.putNextEntry(ZipEntry(name))
                output.write(content.toByteArray())
                output.closeEntry()
            }
        }
        return bytes.toByteArray()
    }

    @Test fun archiveUsesExistingFormatAndRejectsUnexpectedPathsBeforeMutation() {
        val complete = mapOf("rclone.conf" to "[remote]\ntype=local", "rcx.json" to "{}", "rcx.prefs" to "{}")
        assertEquals("{}", BackupArchive.read(ByteArrayInputStream(zip(complete))).tasks)
        assertThrows(IOException::class.java) { BackupArchive.read(ByteArrayInputStream(zip(complete + ("../escape" to "x")))) }
        assertThrows(IOException::class.java) { BackupArchive.read(ByteArrayInputStream(zip(complete - "rcx.prefs"))) }
        assertThrows(IOException::class.java) { BackupArchive.read(ByteArrayInputStream(zip(complete + ("rcx.prefs" to "x".repeat(256 * 1024 + 1))))) }
    }

    @Test fun pendingJournalRestoresPreviousConfigAndOriginalTaskIdentity() {
        val db = DatabaseHandler(context)
        val original = db.createTask(Task(0).apply { title = "Original"; direction = 3 })
        val backup = JSONObject().put("version", 1).put("hadConfig", true)
            .put("database", JSONObject(Exporter.create(context)))
            .put("preferences", JSONObject(SharedPreferencesBackup.export(context)))
        val journal = File(context.filesDir, "restore-journal").apply { mkdirs() }
        File(journal, "previous.json").writeText(backup.toString())
        File(journal, "previous.conf").writeText("original configuration")
        File(journal, "pending").writeBytes(byteArrayOf(1))
        File(context.filesDir, "rclone.conf").writeText("partially restored configuration")
        db.replaceAll(listOf(Task(999).apply { title = "Replacement"; direction = 3 }), emptyList(), emptyList())
        BackupRestore.recoverIfNeeded(context)
        assertEquals("original configuration", File(context.filesDir, "rclone.conf").readText())
        assertEquals(original.bisyncUUID, db.getTask(original.id)!!.bisyncUUID)
        assertNull(db.getTask(999))
        assertFalse(File(journal, "pending").exists())
    }

    @Test fun interruptedPairSwapRestoresBothEncryptedConfigAndWrappedPassword() {
        val journal = File(context.filesDir, "restore-journal").apply { mkdirs() }
        val config = File(context.filesDir, "rclone.conf")
        val wrapped = File(context.filesDir, "config-password.json")
        val oldConfig = "RCLONE_ENCRYPT_V0:previous-opaque-ciphertext"
        val oldPassword = "previous-wrapped-key-record"
        val newConfig = "RCLONE_ENCRYPT_V0:new-opaque-ciphertext"
        val newPassword = "new-wrapped-key-record"
        val backup = JSONObject().put("version", 1).put("hadConfig", true).put("hadPassword", true)
            .put("database", JSONObject(Exporter.create(context)))
            .put("preferences", JSONObject(SharedPreferencesBackup.export(context)))
        File(journal, "previous.json").writeText(backup.toString())
        File(journal, "previous.conf").writeText(oldConfig)
        File(journal, "previous.password").writeText(oldPassword)
        File(journal, "pending").writeBytes(byteArrayOf(1))
        config.writeText(newConfig)
        wrapped.writeText(newPassword)
        BackupRestore.recoverIfNeeded(context)
        assertEquals(oldConfig, config.readText())
        assertEquals(oldPassword, wrapped.readText())
        assertFalse(File(journal, "pending").exists())
    }

    @Test fun interruptedPairSwapDeletesNewWrappingRecordWhenPreviousWasMissing() {
        val journal = File(context.filesDir, "restore-journal").apply { mkdirs() }
        val wrapped = File(context.filesDir, "config-password.json")
        val backup = JSONObject().put("version", 1).put("hadConfig", false).put("hadPassword", false)
            .put("database", JSONObject(Exporter.create(context)))
            .put("preferences", JSONObject(SharedPreferencesBackup.export(context)))
        File(journal, "previous.json").writeText(backup.toString())
        File(journal, "pending").writeBytes(byteArrayOf(1))
        wrapped.writeText("new-wrapped-key-record")
        BackupRestore.recoverIfNeeded(context)
        assertFalse(wrapped.exists())
        assertFalse(File(journal, "pending").exists())
    }
}
