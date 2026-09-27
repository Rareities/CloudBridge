package ca.pkay.rcloneexplorer.util;

import java.io.IOException;
import java.io.InputStream;
import java.io.PushbackInputStream;

/** Content-only format sniffing for the app's ZIP backup and raw rclone config importer. */
public final class BackupImportFormat {
    private static final int SIGNATURE_LENGTH = 4;

    private BackupImportFormat() {
    }

    /**
     * Reads and restores the leading bytes so the selected stream can be passed directly to the
     * ZIP stager or retained for the raw-config fallback. MIME type and display name are not
     * inputs; a positive result is only a ZIP candidate and must still be fully validated.
     */
    public static DetectedInput inspect(InputStream source) throws IOException {
        if (source == null) {
            throw new IOException("Selected import stream is unavailable");
        }

        PushbackInputStream input = new PushbackInputStream(source, SIGNATURE_LENGTH);
        byte[] prefix = new byte[SIGNATURE_LENGTH];
        int count = 0;
        try {
            while (count < prefix.length) {
                int value = input.read();
                if (value == -1) {
                    break;
                }
                prefix[count++] = (byte) value;
            }
            if (count > 0) {
                input.unread(prefix, 0, count);
            }
        } catch (IOException failure) {
            try {
                input.close();
            } catch (IOException closeFailure) {
                failure.addSuppressed(closeFailure);
            }
            throw failure;
        }

        boolean zipCandidate = count == SIGNATURE_LENGTH
                && (hasSignature(prefix, 0x50, 0x4b, 0x03, 0x04)
                || hasSignature(prefix, 0x50, 0x4b, 0x05, 0x06));
        return new DetectedInput(input, zipCandidate);
    }

    private static boolean hasSignature(byte[] prefix, int b0, int b1, int b2, int b3) {
        return (prefix[0] & 0xff) == b0 && (prefix[1] & 0xff) == b1
                && (prefix[2] & 0xff) == b2 && (prefix[3] & 0xff) == b3;
    }

    public static final class DetectedInput implements AutoCloseable {
        private final PushbackInputStream stream;
        private final boolean zipArchive;

        private DetectedInput(PushbackInputStream stream, boolean zipArchive) {
            this.stream = stream;
            this.zipArchive = zipArchive;
        }

        public boolean isZipArchive() {
            return zipArchive;
        }

        /** Returns the same stream with every sniffed byte restored. */
        public PushbackInputStream getStream() {
            return stream;
        }

        @Override
        public void close() throws IOException {
            stream.close();
        }
    }
}
