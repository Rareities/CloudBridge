package ca.pkay.rcloneexplorer.util;

import java.io.File;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;

/** Deletes one private staged upload only after its native process is confirmed stopped. */
public final class StagedUploadSourceCleanup implements AutoCloseable {

    private static final String LOG_TAG = "StagedUploadCleanup";

    private final File appCacheDir;
    private final File stagedFile;
    private final AtomicBoolean cleanupInProgress = new AtomicBoolean();
    private final AtomicBoolean cleanupComplete = new AtomicBoolean();
    private final AtomicBoolean launchMayBeRunning = new AtomicBoolean();
    private volatile NativeExecutionHandle execution;

    public StagedUploadSourceCleanup(File appCacheDir, File stagedFile) {
        if (appCacheDir == null || stagedFile == null) {
            throw new NullPointerException("cache directory and staged file are required");
        }
        this.appCacheDir = appCacheDir;
        this.stagedFile = stagedFile;
    }

    /** Marks the point after preflight where native launch may create a reader for this file. */
    public void markLaunchAttempted() {
        launchMayBeRunning.set(true);
    }

    /** Allows cleanup only when the launcher proved that no process was returned or started. */
    void confirmNoProcessStarted() {
        launchMayBeRunning.set(false);
        close();
    }

    /** Registers late cleanup with the owner before it begins waiting for process completion. */
    public boolean attachTo(NativeExecutionHandle nativeExecution) {
        if (nativeExecution == null) {
            return false;
        }
        execution = nativeExecution;
        if (nativeExecution.attachResource(this)) {
            return true;
        }
        if (nativeExecution.isExitConfirmed()) {
            close();
        } else {
            FLog.e(LOG_TAG, "Unable to register staged-source cleanup while native exit is unconfirmed");
        }
        return false;
    }

    /** Requests a bounded reap; unconfirmed exit leaves the resource registered for a late reap. */
    public boolean cleanupAfter(NativeExecutionHandle nativeExecution) {
        if (nativeExecution != null) {
            execution = nativeExecution;
            if (!nativeExecution.isExitConfirmed()) {
                final NativeExecutionHandle.Outcome outcome;
                try {
                    outcome = nativeExecution.cancelAndAwait(null, null);
                } catch (RuntimeException failure) {
                    FLog.e(LOG_TAG, "Unable to confirm completion of temporary shared upload");
                    return false;
                }
                if (!outcome.isConfirmed() && !nativeExecution.isExitConfirmed()) {
                    FLog.e(LOG_TAG, "Retaining temporary shared upload because native exit is unconfirmed");
                    return false;
                }
            }
        }

        close();
        return cleanupComplete.get();
    }

    @Override
    public void close() {
        NativeExecutionHandle nativeExecution = execution;
        if (nativeExecution == null && launchMayBeRunning.get()) {
            return;
        }
        if (nativeExecution != null && !nativeExecution.isExitConfirmed()) {
            return;
        }
        if (cleanupComplete.get() || !cleanupInProgress.compareAndSet(false, true)) {
            return;
        }

        try {
            ShareStagingPolicy.cleanupUploadedFile(appCacheDir, stagedFile);
            cleanupComplete.set(true);
        } catch (IOException | RuntimeException failure) {
            FLog.e(LOG_TAG, "Unable to clean temporary shared upload");
        } finally {
            cleanupInProgress.set(false);
        }
    }
}
