package ca.pkay.rcloneexplorer.util;

import org.junit.Test;
import static org.junit.Assert.*;

public class AsyncOperationResultTest {
    @Test public void timeoutIsNeverCompletion() throws Exception {
        AsyncOperationResult result = new AsyncOperationResult(1);
        assertEquals(AsyncOperationResult.Outcome.TIMED_OUT, result.await());
        result.complete(false);
        assertEquals(AsyncOperationResult.Outcome.FAILED, result.await());
    }
    @Test public void firstTerminalStatusWins() throws Exception {
        AsyncOperationResult result = new AsyncOperationResult(0);
        result.complete(false); result.complete(true);
        assertEquals(AsyncOperationResult.Outcome.FAILED, result.await());
        AsyncOperationResult success = new AsyncOperationResult(0);
        success.complete(true);
        assertEquals(AsyncOperationResult.Outcome.SUCCESS, success.await());
    }
    @Test public void interruptionIsNotSuccess() throws Exception {
        AsyncOperationResult result = new AsyncOperationResult(5000);
        Thread.currentThread().interrupt();
        try { result.await(); fail("Expected interruption"); }
        catch (InterruptedException expected) { assertFalse(Thread.currentThread().isInterrupted()); }
        finally { Thread.interrupted(); }
    }
}
