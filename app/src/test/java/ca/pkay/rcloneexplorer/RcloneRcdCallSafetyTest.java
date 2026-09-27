package ca.pkay.rcloneexplorer;

import org.junit.Test;

import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class RcloneRcdCallSafetyTest {
    @Test
    public void lostTransportResponseRetainsTheResourceClaim() {
        assertTrue(RcdClaimFailurePolicy.mayHaveStarted(
                new RcloneRcd.RcdIOException(new IOException("connection reset"))));
    }

    @Test
    public void serverFailureRetainsClaimButDefinitiveClientRejectionDoesNot() {
        RcloneRcd.ErrorResponse serverFailure = new RcloneRcd.ErrorResponse();
        serverFailure.status = 503;
        assertTrue(RcdClaimFailurePolicy.mayHaveStarted(new RcloneRcd.RcdOpException(serverFailure)));

        RcloneRcd.ErrorResponse rejected = new RcloneRcd.ErrorResponse();
        rejected.status = 409;
        assertFalse(RcdClaimFailurePolicy.mayHaveStarted(new RcloneRcd.RcdOpException(rejected)));
    }

    @Test
    public void preRequestSerializationFailureAndUnclassifiedRuntimeAreConservative() {
        RcloneRcd.ErrorResponse serializationFailure = new RcloneRcd.ErrorResponse();
        assertFalse(RcdClaimFailurePolicy.mayHaveStarted(
                new RcloneRcd.RcdOpException(serializationFailure)));
        assertFalse(RcdClaimFailurePolicy.mayHaveStarted(new RcloneRcd.RcdOpException(
                RcdExternalFailure.requestTooLarge())));
        assertTrue(RcdClaimFailurePolicy.mayHaveStarted(new IllegalStateException("unknown")));
    }

    @Test
    public void malformedResponseUsesFixedTextAndRetainsUnknownMutationOutcome() {
        RcloneRcd.RcdOpException failure = new RcloneRcd.RcdOpException(
                RcdExternalFailure.invalidResponse());
        assertEquals(RcdExternalFailure.Kind.INVALID_RESPONSE,
                failure.getExternalFailure().getKind());
        assertFalse(failure.getError().contains("raw-secret"));
        assertTrue(RcdClaimFailurePolicy.mayHaveStarted(failure));
    }

    @Test
    public void rcdResponseTextIsTypedAndSanitizedForExternalCallers() {
        RcloneRcd.ErrorResponse response = new RcloneRcd.ErrorResponse();
        response.error = "access token=raw-secret at /private/account/data";
        response.status = 400;
        RcloneRcd.RcdOpException failure = new RcloneRcd.RcdOpException(response);

        assertEquals(RcdExternalFailure.Kind.REQUEST_REJECTED,
                failure.getExternalFailure().getKind());
        assertEquals("Rclone rejected the request.", failure.getError());
        assertFalse(failure.getError().contains("raw-secret"));
        assertFalse(failure.getError().contains("/private/account/data"));
        assertFalse(failure.getMessage().contains("raw-secret"));
    }

    @Test
    public void safeJobNotFoundClassificationAndMutationUnknownSemanticsRemainIntact() {
        RcloneRcd.ErrorResponse missingJob = new RcloneRcd.ErrorResponse();
        missingJob.error = "job not found";
        missingJob.status = 404;
        RcloneRcd.RcdOpException notFound = new RcloneRcd.RcdOpException(missingJob);
        assertTrue(notFound.isJobNotFound());
        assertEquals(RcdExternalFailure.Kind.JOB_NOT_FOUND,
                notFound.getExternalFailure().getKind());
        assertEquals("The requested rclone job is no longer available.", notFound.getError());

        RcloneRcd.ErrorResponse uncertainMutation = new RcloneRcd.ErrorResponse();
        uncertainMutation.error = "token=must-not-escape";
        uncertainMutation.status = 503;
        RcloneRcd.RcdOpException serverFailure = new RcloneRcd.RcdOpException(uncertainMutation);
        assertTrue(RcdClaimFailurePolicy.mayHaveStarted(serverFailure));
        assertTrue(serverFailure.getError().contains("may have started"));
        assertTrue(serverFailure.getError().contains("verify remote state"));

        RcloneRcd.RcdIOException transportFailure =
                new RcloneRcd.RcdIOException(new IOException("password=must-not-escape"));
        assertTrue(RcdClaimFailurePolicy.mayHaveStarted(transportFailure));
        assertEquals(RcdExternalFailure.Kind.TRANSPORT_FAILURE,
                transportFailure.getExternalFailure().getKind());
        assertFalse(transportFailure.getError().contains("must-not-escape"));
        assertFalse(transportFailure.getMessage().contains("must-not-escape"));
        assertTrue(transportFailure.getError().contains("may have started"));
    }

    @Test
    public void callbackExecutorAppliesCallerRunsBackpressureWhenQueueIsFull() throws Exception {
        ThreadPoolExecutor executor = RcloneRcd.newJobCallbackExecutor(1, 1);
        CountDownLatch workerStarted = new CountDownLatch(1);
        CountDownLatch releaseWorker = new CountDownLatch(1);
        AtomicReference<String> overflowThread = new AtomicReference<>();
        try {
            executor.execute(() -> {
                workerStarted.countDown();
                try {
                    releaseWorker.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
            assertTrue(workerStarted.await(1, TimeUnit.SECONDS));
            executor.execute(() -> { }); // occupy the bounded queue

            String callerThread = Thread.currentThread().getName();
            executor.execute(() -> overflowThread.set(Thread.currentThread().getName()));

            assertEquals(callerThread, overflowThread.get());
        } finally {
            releaseWorker.countDown();
            executor.shutdown();
        }
    }

    @Test
    public void callbackExecutorRejectsAfterShutdownForExplicitFallback() {
        ThreadPoolExecutor executor = RcloneRcd.newJobCallbackExecutor(1, 1);
        executor.shutdown();
        try {
            executor.execute(() -> { });
        } catch (RejectedExecutionException expected) {
            return;
        }
        throw new AssertionError("shutdown callback executor must reject for caller fallback");
    }

    @Test
    public void jobStatusHandlersRemainMainThreadByDefaultAndCanOptOut() {
        RcloneRcd.JobStatusHandler defaultHandler = response -> { };
        RcloneRcd.JobStatusHandler backgroundHandler = new RcloneRcd.JobStatusHandler() {
            @Override
            public void handleJobStatus(RcloneRcd.JobStatusResponse response) {
            }

            @Override
            public boolean dispatchOnMainThread() {
                return false;
            }
        };

        assertTrue(defaultHandler.dispatchOnMainThread());
        assertFalse(backgroundHandler.dispatchOnMainThread());
    }

    @Test
    public void jobCallbackBoundaryReturnsHandlerRuntimeFailureForRedactedLogging() {
        RcloneRcd.JobStatusHandler throwingHandler = response -> {
            throw new IllegalStateException("callback failure canary");
        };
        RcloneRcd.JobStatusResponse response = new RcloneRcd.JobStatusResponse();

        RuntimeException failure = RcloneRcd.invokeJobStatusHandler(throwingHandler, response);

        assertNotNull(failure);
        assertEquals("callback failure canary", failure.getMessage());
    }
}
