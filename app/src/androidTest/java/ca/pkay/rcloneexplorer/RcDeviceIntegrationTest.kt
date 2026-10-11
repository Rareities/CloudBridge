package ca.pkay.rcloneexplorer

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Build
import android.os.IBinder
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import ca.pkay.rcloneexplorer.Services.RcdService
import ca.pkay.rcloneexplorer.util.NativeSessionGate
import ca.pkay.rcloneexplorer.util.ProtectedRcloneConfig
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Real packaged rclone RC/Android service test, limited to disposable emulator-local data.
 *
 * Runs every native/session-sensitive phase sequentially in one method. The fixture adds and
 * removes only its uniquely named alias; existing encrypted configurations stay installed.
 * A real RC job's callback is delivered on Android's main looper and awaited off that looper.
 */
@RunWith(AndroidJUnit4::class)
class RcDeviceIntegrationTest {
    private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun checkEnvironment() {
        assumeTrue("A disposable .debug ranchu/goldfish emulator is required",
            context.packageName.endsWith(".debug") &&
                (Build.HARDWARE.contains("ranchu", ignoreCase = true) ||
                    Build.HARDWARE.contains("goldfish", ignoreCase = true)))
    }

    private fun waitUntil(label: String, timeoutMs: Long, condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        while (System.nanoTime() < deadline) {
            if (condition()) return
            Thread.sleep(100)
        }
        assertTrue("Timed out after ${timeoutMs}ms: $label", condition())
    }

    private fun awaitOnline(rcd: RcloneRcd) = waitUntil("authenticated RC service to accept requests", 25_000) {
        try {
            rcd.isOnline() // POST to the real password-protected rc/noopauth endpoint.
            true
        } catch (_: RcloneRcd.RcdIOException) {
            false
        }
    }

    private fun awaitJob(label: String, latch: CountDownLatch,
                         outcome: AtomicReference<RcloneRcd.JobStatusResponse?>) {
        assertTrue("No asynchronous $label callback on the Android main looper",
            latch.await(65, TimeUnit.SECONDS))
        val result = outcome.get()
        assertNotNull("$label callback had no result", result)
        assertTrue("$label did not reach terminal status", result!!.finished)
        assertTrue("$label failed: ${result.error}", result.success)
    }

    private fun isServiceShutdown(service: RcdService): Boolean {
        // shutdown is a service-main-thread field rather than a volatile property.
        // Observe it on the owning looper so an instrumentation-thread poll is valid.
        val result = AtomicBoolean()
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            result.set(service.isShutdown)
        }
        return result.get()
    }

    private fun checkCommand(label: String, process: Process?) {
        assertNotNull("Could not start $label", process)
        val child = process!!
        if (!child.waitFor(25, TimeUnit.SECONDS)) {
            child.destroy()
            if (!child.waitFor(5, TimeUnit.SECONDS)) child.destroyForcibly()
            fail("$label did not exit within its deadline")
        }
        assertEquals("$label failed (native exit code)", 0, child.exitValue())
    }

    private fun checksum(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    @Test(timeout = 240_000)
    fun packagedAuthenticatedRcCopyMoveGateCliInvalidationRestartAndBoundServiceIdle() {
        checkEnvironment()
        val root = File(context.filesDir, "phase2-rc-${UUID.randomUUID()}").apply {
            assertTrue("Cannot create a private RC fixture", mkdirs())
        }
        val source = File(root, "source").apply { assertTrue(mkdirs()) }
        val copied = File(root, "copied")
        val moved = File(root, "moved").apply { assertTrue(mkdirs()) }
        val alias = "phase2_rc_${UUID.randomUUID().toString().replace("-", "")}"
        val rclone = Rclone(context)
        val daemons = mutableListOf<RcloneRcd>()
        val serviceRef = AtomicReference<RcdService?>()
        val connectionReady = CountDownLatch(1)
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                serviceRef.set((binder as RcdService.RcdBinder).service)
                connectionReady.countDown()
            }
            override fun onServiceDisconnected(name: ComponentName) { serviceRef.set(null) }
        }
        var bound = false
        var aliasCreated = false
        var cli: Process? = null
        try {
            // Respect any earlier instrumented method still releasing its OS-level process.
            waitUntil("previous RC/CLI process to exit", 12_000) {
                !RcloneRcd.hasRunningProcess() && !NativeSessionGate.GLOBAL.isBusy()
            }
            rclone.prepareProtectedConfig()
            val native = File(context.applicationInfo.nativeLibraryDir, "librclone.so")
            assertTrue("Packaged native rclone binary is missing", native.isFile)

            // A unique local alias augments the encrypted config; never replace the database,
            // Keystore or another test's existing remotes with a fixture config.
            val aliasCommand = rclone.configCreateNoInteract(
                mutableListOf(alias, "alias", "remote", root.absolutePath))
            // A native config command may have written its section before reporting an error.
            aliasCreated = aliasCommand != null
            checkCommand("create local RC alias", aliasCommand)

            val payload = File(source, "bytes.bin")
            val block = ByteArray(64 * 1024) { (it % 251).toByte() }
            payload.outputStream().buffered().use { output ->
                repeat(1536) { output.write(block) } // 96 MiB: keeps real async copy observable.
            }
            val sourceHash = checksum(payload)

            // An actual Android bind constructs the real RcdService and returns its binder.
            bound = context.bindService(Intent(context, RcdService::class.java), connection,
                Context.BIND_AUTO_CREATE)
            assertTrue("Could not bind actual RcdService", bound)
            assertTrue("Service binding callback was not delivered", connectionReady.await(10, TimeUnit.SECONDS))
            val service = requireNotNull(serviceRef.get()) { "RcdService disconnected on bind" }
            val daemon = service.localRcd.also { daemons.add(it) }
            awaitOnline(daemon)
            assertTrue("Authenticated RC daemon is not alive", daemon.isAlive)
            assertNotNull("The configured local alias is not usable via authenticated RC", daemon.list(alias, "source"))

            val copyDone = CountDownLatch(1)
            val copyOutcome = AtomicReference<RcloneRcd.JobStatusResponse?>()
            daemon.copy("$alias:source", "$alias:copied",
                RcloneRcd.JobStatusHandler { status -> copyOutcome.set(status); copyDone.countDown() })
            assertTrue("Real asynchronous RC copy was not registered as pending", daemon.hasPendingJobs())

            // Exercise the production ProtectedRcloneConfig.launch admission path, not a
            // stand-in lock. A rejected CLI must never touch the disposable destination.
            val forbiddenDestination = File(root, "cli-while-rc-busy")
            val command = arrayOf(native.absolutePath, "--config",
                File(context.filesDir, "rclone.conf").absolutePath,
                "copy", "$alias:source", forbiddenDestination.absolutePath)
            var forbidden: Process? = null
            var rejection: IOException? = null
            try {
                forbidden = ProtectedRcloneConfig.launch(context, command, rclone.getRcloneEnv())
            } catch (blocked: IOException) {
                rejection = blocked
            } finally {
                // If admission broke, stop the unexpected test-local copy before failing.
                forbidden?.let {
                    it.destroy()
                    if (!it.waitFor(5, TimeUnit.SECONDS)) {
                        it.destroyForcibly()
                        assertTrue("Unexpected CLI failed to stop", it.waitFor(5, TimeUnit.SECONDS))
                    }
                    Unit
                }
            }
            assertNotNull("A real CLI was admitted while an RC job owned the session", rejection)
            assertTrue("CLI rejected for a reason other than session admission: ${rejection?.message}",
                rejection!!.message.orEmpty().contains("Another native operation is active"))
            assertFalse("Rejected CLI modified the fixture", forbiddenDestination.exists())
            awaitJob("copy", copyDone, copyOutcome)
            assertEquals("RC copy did not reproduce the file", sourceHash, checksum(File(copied, "bytes.bin")))

            val moveDone = CountDownLatch(1)
            val moveOutcome = AtomicReference<RcloneRcd.JobStatusResponse?>()
            daemon.moveFile(alias, "copied/bytes.bin", alias, "moved/bytes.bin",
                RcloneRcd.JobStatusHandler { status -> moveOutcome.set(status); moveDone.countDown() })
            awaitJob("moveFile", moveDone, moveOutcome)
            assertFalse("RC moveFile left its source behind", File(copied, "bytes.bin").exists())
            assertEquals("RC moveFile changed file content", sourceHash, checksum(File(moved, "bytes.bin")))
            waitUntil("RC job sessions to close", 10_000) {
                !daemon.hasActiveWork() && !NativeSessionGate.GLOBAL.isBusy()
            }

            // Launch a real, short, local-filesystem CLI after jobs finish. Its native command
            // session advances the generation, so the existing idle RC daemon must go stale.
            cli = ProtectedRcloneConfig.launch(context,
                arrayOf(native.absolutePath, "--config", File(context.filesDir, "rclone.conf").absolutePath,
                    "lsf", "$alias:moved"), rclone.getRcloneEnv())
            assertFalse("Old daemon accepted its stale session generation", daemon.isAlive)
            checkCommand("generation-invalidating local CLI", cli)
            cli = null
            waitUntil("local CLI to release its native session", 8_000) {
                !NativeSessionGate.GLOBAL.isBusy()
            }

            // Stop is a request, not a confirmed exit. Wait for the native child to die
            // before calling getLocalRcd(), which refuses parallel daemon processes.
            daemon.stopRcd()
            waitUntil("stale daemon native process to terminate", 15_000) {
                !daemon.hasLiveProcess() && !RcloneRcd.hasRunningProcess()
            }
            val restarted = service.localRcd.also { daemons.add(it) }
            assertNotSame("getLocalRcd did not start a fresh daemon", daemon, restarted)
            awaitOnline(restarted)
            assertNotNull("Restarted daemon cannot read the local alias", restarted.list(alias, "moved"))

            // Exercise the real one-shot 30-second idle grace while the service remains bound.
            // There must be no active RC calls/jobs or document streams before the check.
            waitUntil("restarted RC requests to settle", 10_000) { !restarted.hasActiveWork() }
            service.onNotifyUse()
            assertFalse("Service was already stopped before idle grace", isServiceShutdown(service))
            val idleStarted = System.nanoTime()
            waitUntil("bound RcdService idle grace to stop native daemon", 48_000) {
                isServiceShutdown(service) && !restarted.hasLiveProcess()
            }
            assertTrue("Service stopped before its 30-second idle grace",
                TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - idleStarted) >= 25_000)
            assertFalse("The idle daemon is still present in the native process registry",
                RcloneRcd.hasRunningProcess())
        } finally {
            // Any failed assertion must still stop this test's processes before cleaning config.
            cli?.let { child ->
                try {
                    child.destroy()
                    if (!child.waitFor(5, TimeUnit.SECONDS)) { child.destroyForcibly() }
                    Unit
                } catch (_: Exception) { child.destroyForcibly(); Unit }
            }
            for (daemon in daemons.asReversed()) {
                try { if (daemon.hasLiveProcess()) daemon.stopRcd(); Unit } catch (_: Exception) { }
            }
            try {
                waitUntil("test RC native processes to exit before fixture removal", 20_000) {
                    daemons.none { it.hasLiveProcess() } && !RcloneRcd.hasRunningProcess() &&
                        !NativeSessionGate.GLOBAL.isBusy()
                }
            } finally {
                if (bound) context.unbindService(connection)
            }
            if (aliasCreated) {
                checkCommand("delete only this test's local alias", rclone.config("delete", mutableListOf(alias)))
            }
            assertTrue("Could not remove disposable RC local fixture files", root.deleteRecursively())
        }
    }
}
