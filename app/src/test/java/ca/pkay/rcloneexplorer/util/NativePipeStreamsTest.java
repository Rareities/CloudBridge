package ca.pkay.rcloneexplorer.util;

import java.io.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.Test;
import static org.junit.Assert.*;

public class NativePipeStreamsTest {
    private static class Child extends Process {
        volatile boolean destroyed;
        final boolean live;
        final int code;
        final InputStream input = new ByteArrayInputStream(new byte[]{1, 2});
        final InputStream error = new ByteArrayInputStream(new byte[100000]);
        final CountDownLatch exited;
        final CountDownLatch outputClosed = new CountDownLatch(1);
        final AtomicBoolean prematureEof = new AtomicBoolean();
        final ByteArrayOutputStream output = new ByteArrayOutputStream() {
            @Override public void close() {
                if (live && !destroyed) prematureEof.set(true);
                outputClosed.countDown();
            }
        };
        Child(int code, boolean live) { this.code = code; this.live = live; exited = new CountDownLatch(live ? 1 : 0); }
        @Override public InputStream getInputStream() { return input; }
        @Override public InputStream getErrorStream() { return error; }
        @Override public OutputStream getOutputStream() { return output; }
        @Override public int exitValue() {
            if (live && !destroyed) throw new IllegalThreadStateException();
            return destroyed ? 143 : code;
        }
        @Override public int waitFor() throws InterruptedException { exited.await(); return exitValue(); }
        @Override public void destroy() { destroyed = true; exited.countDown(); }
    }
    @Test public void nonzeroDownloadExitIsNotSuccessfulEof() throws Exception {
        Child child = new Child(7, false);
        try (InputStream stream = NativePipeStreams.download(child)) {
            assertEquals(1, stream.read()); assertEquals(2, stream.read());
            try { stream.read(); fail("Expected failed download"); } catch (IOException expected) { }
        }
    }
    @Test public void successfulUploadCloseVerifiesCompletion() throws Exception {
        Child child = new Child(0, false);
        OutputStream stream = NativePipeStreams.upload(child);
        stream.write(new byte[]{3, 4}); stream.close(); stream.close();
        assertArrayEquals(new byte[]{3, 4}, child.output.toByteArray());
        assertFalse(child.destroyed);
    }
    @Test public void nonzeroUploadExitFailsClose() throws Exception {
        Child child = new Child(7, false);
        OutputStream stream = NativePipeStreams.upload(child);
        try { stream.close(); fail("Expected failed upload"); } catch (IOException expected) { }
    }
    @Test public void cancelDestroysBeforeSupplyingEofToUpload() throws Exception {
        Child child = new Child(0, true);
        OutputStream stream = NativePipeStreams.upload(child);
        ((TransferControl) stream).abort();
        try { stream.close(); fail("Cancelled upload cannot succeed"); } catch (IOException expected) { }
        assertTrue(child.outputClosed.await(5, TimeUnit.SECONDS));
        assertTrue(child.destroyed);
        assertFalse(child.prematureEof.get());
    }
    @Test public void closingIncompleteDownloadStopsChild() throws Exception {
        Child child = new Child(0, true);
        InputStream stream = NativePipeStreams.download(child);
        stream.close(); stream.close();
        assertTrue(child.destroyed);
    }
}
