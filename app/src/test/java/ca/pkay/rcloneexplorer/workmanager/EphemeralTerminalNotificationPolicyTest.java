package ca.pkay.rcloneexplorer.workmanager;

import org.junit.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class EphemeralTerminalNotificationPolicyTest {
    @Test
    public void cancellationBeforeClaimWinsAndCanOnlyBeClaimedOnce() {
        EphemeralTerminalNotificationPolicy policy = new EphemeralTerminalNotificationPolicy();
        policy.requestCancellation();

        assertEquals(EphemeralTerminalNotificationPolicy.Outcome.CANCELLED,
                policy.claim(false, true, false, false));
        assertEquals(EphemeralTerminalNotificationPolicy.Outcome.ALREADY_CLAIMED,
                policy.claim(false, false, true, true));
        assertTrue(policy.isClaimed());
    }

    @Test
    public void cancellationAfterSuccessCannotReplaceTheClaimedOutcome() {
        EphemeralTerminalNotificationPolicy policy = new EphemeralTerminalNotificationPolicy();

        assertEquals(EphemeralTerminalNotificationPolicy.Outcome.SUCCESS,
                policy.claim(false, false, true, true));
        policy.requestCancellation();
        assertEquals(EphemeralTerminalNotificationPolicy.Outcome.ALREADY_CLAIMED,
                policy.claim(false, true, true, true));
    }

    @Test
    public void stateUpdatesAfterTheTerminalClaimAreRejected() {
        EphemeralTerminalNotificationPolicy policy = new EphemeralTerminalNotificationPolicy();
        AtomicBoolean changed = new AtomicBoolean();
        assertEquals(EphemeralTerminalNotificationPolicy.Outcome.SUCCESS,
                policy.claim(false, false, true, true));

        assertFalse(policy.updateIfPending(() -> changed.set(true)));
        assertFalse(changed.get());
    }

    @Test
    public void stateUpdateAndTerminalClaimShareOneAtomicBoundary() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            for (int iteration = 0; iteration < 100; iteration++) {
                EphemeralTerminalNotificationPolicy policy = new EphemeralTerminalNotificationPolicy();
                AtomicBoolean failureReported = new AtomicBoolean();
                CountDownLatch ready = new CountDownLatch(2);
                CountDownLatch start = new CountDownLatch(1);
                Future<Boolean> update = executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    return policy.updateIfPending(() -> failureReported.set(true));
                });
                Future<EphemeralTerminalNotificationPolicy.Outcome> claim = executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    synchronized (policy) {
                        return policy.claim(failureReported.get(), false, true, true);
                    }
                });

                assertTrue("both contenders reach the barrier", ready.await(5, TimeUnit.SECONDS));
                start.countDown();
                boolean updated = update.get(5, TimeUnit.SECONDS);
                EphemeralTerminalNotificationPolicy.Outcome outcome = claim.get(5, TimeUnit.SECONDS);
                assertEquals(updated ? EphemeralTerminalNotificationPolicy.Outcome.FAILURE
                        : EphemeralTerminalNotificationPolicy.Outcome.SUCCESS, outcome);
            }
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    public void successRequiresNoFailureAndConfirmedSuccessfulNativeExit() {
        assertOutcome(EphemeralTerminalNotificationPolicy.Outcome.FAILURE, false, false, false, false);
        assertOutcome(EphemeralTerminalNotificationPolicy.Outcome.FAILURE, false, false, false, true);
        assertOutcome(EphemeralTerminalNotificationPolicy.Outcome.FAILURE, false, false, true, false);
        assertOutcome(EphemeralTerminalNotificationPolicy.Outcome.FAILURE, true, false, true, true);
        assertOutcome(EphemeralTerminalNotificationPolicy.Outcome.CANCELLED, false, true, true, true);
        assertOutcome(EphemeralTerminalNotificationPolicy.Outcome.SUCCESS, false, false, true, true);
    }

    @Test
    public void concurrentStopAndSuccessfulCompletionClaimAtMostOneOutcome() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            for (int iteration = 0; iteration < 100; iteration++) {
                EphemeralTerminalNotificationPolicy policy = new EphemeralTerminalNotificationPolicy();
                CountDownLatch ready = new CountDownLatch(2);
                CountDownLatch start = new CountDownLatch(1);
                Future<?> stop = executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    policy.requestCancellation();
                    return null;
                });
                Future<EphemeralTerminalNotificationPolicy.Outcome> completion = executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    return policy.claim(false, false, true, true);
                });

                assertTrue("both contenders reach the barrier", ready.await(5, TimeUnit.SECONDS));
                start.countDown();
                stop.get(5, TimeUnit.SECONDS);
                EphemeralTerminalNotificationPolicy.Outcome firstClaim = completion.get(5, TimeUnit.SECONDS);
                assertTrue(firstClaim == EphemeralTerminalNotificationPolicy.Outcome.SUCCESS
                        || firstClaim == EphemeralTerminalNotificationPolicy.Outcome.CANCELLED);
                assertEquals(EphemeralTerminalNotificationPolicy.Outcome.ALREADY_CLAIMED,
                        policy.claim(false, false, true, true));
            }
        } finally {
            executor.shutdownNow();
        }
    }

    private static void assertOutcome(
            EphemeralTerminalNotificationPolicy.Outcome expected,
            boolean failureReported,
            boolean cancellationReported,
            boolean nativeExitConfirmed,
            boolean nativeExitSucceeded) {
        EphemeralTerminalNotificationPolicy policy = new EphemeralTerminalNotificationPolicy();
        assertEquals(expected, policy.claim(failureReported, cancellationReported,
                nativeExitConfirmed, nativeExitSucceeded));
    }
}
