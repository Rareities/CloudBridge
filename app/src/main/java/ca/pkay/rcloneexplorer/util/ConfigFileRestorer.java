package ca.pkay.rcloneexplorer.util;

import android.util.AtomicFile;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** Crash-recoverable single-file replacement for the private rclone config. */
public final class ConfigFileRestorer {
    private static final Object LOCK = new Object();
    private static final int BUFFER_BYTES = 8192;

    private ConfigFileRestorer() {
    }

    /**
     * Causes Android AtomicFile to restore its previous base file if a write was
     * interrupted before finishWrite. Call before any native process reads config.
     */
    public static void recover(File destination) throws IOException {
        if (destination == null) throw new IllegalArgumentException("Config path is missing");
        synchronized (LOCK) {
            recoverLocked(destination);
        }
    }

    /**
     * Replaces destination with snapshot using Android AtomicFile. The source must be
     * in the same private directory and remain intact until the caller commits its
     * larger multi-store transaction.
     */
    public static void restore(File snapshot, File destination, long maxBytes) throws IOException {
        if (snapshot == null || destination == null) {
            throw new IllegalArgumentException("Config snapshot or destination is missing");
        }
        if (maxBytes < 0) throw new IllegalArgumentException("Config size limit is invalid");
        File snapshotParent = snapshot.getCanonicalFile().getParentFile();
        File destinationParent = destination.getCanonicalFile().getParentFile();
        if (snapshotParent == null || !snapshotParent.equals(destinationParent)
                || snapshot.getCanonicalFile().equals(destination.getCanonicalFile())) {
            throw new IOException("Config snapshot is outside the destination directory");
        }
        if (!snapshot.isFile() || snapshot.length() > maxBytes) {
            throw new IOException("Config snapshot is missing or exceeds the maximum size");
        }

        synchronized (LOCK) {
            replace(snapshot, maxBytes, new AndroidAtomicFileOperations(destination));
        }
    }

    private static void recoverLocked(File destination) throws IOException {
        AtomicFileOperations operations = new AndroidAtomicFileOperations(destination);
        try (InputStream ignored = operations.openRead()) {
            // openRead performs AtomicFile's backup recovery before returning.
        } catch (FileNotFoundException noConfig) {
            // A missing config is a supported initial-install state.
        }
    }

    /** Delete the base and any AtomicFile recovery copy as one serialized operation. */
    public static boolean delete(File destination) {
        if (destination == null) return false;
        synchronized (LOCK) {
            new AtomicFile(destination).delete();
            return !destination.exists() && !new File(destination.getPath() + ".bak").exists();
        }
    }

    static void restore(File snapshot, File destination, long maxBytes,
                        AtomicFileOperations operations) throws IOException {
        if (snapshot == null || destination == null) {
            throw new IllegalArgumentException("Config snapshot or destination is missing");
        }
        if (maxBytes < 0) throw new IllegalArgumentException("Config size limit is invalid");
        File snapshotParent = snapshot.getCanonicalFile().getParentFile();
        File destinationParent = destination.getCanonicalFile().getParentFile();
        if (snapshotParent == null || !snapshotParent.equals(destinationParent)
                || snapshot.getCanonicalFile().equals(destination.getCanonicalFile())) {
            throw new IOException("Config snapshot is outside the destination directory");
        }
        if (!snapshot.isFile() || snapshot.length() > maxBytes) {
            throw new IOException("Config snapshot is missing or exceeds the maximum size");
        }
        synchronized (LOCK) {
            replace(snapshot, maxBytes, operations);
        }
    }

    private static void replace(File snapshot, long maxBytes, AtomicFileOperations operations)
            throws IOException {
        byte[] previous = fingerprintBeforeWrite(operations, maxBytes);
        boolean previouslyExisted = previous != null;
        OutputStream output = null;
        IOException failure = null;
        try {
            output = operations.startWrite();
            try (InputStream input = new FileInputStream(snapshot)) {
                copyBounded(input, output, maxBytes);
            }
            output.flush();
            operations.finishWrite(output);
            output = null;
        } catch (IOException writeFailure) {
            failure = writeFailure;
        } catch (RuntimeException writeFailure) {
            failure = new IOException("Config replacement failed", writeFailure);
        }

        if (failure == null) return;

        if (output != null) {
            try {
                operations.failWrite(output);
            } catch (RuntimeException rollbackFailure) {
                failure.addSuppressed(rollbackFailure);
            }
        }

        boolean previousStateRestored = matchesPriorState(
                operations, previouslyExisted, previous, maxBytes, failure);
        throw new ReplacementException(previousStateRestored, failure);
    }

    private static byte[] fingerprintBeforeWrite(AtomicFileOperations operations, long maxBytes)
            throws IOException {
        try (InputStream input = operations.openRead()) {
            return digestBounded(input, maxBytes);
        } catch (FileNotFoundException noConfig) {
            return null;
        }
    }

    private static boolean matchesPriorState(AtomicFileOperations operations,
                                             boolean previouslyExisted,
                                             byte[] previous,
                                             long maxBytes,
                                             IOException failure) {
        try (InputStream input = operations.openRead()) {
            if (!previouslyExisted) return false;
            return MessageDigest.isEqual(previous, digestBounded(input, maxBytes));
        } catch (FileNotFoundException missing) {
            return !previouslyExisted;
        } catch (IOException | RuntimeException recoveryFailure) {
            failure.addSuppressed(recoveryFailure);
            return false;
        }
    }

    private static void copyBounded(InputStream input, OutputStream output, long maxBytes)
            throws IOException {
        byte[] buffer = new byte[BUFFER_BYTES];
        long total = 0;
        int count;
        while ((count = input.read(buffer)) != -1) {
            total += count;
            if (total > maxBytes) throw new IOException("Config exceeds the maximum size");
            output.write(buffer, 0, count);
        }
    }

    private static byte[] digestBounded(InputStream input, long maxBytes) throws IOException {
        final MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IOException("SHA-256 is unavailable", impossible);
        }
        byte[] buffer = new byte[BUFFER_BYTES];
        long total = 0;
        int count;
        while ((count = input.read(buffer)) != -1) {
            total += count;
            if (total > maxBytes) throw new IOException("Config exceeds the maximum size");
            digest.update(buffer, 0, count);
        }
        return digest.digest();
    }

    interface AtomicFileOperations {
        InputStream openRead() throws IOException;

        OutputStream startWrite() throws IOException;

        void finishWrite(OutputStream output);

        void failWrite(OutputStream output);
    }

    private static final class AndroidAtomicFileOperations implements AtomicFileOperations {
        private final AtomicFile atomicFile;

        private AndroidAtomicFileOperations(File destination) {
            atomicFile = new AtomicFile(destination);
        }

        @Override
        public InputStream openRead() throws IOException {
            return atomicFile.openRead();
        }

        @Override
        public OutputStream startWrite() throws IOException {
            return atomicFile.startWrite();
        }

        @Override
        public void finishWrite(OutputStream output) {
            atomicFile.finishWrite((FileOutputStream) output);
        }

        @Override
        public void failWrite(OutputStream output) {
            atomicFile.failWrite((FileOutputStream) output);
        }
    }

    /** Signals whether a failed write returned the file to its exact pre-write bytes. */
    public static final class ReplacementException extends IOException {
        private final boolean previousStateRestored;

        private ReplacementException(boolean previousStateRestored, IOException cause) {
            super("Config replacement failed", cause);
            this.previousStateRestored = previousStateRestored;
        }

        public boolean isPreviousStateRestored() {
            return previousStateRestored;
        }
    }
}
