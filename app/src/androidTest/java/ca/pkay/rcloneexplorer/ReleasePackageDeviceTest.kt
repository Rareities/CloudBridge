package ca.pkay.rcloneexplorer

import android.content.Intent
import android.content.pm.ApplicationInfo
import android.os.Build
import androidx.preference.PreferenceManager
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.WorkInfo
import androidx.work.WorkManager
import ca.pkay.rcloneexplorer.Activities.MainActivity
import ca.pkay.rcloneexplorer.Activities.TaskActivity
import ca.pkay.rcloneexplorer.Database.DatabaseHandler
import ca.pkay.rcloneexplorer.Items.Task
import ca.pkay.rcloneexplorer.util.BisyncPolicy
import ca.pkay.rcloneexplorer.util.BisyncStateStore
import ca.pkay.rcloneexplorer.util.EngineProvenance
import ca.pkay.rcloneexplorer.util.NativeConfigEncryption
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

/** Runs against the actual minified, signed, non-debuggable release APK. */
@RunWith(AndroidJUnit4::class)
class ReleasePackageDeviceTest {
    @Test(timeout = 150_000) fun signedReleaseLaunchesAndCompletesGuardedNativeBisync() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        assumeTrue(InstrumentationRegistry.getArguments().getString("releaseAcceptance") == "true" &&
            (Build.HARDWARE.contains("ranchu") || Build.HARDWARE.contains("goldfish")))
        assertEquals("de.schuelken.cloudbridge", context.packageName)
        assertEquals(0, context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE)
        EngineProvenance.verify(context)
        val rclone = Rclone(context)
        val db = DatabaseHandler(context)
        val work = WorkManager.getInstance(context)
        val alias = "release_${UUID.randomUUID().toString().replace("-", "")}"
        val root = File(context.filesDir, alias).apply { check(mkdirs()) }
        val left = File(root, "left").apply { check(mkdirs()) }
        val right = File(root, "right").apply { check(mkdirs()) }
        var task: Task? = null
        var createdAlias = false
        fun await(label: String, timeout: Long = 30_000, condition: () -> Boolean) {
            val end = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeout)
            while (System.nanoTime() < end) {
                if (condition()) return
                Thread.sleep(100)
            }
            assertTrue(label, condition())
        }
        fun command(child: Process?) {
            val start = System.nanoTime()
            val result = ProcessRunner().run(child,
                { TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - start) > 25 }, null, null)
            assertTrue("Release native command failed: ${result.exitCode}", result.success())
        }
        val completedWork = mutableSetOf<UUID>()
        fun finished(saved: Task) {
            var found: UUID? = null
            await("Release worker did not finish: ${RunHistory.read(context, saved)}") {
                val states = work.getWorkInfosForUniqueWork("task:${saved.id}").get(5, TimeUnit.SECONDS)
                states.filter { it.id !in completedWork }.forEach {
                    if (it.state == WorkInfo.State.FAILED) fail("Release worker failed: ${RunHistory.read(context, saved)}")
                    if (it.state == WorkInfo.State.SUCCEEDED) found = it.id
                }
                found != null
            }
            await("Release process did not relinquish ownership") { !NativeSessionGate.GLOBAL.isBusy }
            completedWork.add(found!!)
        }
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        prefs.edit().putBoolean("intro_cloudbridge_v1_completed", true)
            .putBoolean(context.getString(R.string.pref_key_app_updates), false)
            .putBoolean(context.getString(R.string.pref_key_enable_vcp), false).commit()
        try {
            rclone.prepareProtectedConfig()
            assertTrue(NativeConfigEncryption.isEncrypted(File(context.filesDir, "rclone.conf")))
            createdAlias = true
            command(rclone.configCreateNoInteract(mutableListOf(alias, "alias", "remote", right.absolutePath)))
            File(left, "initial.txt").writeText("release initial bytes")
            val saved = db.createTask(Task(0).apply {
                title = "Signed release acceptance"; direction = 6
                localPath = left.absolutePath; remoteId = alias; remotePath = "//$alias"; wifionly = false
            }).also { task = it }
            val state = BisyncStateStore(BisyncPolicy.workdir(context, saved))
            ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java)).use {
                val manager = SyncManager(context)
                manager.previewBisync(saved.id)
                finished(saved)
                assertFalse(File(right, "initial.txt").exists())
                manager.initialiseBisync(saved.id, state.read().getString("previewFingerprint"))
                finished(saved)
                assertEquals("release initial bytes", File(right, "initial.txt").readText())
                File(left, "from-left.txt").writeText("L")
                File(right, "from-right.txt").writeText("R")
                manager.queue(saved.id)
                finished(saved)
                assertEquals("L", File(right, "from-left.txt").readText())
                assertEquals("R", File(left, "from-right.txt").readText())
                assertEquals(BisyncStateStore.READY, state.read().getString("status"))
            }
            ActivityScenario.launch<TaskActivity>(Intent(context, TaskActivity::class.java)
                .putExtra(TaskActivity.ID_EXTRA, saved.id)).use { editor ->
                editor.onActivity { activity ->
                    assertEquals(saved.title, activity.findViewById<android.widget.EditText>(R.id.task_title_textfield).text.toString())
                    assertNotNull(activity.findViewById<android.view.View>(R.id.bisync_command_button))
                    assertNotNull(activity.findViewById<android.view.View>(R.id.task_proton_connection_button))
                }
            }
        } finally {
            task?.let { work.cancelUniqueWork("task:${it.id}").result.get(5, TimeUnit.SECONDS) }
            await("Release fixture cleanup must wait for native exit") { !NativeSessionGate.GLOBAL.isBusy }
            if (createdAlias) command(rclone.config("delete", mutableListOf(alias)))
            task?.let { db.deleteTask(it.id) }
            assertTrue(root.deleteRecursively())
        }
    }
}
