package ca.pkay.rcloneexplorer.util;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

public class ConfigFileRestorerTest {
    @Rule
    public final TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void successfulReplacementWritesSnapshotAndFinishesAtomicFile() throws Exception {
        File destination = file("rclone.conf", "imported config");
        File snapshot = file("before-import.bak", "previous config");
        TestAtomicFile atomicFile = new TestAtomicFile(destination);

        ConfigFileRestorer.restore(snapshot, destination, 1024, atomicFile);

        assertArrayEquals(bytes("previous config"), read(destination));
        assertFalse(atomicFile.backup.exists());
        assertTrue(atomicFile.finished);
        assertFalse(atomicFile.failed);
    }

    @Test
    public void failedReplacementRestoresAndVerifiesExactPriorFile() throws Exception {
        File destination = file("rclone.conf", "imported config");
        File snapshot = file("before-import.bak", "previous config");
        TestAtomicFile atomicFile = new TestAtomicFile(destination);
        atomicFile.failWrites = true;

        try {
            ConfigFileRestorer.restore(snapshot, destination, 1024, atomicFile);
            fail("the injected write failure must be reported");
        } catch (ConfigFileRestorer.ReplacementException failure) {
            assertTrue(failure.isPreviousStateRestored());
            assertTrue(atomicFile.failed);
            assertFalse(atomicFile.finished);
        }

        assertArrayEquals(bytes("imported config"), read(destination));
        assertFalse(atomicFile.backup.exists());
    }

    @Test
    public void failedRollbackDoesNotClaimPriorFileWasRestored() throws Exception {
        File destination = file("rclone.conf", "imported config");
        File snapshot = file("before-import.bak", "previous config");
        TestAtomicFile atomicFile = new TestAtomicFile(destination);
        atomicFile.failWrites = true;
        atomicFile.failRecovery = true;

        try {
            ConfigFileRestorer.restore(snapshot, destination, 1024, atomicFile);
            fail("the injected write failure must be reported");
        } catch (ConfigFileRestorer.ReplacementException failure) {
            assertFalse(failure.isPreviousStateRestored());
            assertTrue(atomicFile.failed);
        }
    }

    @Test
    public void oversizedSnapshotIsRejectedBeforeAtomicWriteStarts() throws Exception {
        File destination = file("rclone.conf", "old");
        File snapshot = file("before-import.bak", "too large");
        TestAtomicFile atomicFile = new TestAtomicFile(destination);

        try {
            ConfigFileRestorer.restore(snapshot, destination, 3, atomicFile);
            fail("an oversized snapshot must be rejected");
        } catch (IOException expected) {
            assertFalse(atomicFile.started);
        }

        assertArrayEquals(bytes("old"), read(destination));
    }

    private File file(String name, String contents) throws IOException {
        File result = new File(temporaryFolder.getRoot(), name);
        try (FileOutputStream output = new FileOutputStream(result)) {
            output.write(bytes(contents));
            output.getFD().sync();
        }
        return result;
    }

    private static byte[] read(File file) throws IOException {
        try (FileInputStream input = new FileInputStream(file)) {
            byte[] result = new byte[(int) file.length()];
            int offset = 0;
            while (offset < result.length) {
                int count = input.read(result, offset, result.length - offset);
                if (count < 0) break;
                offset += count;
            }
            return offset == result.length ? result : java.util.Arrays.copyOf(result, offset);
        }
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private static final class TestAtomicFile implements ConfigFileRestorer.AtomicFileOperations {
        private final File destination;
        private final File backup;
        private boolean started;
        private boolean finished;
        private boolean failed;
        private boolean failWrites;
        private boolean failRecovery;
        private OutputStream currentOutput;

        private TestAtomicFile(File destination) {
            this.destination = destination;
            this.backup = new File(destination.getParentFile(), destination.getName() + ".bak");
        }

        @Override
        public InputStream openRead() throws IOException {
            if (failRecovery && backup.exists()) {
                throw new IOException("injected recovery failure");
            }
            if (backup.exists()) {
                if (destination.exists() && !destination.delete()) {
                    throw new IOException("unable to remove incomplete file");
                }
                if (!backup.renameTo(destination)) {
                    throw new IOException("unable to restore backup");
                }
            }
            if (!destination.isFile()) throw new java.io.FileNotFoundException(destination.getName());
            return new FileInputStream(destination);
        }

        @Override
        public OutputStream startWrite() throws IOException {
            started = true;
            if (destination.exists()) {
                copy(destination, backup);
            }
            FileOutputStream output = new FileOutputStream(destination, false);
            currentOutput = failWrites ? new OutputStream() {
                @Override
                public void write(int value) throws IOException {
                    throw new IOException("injected disk-full write failure");
                }

                @Override
                public void write(byte[] bytes, int offset, int length) throws IOException {
                    throw new IOException("injected disk-full write failure");
                }

                @Override
                public void flush() throws IOException {
                    output.flush();
                }

                @Override
                public void close() throws IOException {
                    output.close();
                }
            } : output;
            return currentOutput;
        }

        @Override
        public void finishWrite(OutputStream output) {
            try {
                output.close();
                if (backup.exists() && !backup.delete()) {
                    throw new IllegalStateException("unable to remove atomic backup");
                }
                finished = true;
            } catch (IOException failure) {
                throw new IllegalStateException(failure);
            }
        }

        @Override
        public void failWrite(OutputStream output) {
            failed = true;
            try {
                output.close();
                if (failRecovery) return;
                if (backup.exists()) {
                    if (destination.exists() && !destination.delete()) return;
                    backup.renameTo(destination);
                } else if (destination.exists()) {
                    destination.delete();
                }
            } catch (IOException ignored) {
                // The caller verifies the exact file state and fails closed.
            }
        }

        private static void copy(File source, File destination) throws IOException {
            try (InputStream input = new FileInputStream(source);
                 OutputStream output = new FileOutputStream(destination, false)) {
                byte[] buffer = new byte[256];
                int count;
                while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
                output.flush();
            }
        }
    }
}
