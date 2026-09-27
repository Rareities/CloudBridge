package ca.pkay.rcloneexplorer.util;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

/** Reads a complete response while enforcing a strict in-memory byte ceiling. */
public final class BoundedResponseReader {

    private static final int BUFFER_BYTES = 8192;

    private BoundedResponseReader() {
    }

    public static byte[] read(InputStream input, int maximumBytes) throws IOException {
        if (input == null) {
            throw new IOException("Response stream is missing");
        }
        if (maximumBytes < 0) {
            throw new IllegalArgumentException("maximumBytes must not be negative");
        }

        ByteArrayOutputStream output = new ByteArrayOutputStream(Math.min(maximumBytes, BUFFER_BYTES));
        byte[] buffer = new byte[BUFFER_BYTES];
        int total = 0;
        for (int count; (count = input.read(buffer, 0, buffer.length)) != -1; ) {
            if (count > maximumBytes - total) {
                throw new IOException("Response exceeds the maximum allowed size");
            }
            output.write(buffer, 0, count);
            total += count;
        }
        return output.toByteArray();
    }
}
