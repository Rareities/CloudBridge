package ca.pkay.rcloneexplorer.workmanager

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class ProcessMonitorTest {
    private class Child(private val code: Int, stderr: String) : Process() {
        var destroyed = false
        @Volatile var stdoutRead = false
        private val drained = CountDownLatch(1)
        private val stdout = object : ByteArrayInputStream(ByteArray(100000)) {
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                stdoutRead = true
                drained.countDown()
                return super.read(b, off, len)
            }
        }
        private val errors = ByteArrayInputStream(stderr.toByteArray())
        override fun getInputStream(): InputStream = stdout
        override fun getErrorStream(): InputStream = errors
        override fun getOutputStream(): OutputStream = ByteArrayOutputStream()
        override fun waitFor(): Int {
            check(drained.await(5, TimeUnit.SECONDS)) { "stdout must drain concurrently" }
            return code
        }
        override fun exitValue(): Int = code
        override fun destroy() { destroyed = true }
    }

    @Test fun nonzeroExitIsPreservedWithoutErrorLogs() {
        val child = Child(7, "progress\n")
        val lines = mutableListOf<String>()
        assertEquals(7, ProcessMonitor.await(child) { lines.add(it) })
        assertEquals(listOf("progress"), lines)
        assertTrue(child.destroyed)
    }

    @Test fun successfulExitAndBothPipesAreHandled() {
        val child = Child(0, "warning\n")
        assertEquals(0, ProcessMonitor.await(child) {})
        assertTrue(child.stdoutRead)
        assertTrue(child.destroyed)
    }

    @Test fun callbackFailureStillDestroysChild() {
        val child = Child(0, "line\n")
        try {
            ProcessMonitor.await(child) { throw IllegalStateException("failed parser") }
            throw AssertionError("Expected callback failure")
        } catch (expected: IllegalStateException) {
            assertTrue(child.destroyed)
        }
    }
    @Test fun oversizedProgressLineFailsAndDestroysChild() {
        val child = Child(0, "x".repeat(65537))
        try {
            ProcessMonitor.await(child) {}
            throw AssertionError("Expected bounded progress failure")
        } catch (expected: java.io.IOException) {
            assertTrue(child.destroyed)
        }
    }
}
