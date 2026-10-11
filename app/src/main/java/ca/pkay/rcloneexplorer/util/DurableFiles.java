package ca.pkay.rcloneexplorer.util;

import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import java.io.File;
import java.io.FileDescriptor;
import java.io.IOException;
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
}
