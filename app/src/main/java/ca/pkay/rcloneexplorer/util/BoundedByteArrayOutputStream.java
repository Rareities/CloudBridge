package ca.pkay.rcloneexplorer.util;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;

/** Collects serialized request bytes without ever growing beyond a caller-supplied limit. */
public final class BoundedByteArrayOutputStream extends OutputStream {
    private final int maxBytes;
    private final ByteArrayOutputStream bytes;

    public BoundedByteArrayOutputStream(int maxBytes) {
        if (maxBytes < 0) {
            throw new IllegalArgumentException("maxBytes must be non-negative");
        }
        this.maxBytes = maxBytes;
        this.bytes = new ByteArrayOutputStream(Math.min(maxBytes, 1024));
    }

    @Override
    public void write(int value) throws IOException {
        requireCapacity(1);
        bytes.write(value);
    }

    @Override
    public void write(byte[] value, int offset, int length) throws IOException {
        if (value == null) {
            throw new NullPointerException("value");
        }
        if (offset < 0 || length < 0 || length > value.length - offset) {
            throw new IndexOutOfBoundsException();
        }
        requireCapacity(length);
        bytes.write(value, offset, length);
    }

    public byte[] toByteArray() {
        return bytes.toByteArray();
    }

    private void requireCapacity(int additionalBytes) throws SizeLimitExceededException {
        if (additionalBytes > maxBytes - bytes.size()) {
            throw new SizeLimitExceededException();
        }
    }

    public static final class SizeLimitExceededException extends IOException {
        public SizeLimitExceededException() {
            super("Serialized request exceeds the configured size limit");
        }
    }
}
