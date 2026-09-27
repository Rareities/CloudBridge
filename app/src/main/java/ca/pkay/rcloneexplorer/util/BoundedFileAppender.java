package ca.pkay.rcloneexplorer.util;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

/** Serializes in-process appends and resets a diagnostic file before it can exceed its byte cap. */
public final class BoundedFileAppender {

    private static final Object LOCK = new Object();

    private BoundedFileAppender() {}

    /**
     * Appends one complete record. When it would exceed the limit, the prior diagnostic file is
     * deleted first, matching the existing log-rotation policy while enforcing the cap at write
     * time instead of relying on an earlier asynchronous size check.
     *
     * @return true if the record was appended; false if it is larger than the limit or the old
     *         file could not be removed.
     */
    public static boolean append(File file, byte[] record, long maxBytes) throws IOException {
        if (file == null) throw new NullPointerException("file");
        if (record == null) throw new NullPointerException("record");
        if (maxBytes < 0) throw new IllegalArgumentException("maxBytes must not be negative");

        synchronized (LOCK) {
            long currentBytes = file.isFile() ? file.length() : 0;
            if (currentBytes > maxBytes) {
                if (file.exists() && !file.delete()) return false;
                currentBytes = 0;
            }
            if (record.length > maxBytes) return false;
            if (record.length > maxBytes - currentBytes
                    && file.exists() && !file.delete()) return false;

            try (FileOutputStream output = new FileOutputStream(file, true)) {
                output.write(record);
                output.flush();
            }
            return file.length() <= maxBytes;
        }
    }
}
