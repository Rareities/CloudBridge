package ca.pkay.rcloneexplorer;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;
import static org.junit.Assert.*;

public class RcJobRegistrationTest {
    @Test public void fastJobCanNeverBeObservedBeforeItsHandler() throws Exception {
        for (int iteration = 0; iteration < 100; iteration++) {
            LinkedBlockingQueue<Integer> queue = new LinkedBlockingQueue<>();
            java.util.concurrent.ConcurrentHashMap<Integer, String> handlers = new java.util.concurrent.ConcurrentHashMap<>();
            AtomicReference<String> observed = new AtomicReference<>();
            CountDownLatch received = new CountDownLatch(1);
            Thread monitor = new Thread(() -> {
                try {
                    Integer job = queue.take();
                    observed.set(handlers.get(job));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    received.countDown();
                }
            });
            monitor.start();
            RcJobRegistration.publish(iteration, "ready", handlers::put, queue);
            assertTrue(received.await(2, TimeUnit.SECONDS));
            assertEquals("ready", observed.get());
            monitor.join(2000);
        }
    }
}
