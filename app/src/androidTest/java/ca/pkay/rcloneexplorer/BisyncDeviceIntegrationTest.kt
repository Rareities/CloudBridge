package ca.pkay.rcloneexplorer

import android.content.Context
import android.net.Uri
import android.os.Build
import androidx.preference.PreferenceManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.Data
import androidx.work.ForegroundInfo
import androidx.work.ForegroundUpdater
import androidx.work.ListenableWorker
import androidx.work.impl.utils.futures.SettableFuture
import androidx.work.testing.TestWorkerBuilder
import ca.pkay.rcloneexplorer.Database.DatabaseHandler
import ca.pkay.rcloneexplorer.Database.json.BackupRestore
import ca.pkay.rcloneexplorer.Items.Task
import ca.pkay.rcloneexplorer.util.BisyncPolicy
import ca.pkay.rcloneexplorer.util.BisyncStateStore
import ca.pkay.rcloneexplorer.util.NativeConfigEncryption
import ca.pkay.rcloneexplorer.util.NativeSessionGate
import ca.pkay.rcloneexplorer.util.RunHistory
import ca.pkay.rcloneexplorer.workmanager.SyncManager
import ca.pkay.rcloneexplorer.workmanager.SyncWorker
import com.google.common.util.concurrent.ListenableFuture
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Real packaged native executable, SQLite, Keystore, state and worker integration.
 * Only emulator debug installations are accepted. No Proton account is used.
 * Foreground promotion is substituted here; scheduling/platform tests are separate.
 */
@RunWith(AndroidJUnit4::class)
class BisyncDeviceIntegrationTest {
    private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val foreground = object : ForegroundUpdater {
        override fun setForegroundAsync(context: Context, id: UUID, foregroundInfo: ForegroundInfo): ListenableFuture<Void> =
            SettableFuture.create<Void>().also { it.set(null) }
    }

    private fun checkEnvironment() {
        assumeTrue("Disposable emulator debug build required", context.packageName.endsWith(".debug") &&
            (Build.HARDWARE.contains("ranchu") || Build.HARDWARE.contains("goldfish")))
        PreferenceManager.getDefaultSharedPreferences(context).edit()
            .putBoolean(context.getString(R.string.pref_key_enable_vcp), false)
            .putBoolean(context.getString(R.string.pref_key_app_updates), false).commit()
        ca.pkay.rcloneexplorer.util.EngineProvenance.verify(context)
    }

    @Test fun substitutedNativeEngineIsRejectedBeforeExecution() {
        checkEnvironment()
        val directory = File(context.cacheDir, "phase2-wrong-engine-${UUID.randomUUID()}").apply { mkdirs() }
        File(directory, "librclone.so").writeBytes(byteArrayOf(127, 69, 76, 70))
        val isolated = object : android.content.ContextWrapper(context) {
            override fun getApplicationInfo(): android.content.pm.ApplicationInfo =
                android.content.pm.ApplicationInfo(super.getApplicationInfo()).apply { nativeLibraryDir = directory.absolutePath }
        }
        try {
            assertThrows(java.io.IOException::class.java) { ca.pkay.rcloneexplorer.util.EngineProvenance.verify(isolated) }
        } finally { directory.deleteRecursively() }
        ca.pkay.rcloneexplorer.util.EngineProvenance.verify(context)
    }

    private fun worker(task: Task, operation: String, approval: String? = null, revision: String? = null): SyncWorker {
        val data = Data.Builder().putLong(SyncWorker.TASK_ID, task.id)
            .putString("TASK_UUID", task.bisyncUUID).putString(SyncWorker.BISYNC_OPERATION, operation)
            .putString(SyncWorker.BISYNC_APPROVAL, approval)
            .putString(SyncManager.TASK_REVISION, revision ?: SyncManager.taskRevision(context, task)).build()
        return TestWorkerBuilder.from(context, SyncWorker::class.java, Executor { it.run() })
            .setForegroundUpdater(foreground).setInputData(data).build()
    }

    private fun execute(task: Task, operation: String, approval: String? = null): ListenableWorker.Result =
        worker(task, operation, approval).doWork()

    private fun assertSuccess(task: Task, operation: String, approval: String? = null) {
        val result = execute(task, operation, approval)
        assertTrue("$operation failed: $result; history=${RunHistory.read(context, task)}", result is ListenableWorker.Result.Success)
    }

    private fun snapshot(directory: File): Map<String, String> = directory.walkTopDown().filter { it.isFile }.associate {
        it.relativeTo(directory).invariantSeparatorsPath to MessageDigest.getInstance("SHA-256")
            .digest(it.readBytes()).joinToString("") { b -> "%02x".format(b) }
    }

    private fun installFixtureConfig(right: File): Rclone {
        val rclone = Rclone(context)
        val staged = File(context.cacheDir, "phase2-fixture.conf")
        staged.writeText("[phase2_local]\ntype = alias\nremote = ${right.absolutePath}\n")
        try {
            val prepared = rclone.protectImportedConfig(staged, null)
            BackupRestore.installConfig(context, rclone, staged, prepared)
        } finally { staged.delete() }
        assertTrue(NativeConfigEncryption.isEncrypted(File(context.filesDir, "rclone.conf")))
        return rclone
    }

    @Test fun completeNativeBisyncLifecycleAndEncryptedRestore() {
        checkEnvironment()
        val root = File(context.filesDir, "phase2-fixture-${UUID.randomUUID()}").apply { mkdirs() }
        val left = File(root, "left").apply { mkdirs() }
        val right = File(root, "right").apply { mkdirs() }
        for (i in 0 until 20) File(left, "file-$i.txt").writeText("base-$i")
        File(left, "both.txt").writeText("AAAA")
        File(right, "remote-only.txt").writeText("remote")
        val rclone = installFixtureConfig(right)
        val db = DatabaseHandler(context)
        val task = db.createTask(Task(0).apply {
            title = "Disposable native bisync"; direction = 6
            localPath = left.absolutePath; remoteId = "phase2_local"; remotePath = "//phase2_local"
            remoteType = 0; wifionly = false; bisyncMaxDelete = 10
        })
        val store = BisyncStateStore(BisyncPolicy.workdir(context, task))
        val beforeLeft = snapshot(left); val beforeRight = snapshot(right)
        assertSuccess(task, BisyncStateStore.PREVIEW)
        assertEquals(beforeLeft, snapshot(left)); assertEquals(beforeRight, snapshot(right))
        assertEquals(BisyncStateStore.PREVIEW_READY, store.read().getString("status"))
        assertSuccess(task, BisyncStateStore.INITIALISE, store.read().getString("previewFingerprint"))
        assertEquals(BisyncStateStore.READY, store.read().getString("status"))
        assertEquals(snapshot(left), snapshot(right))

        // Fresh wrapper/state objects emulate a cold in-process restart, not OS process death.
        val reopened = BisyncStateStore(BisyncPolicy.workdir(context, db.getTask(task.id)!!))
        val baseline = reopened.read().getString("baselineId")
        val baselineFiles = File(BisyncPolicy.workdir(context, task), "baselines/$baseline")
        val baselineBeforePreview = snapshot(baselineFiles)
        File(left, "local-added.txt").writeText("L")
        File(right, "remote-added.txt").writeText("R")
        assertSuccess(task, BisyncStateStore.PREVIEW)
        assertEquals(BisyncStateStore.READY, reopened.read().getString("status"))
        assertEquals(baselineBeforePreview, snapshot(baselineFiles))
        assertFalse(File(right, "local-added.txt").exists())
        assertSuccess(task, BisyncStateStore.RUN)
        assertEquals(snapshot(left), snapshot(right))

        File(left, "both.txt").writeText("LLLL")
        File(right, "both.txt").writeText("RRRR")
        assertSuccess(task, BisyncStateStore.RUN)
        assertEquals(snapshot(left), snapshot(right))
        val contents = left.listFiles()!!.filter { it.isFile }.map { it.readText() }
        assertTrue(contents.contains("LLLL")); assertTrue(contents.contains("RRRR"))
        File(left, "file-0.txt").delete()
        assertSuccess(task, BisyncStateStore.RUN)
        assertFalse(File(right, "file-0.txt").exists())

        val oldRevision = SyncManager.taskRevision(context, task)
        task.bisyncAdvancedOptions = "--bwlimit=8M"
        db.updateTask(task)
        val rightBeforeEdit = snapshot(right)
        assertTrue(worker(task, BisyncStateStore.RUN, revision=oldRevision).doWork() is ListenableWorker.Result.Failure)
        assertEquals(rightBeforeEdit, snapshot(right))
        assertTrue(execute(task, BisyncStateStore.RUN) is ListenableWorker.Result.Failure)
        assertSuccess(task, BisyncStateStore.PREVIEW)
        assertSuccess(task, BisyncStateStore.INITIALISE, store.read().getString("previewFingerprint"))

        // Large deletion must stop before deleting the peer's committed contents.
        for (i in 1 until 9) File(left, "file-$i.txt").delete()
        val peerBeforeThreshold = snapshot(right)
        assertTrue(execute(task, BisyncStateStore.RUN) is ListenableWorker.Result.Failure)
        assertEquals(peerBeforeThreshold, snapshot(right))
        assertEquals(BisyncStateStore.NEEDS_RECOVERY, store.read().getString("status"))
        assertSuccess(task, BisyncStateStore.PREVIEW)
        assertSuccess(task, BisyncStateStore.INITIALISE, store.read().getString("previewFingerprint"))

        val backup = File(context.cacheDir, "phase2-encrypted-backup.zip")
        rclone.exportConfigFile(Uri.fromFile(backup), "disposable-backup-password")
        val configBefore = File(context.filesDir, "rclone.conf").readBytes()
        try { BackupRestore.restore(context, rclone, Uri.fromFile(backup), "wrong-password"); fail("Wrong password accepted") }
        catch (_: Exception) { }
        assertArrayEquals(configBefore, File(context.filesDir, "rclone.conf").readBytes())
        BackupRestore.restore(context, rclone, Uri.fromFile(backup), "disposable-backup-password")
        assertTrue(NativeConfigEncryption.isEncrypted(File(context.filesDir, "rclone.conf")))
        assertNotEquals(task.bisyncUUID, db.getTask(task.id)!!.bisyncUUID)
        assertEquals(snapshot(left), snapshot(right))
    }

    @Test fun cancelledNativePipeRetainsOwnershipAndCannotReportSuccess() {
        checkEnvironment()
        val root = File(context.filesDir, "phase2-pipe-${UUID.randomUUID()}").apply { mkdirs() }
        val source = File(root, "bytes.bin")
        source.outputStream().use { out -> repeat(128) { out.write(ByteArray(65536) { 91 }) } }
        val rclone = Rclone(context)
        rclone.prepareProtectedConfig()
        val transfer = rclone.openDownloadPipe(source.absolutePath)
        assertEquals(91, transfer.input().read())
        assertThrows(java.io.IOException::class.java) { NativeSessionGate.GLOBAL.acquireCommand() }
        transfer.cancel()
        assertThrows(java.io.IOException::class.java) { transfer.awaitSuccessfulExit() }
        transfer.input().close()
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (System.nanoTime() < deadline) {
            try { NativeSessionGate.GLOBAL.acquireCommand().close(); return }
            catch (_: java.io.IOException) { Thread.sleep(10) }
        }
        fail("Native admission did not close after cancellation and confirmed exit")
    }
}
