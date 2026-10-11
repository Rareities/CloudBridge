package ca.pkay.rcloneexplorer

import android.content.Intent
import android.os.Build
import androidx.preference.PreferenceManager
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.WorkInfo
import androidx.work.WorkManager
import ca.pkay.rcloneexplorer.Activities.MainActivity
import ca.pkay.rcloneexplorer.Database.DatabaseHandler
import ca.pkay.rcloneexplorer.Items.Task
import ca.pkay.rcloneexplorer.util.NativeSessionGate
import ca.pkay.rcloneexplorer.util.ProcessRunner
import ca.pkay.rcloneexplorer.util.RunHistory
import ca.pkay.rcloneexplorer.workmanager.SyncManager
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit

/** All six existing copy/sync modes use the actual bundled engine and WorkManager. */
@RunWith(AndroidJUnit4::class)
class OrdinaryTaskDeviceIntegrationTest {
    @Test(timeout = 180_000) fun ordinaryCopySyncAndCloudToCloudRemainFunctional() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assumeTrue(context.packageName.endsWith(".debug") &&
            (Build.HARDWARE.contains("ranchu") || Build.HARDWARE.contains("goldfish")))
        val db = DatabaseHandler(context)
        val work = WorkManager.getInstance(context)
        val rclone = Rclone(context)
        val name = "ordinary_${UUID.randomUUID().toString().replace("-", "")}"
        val root = File(context.filesDir, name).apply { check(mkdirs()) }
        val tasks = mutableListOf<Task>()
        var aliasCreated = false
        fun await(label: String, timeout: Long = 20_000, condition: () -> Boolean) {
            val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeout)
            while (System.nanoTime() < deadline) {
                if (condition()) return
                Thread.sleep(100)
            }
            assertTrue(label, condition())
        }
        fun command(child: Process?) {
            val started = System.nanoTime()
            val result = ProcessRunner().run(child,
                { TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - started) > 25 }, null, null)
            assertTrue("Native fixture command failed: ${result.exitCode}", result.success())
        }
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        prefs.edit().putBoolean("intro_cloudbridge_v1_completed", true)
            .putBoolean(context.getString(R.string.pref_key_enable_vcp), false)
            .putBoolean(context.getString(R.string.pref_key_app_updates), false).commit()
        try {
            await("Previous native job must finish") { !NativeSessionGate.GLOBAL.isBusy && !RcloneRcd.hasRunningProcess() }
            aliasCreated = true
            command(rclone.configCreateNoInteract(mutableListOf(name, "alias", "remote", root.absolutePath)))
            ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java)).use {
                for (mode in listOf(1, 2, 3, 4, 7, 8)) {
                    val fixture = File(root, "mode-$mode").apply { check(mkdirs()) }
                    val local = File(fixture, "local").apply { check(mkdirs()) }
                    val remote = File(fixture, "remote").apply { check(mkdirs()) }
                    val destination = File(fixture, "destination").apply { check(mkdirs()) }
                    val source = if (mode == 1 || mode == 3) local else remote
                    val target = if (mode == 1 || mode == 3) remote else if (mode == 2 || mode == 4) local else destination
                    File(source, "source.txt").writeText("exact bytes for mode $mode")
                    File(target, "extra.txt").writeText("destination-only")
                    val task = db.createTask(Task(0).apply {
                        title = "Ordinary mode $mode"; direction = mode
                        localPath = local.absolutePath; remoteId = name; remotePath = "mode-$mode/remote"
                        remoteId2 = name; remotePath2 = "mode-$mode/destination"
                        wifionly = false; md5sum = true
                    })
                    tasks.add(task)
                    SyncManager(context).queue(task.id)
                    await("Existing mode $mode did not finish: ${RunHistory.read(context, task)}", 30_000) {
                        val state = work.getWorkInfosForUniqueWork("task:${task.id}").get(3, TimeUnit.SECONDS)
                        if (state.any { info -> info.state == WorkInfo.State.FAILED })
                            fail("Existing mode $mode failed: ${RunHistory.read(context, task)}")
                        state.any { info -> info.state == WorkInfo.State.SUCCEEDED }
                    }
                    assertEquals("exact bytes for mode $mode", File(target, "source.txt").readText())
                    assertEquals("Sync removes extras; copy preserves extras (mode $mode)",
                        mode == 3 || mode == 4 || mode == 8, File(target, "extra.txt").exists())
                    assertTrue(File(source, "source.txt").isFile)
                    await("Native ownership must finish after mode $mode") { !NativeSessionGate.GLOBAL.isBusy }
                }
            }
        } finally {
            for (task in tasks) work.cancelUniqueWork("task:${task.id}").result.get(5, TimeUnit.SECONDS)
            await("Fixture cleanup must wait for native exit") { !NativeSessionGate.GLOBAL.isBusy }
            if (aliasCreated) command(rclone.config("delete", mutableListOf(name)))
            for (task in tasks) db.deleteTask(task.id)
            assertTrue(root.deleteRecursively())
        }
    }
}
