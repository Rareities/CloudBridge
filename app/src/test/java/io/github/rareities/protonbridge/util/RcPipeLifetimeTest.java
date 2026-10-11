package io.github.rareities.protonbridge.util;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;
import static org.junit.Assert.*;

public class RcPipeLifetimeTest {
    @Test public void pipeCompletionCannotFreeAdmissionBeforeRcatExit() {
        AtomicInteger released = new AtomicInteger();
        RcPipeLifetime lifetime = new RcPipeLifetime(released::incrementAndGet);
        lifetime.pipeFinished();
        assertEquals(0, released.get());
        lifetime.processFinished();
        lifetime.processFinished();
        lifetime.pipeFinished();
        assertEquals(1, released.get());
    }

    @Test public void processExitCannotFreeAdmissionBeforeConsumerFinishes() {
        AtomicInteger released = new AtomicInteger();
        RcPipeLifetime lifetime = new RcPipeLifetime(released::incrementAndGet);
        lifetime.processFinished();
        assertEquals(0, released.get());
        lifetime.pipeFinished();
        assertEquals(1, released.get());
    }

    @Test public void concurrentCompletionsReleaseExactlyOnce() throws Exception {
        AtomicInteger released = new AtomicInteger();
        RcPipeLifetime lifetime = new RcPipeLifetime(released::incrementAndGet);
        CountDownLatch go = new CountDownLatch(1);
        Thread pipe = new Thread(() -> { try { go.await(); } catch (InterruptedException ignored) { return; } lifetime.pipeFinished(); });
        Thread nativeExit = new Thread(() -> { try { go.await(); } catch (InterruptedException ignored) { return; } lifetime.processFinished(); });
        pipe.start(); nativeExit.start(); go.countDown();
        pipe.join(2000); nativeExit.join(2000);
        assertFalse(pipe.isAlive()); assertFalse(nativeExit.isAlive());
        assertEquals(1, released.get());
    }
}
