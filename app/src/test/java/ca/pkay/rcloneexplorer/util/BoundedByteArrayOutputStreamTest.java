package ca.pkay.rcloneexplorer.util;

import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

public class BoundedByteArrayOutputStreamTest {
    @Test
    public void acceptsWritesExactlyAtLimit() throws IOException {
        BoundedByteArrayOutputStream output = new BoundedByteArrayOutputStream(4);
        output.write("ab".getBytes(StandardCharsets.UTF_8));
        output.write('c');
        output.write(new byte[]{'d', 'e'}, 0, 1);

        assertArrayEquals("abcd".getBytes(StandardCharsets.UTF_8), output.toByteArray());
    }

    @Test
    public void rejectsAnOversizedBulkWriteWithoutAppendingAPrefix() throws IOException {
        BoundedByteArrayOutputStream output = new BoundedByteArrayOutputStream(3);
        output.write("ab".getBytes(StandardCharsets.UTF_8));
        try {
            output.write("cd".getBytes(StandardCharsets.UTF_8));
            fail("Expected the bounded stream to reject an oversized write");
        } catch (BoundedByteArrayOutputStream.SizeLimitExceededException expected) {
            assertEquals("ab", new String(output.toByteArray(), StandardCharsets.UTF_8));
        }
    }

    @Test
    public void rejectsWritesWhenTheLimitIsZero() throws IOException {
        BoundedByteArrayOutputStream output = new BoundedByteArrayOutputStream(0);
        try {
            output.write(1);
            fail("Expected the zero-capacity stream to reject data");
        } catch (BoundedByteArrayOutputStream.SizeLimitExceededException expected) {
            assertEquals(0, output.toByteArray().length);
        }
    }
}
