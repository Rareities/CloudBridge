package ca.pkay.rcloneexplorer.util;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;

public class BoundedResponseReaderTest {

    @Test
    public void acceptsEmptyAndExactLimitResponses() throws Exception {
        assertArrayEquals(new byte[0], BoundedResponseReader.read(
                new ByteArrayInputStream(new byte[0]), 0));
        byte[] payload = new byte[]{1, 2, 3, 4};
        assertArrayEquals(payload, BoundedResponseReader.read(
                new ByteArrayInputStream(payload), payload.length));
    }

    @Test
    public void rejectsResponseOneByteOverLimit() throws Exception {
        try {
            BoundedResponseReader.read(new ByteArrayInputStream(new byte[]{1, 2, 3}), 2);
            fail("oversized response must fail before its body is buffered");
        } catch (IOException expected) {
            // expected
        }
    }

    @Test
    public void rejectsMissingStreamAndNegativeLimit() throws Exception {
        try {
            BoundedResponseReader.read(null, 1);
            fail("missing body must fail closed");
        } catch (IOException expected) {
            // expected
        }
        try {
            BoundedResponseReader.read(new ByteArrayInputStream(new byte[0]), -1);
            fail("negative limit must be rejected");
        } catch (IllegalArgumentException expected) {
            // expected
        }
    }
}
