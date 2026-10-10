package ca.pkay.rcloneexplorer.workmanager

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class ProcessDeadlineTest {
    private class StuckProcess:Process() {
        val terminated=CountDownLatch(1)
        val killed=CountDownLatch(1)
        override fun getInputStream()=ByteArrayInputStream(byteArrayOf())
        override fun getErrorStream()=ByteArrayInputStream(byteArrayOf())
        override fun getOutputStream()=ByteArrayOutputStream()
        override fun waitFor():Int { killed.await(); return 137 }
        override fun exitValue():Int { if(killed.count>0) throw IllegalThreadStateException(); return 137 }
        override fun destroy() { terminated.countDown() }
        override fun destroyForcibly():Process { killed.countDown(); return this }
    }
    @Test fun runtimeDeadlineRequestsGracefulThenForcedExit() {
        val process=StuckProcess()
        val expired=CountDownLatch(1)
        val deadline=ProcessDeadline(process,10,{ expired.countDown() },20)
        assertTrue(expired.await(2,TimeUnit.SECONDS))
        assertTrue(process.terminated.await(2,TimeUnit.SECONDS))
        assertTrue(process.killed.await(2,TimeUnit.SECONDS))
        deadline.close()
    }
    @Test fun closingAliveProcessRetainsSecondStageCleanup() {
        val process=StuckProcess()
        val deadline=ProcessDeadline(process,60_000,{ fail("Completed worker must not expire") },20)
        deadline.close()
        assertTrue(process.terminated.await(2,TimeUnit.SECONDS))
        assertTrue(process.killed.await(2,TimeUnit.SECONDS))
    }
}
