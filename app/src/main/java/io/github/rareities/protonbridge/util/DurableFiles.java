package io.github.rareities.protonbridge.util;

import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import java.io.File;
import java.io.FileDescriptor;
import java.io.IOException;
import java.io.InputStream;
import android.util.AtomicFile;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.io.RandomAccessFile;

/** Content and directory-entry barriers for the private restore transaction. */
public final class DurableFiles {
    private DurableFiles() {}
    interface DirectorySync { void sync(File directory) throws IOException; }
    static DirectorySync directorySync = DurableFiles::syncDirectoryNative;

    public static void syncDirectory(File directory) throws IOException {
        directorySync.sync(directory);
    }
    private static void syncDirectoryNative(File directory) throws IOException {
        FileDescriptor fd = null;
        try {
            if (!directory.isDirectory()) throw new IOException("Configuration directory is unavailable");
            fd = Os.open(directory.getAbsolutePath(), OsConstants.O_RDONLY, 0);
            Os.fsync(fd);
        } catch (ErrnoException error) { throw new IOException("Cannot commit configuration directory", error); }
        finally {
            if (fd != null) try { Os.close(fd); }
            catch (ErrnoException error) { throw new IOException("Cannot close configuration directory", error); }
        }
    }
    public static void syncFileIfPresent(File file) throws IOException {
        if (file.isFile()) try (RandomAccessFile handle = new RandomAccessFile(file, "rw")) {
            handle.getFD().sync();
        }
    }
    /** Verifies bounded installed bytes after AtomicFile may have suppressed an I/O failure. */
    public static void verifyContents(AtomicFile target, InputStream expected, int limit) throws IOException {
        try (InputStream installed = target.openRead()) {
            if (!MessageDigest.isEqual(digest(expected, limit), digest(installed, limit)))
                throw new IOException("Installed configuration contents do not match staged data");
        }
    }
    private static byte[] digest(InputStream input, int limit) throws IOException {
        final MessageDigest hash;
        try { hash = MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
        byte[] buffer = new byte[8192];
        try {
            int count, total = 0;
            while ((count = input.read(buffer)) != -1) {
                if (count > limit - total) throw new IOException("Installed configuration exceeds its staged size limit");
                hash.update(buffer, 0, count);
                total += count;
            }
            return hash.digest();
        } finally { Arrays.fill(buffer, (byte) 0); }
    }
}
