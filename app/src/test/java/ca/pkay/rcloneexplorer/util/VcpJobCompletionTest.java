package ca.pkay.rcloneexplorer.util;

import org.junit.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class VcpJobCompletionTest {

    @Test
    public void onlyFinishedSuccessfulResponseIsSuccess() {
        VcpJobCompletion completion = new VcpJobCompletion();
        completion.complete(true, true);

        assertEquals(VcpJobCompletion.Outcome.SUCCEEDED, completion.await(0));
    }

    @Test
    public void finishedFailureIsNotSuccess() {
        VcpJobCompletion completion = new VcpJobCompletion();
        completion.complete(true, false);

        assertEquals(VcpJobCompletion.Outcome.FAILED, completion.await(0));
    }

    @Test
    public void timeoutRemainsUnknownEvenIfLateSuccessArrives() {
        VcpJobCompletion completion = new VcpJobCompletion();
        assertEquals(VcpJobCompletion.Outcome.UNKNOWN, completion.await(0));

        completion.complete(true, true);

        assertEquals(VcpJobCompletion.Outcome.SUCCEEDED, completion.await(0));
    }

    @Test
    public void callbackMayOnlyCompleteOnce() {
        VcpJobCompletion completion = new VcpJobCompletion();
        completion.complete(true, true);
        completion.complete(true, false);

        assertEquals(VcpJobCompletion.Outcome.SUCCEEDED, completion.await(0));
    }

    @Test
    public void nonTerminalResponseRemainsUnknown() {
        VcpJobCompletion completion = new VcpJobCompletion();
        completion.complete(false, true);

        assertEquals(VcpJobCompletion.Outcome.UNKNOWN, completion.await(0));
    }

    @Test
    public void sourceGrantMayOnlyBeRevokedAfterConfirmedSuccess() {
        assertTrue(VcpJobCompletion.isConfirmedSuccess(true, true));
        assertFalse(VcpJobCompletion.isConfirmedSuccess(true, false));
        assertFalse(VcpJobCompletion.isConfirmedSuccess(false, true));
    }

    @Test
    public void interruptedWaitIsUnknownAndRestoresInterruptFlag() {
        VcpJobCompletion completion = new VcpJobCompletion();
        AtomicReference<VcpJobCompletion.Outcome> outcome = new AtomicReference<>();
        AtomicReference<Boolean> interrupted = new AtomicReference<>(false);
        CountDownLatch enteringWait = new CountDownLatch(1);
        Thread waiter = new Thread(() -> {
            enteringWait.countDown();
            outcome.set(completion.await(TimeUnitHolder.LONG_WAIT_MILLIS));
            interrupted.set(Thread.currentThread().isInterrupted());
        });
        waiter.start();
        try {
            enteringWait.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
        waiter.interrupt();
        try {
            waiter.join(1000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }

        assertEquals(VcpJobCompletion.Outcome.UNKNOWN, outcome.get());
        assertTrue(interrupted.get());
    }

    private static final class TimeUnitHolder {
        private static final long LONG_WAIT_MILLIS = 60_000;
    }
}
