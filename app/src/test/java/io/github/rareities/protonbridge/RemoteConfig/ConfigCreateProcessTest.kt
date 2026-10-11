package io.github.rareities.protonbridge.RemoteConfig

import android.app.Application
import android.view.View
import androidx.test.core.app.ApplicationProvider
import io.github.rareities.protonbridge.Rclone
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class ConfigCreateProcessTest {
    private val app: Application get() = ApplicationProvider.getApplicationContext()

    private class Child(output: String, private val code: Int = 0, running: Boolean = false) : Process() {
        val exited = CountDownLatch(if (running) 1 else 0)
        val destroyed = CountDownLatch(1)
        private val stdout = ByteArrayInputStream(output.toByteArray())
        override fun getInputStream() = stdout
        override fun getErrorStream() = ByteArrayInputStream(ByteArray(128 * 1024) { 97 })
        override fun getOutputStream() = ByteArrayOutputStream()
        override fun waitFor(): Int { exited.await(); return code }
        override fun exitValue(): Int {
            if (exited.count != 0L) throw IllegalThreadStateException()
            return code
        }
        override fun destroy() { destroyed.countDown(); exited.countDown() }
    }

    private fun task(start: () -> Process): ConfigCreate {
        val fake = object : Rclone(app) {
            override fun config(action: String, options: MutableList<String>): Process {
                assertEquals("update", action)
                assertTrue(options.contains("--non-interactive"))
                return start()
            }
        }
        return ConfigCreate(arrayListOf("fixture", "internxt"), View(app), View(app), app, fake, "internxt")
    }

    @Test fun validTerminalResponseNeedsSuccessfulExit() {
        assertTrue(task { Child("{\"State\":\"\"}") }.runConfigReconnect("fixture"))
        assertFalse(task { Child("{\"State\":\"\"}", 7) }.runConfigReconnect("fixture"))
    }

    @Test fun repeatedGotoCannotCreateAnEndlessAuthenticationLoop() {
        val calls = AtomicInteger()
        val attempt = task { calls.incrementAndGet(); Child("{\"State\":\"login\"}") }
        assertFalse(attempt.runConfigReconnect("fixture"))
        assertEquals(32, calls.get())
    }

    @Test fun oversizedResponseCannotBeAcceptedOrRetainedWithoutLimit() {
        val output = ("a".repeat(60000) + "\n").repeat(4)
        assertFalse(task { Child(output) }.runConfigReconnect("fixture"))
    }

    @Test(timeout = 10000) fun cancellationStopsTheCurrentReconnectChild() {
        val started = CountDownLatch(1)
        val child = Child("", running = true)
        val attempt = task { started.countDown(); child }
        val result = FutureTask { attempt.runConfigReconnect("fixture") }
        val worker = Thread(result, "auth-test")
        worker.start()
        try {
            assertTrue(started.await(2, TimeUnit.SECONDS))
            attempt.cancel(true)
            assertFalse(result.get(5, TimeUnit.SECONDS))
            assertTrue(child.destroyed.await(1, TimeUnit.SECONDS))
            assertEquals(0L, child.exited.count)
        } finally {
            child.destroy()
            worker.interrupt()
            worker.join(2000)
        }
    }
}
