package ca.pkay.rcloneexplorer.util;

import java.io.IOException;
import java.io.Reader;

/** Reads at most a caller-specified number of UTF-16 characters without closing the reader. */
public final class BoundedTextReader {
    private static final int BUFFER_SIZE = 8192;

    private BoundedTextReader() {
    }

    public static String read(Reader reader, int maxChars) throws IOException {
        if (reader == null) throw new NullPointerException("reader");
        if (maxChars < 0) throw new IllegalArgumentException("maxChars must not be negative");

        StringBuilder text = new StringBuilder(Math.min(maxChars, BUFFER_SIZE));
        char[] buffer = new char[BUFFER_SIZE];
        int count;
        while ((count = reader.read(buffer)) != -1) {
            if (count > maxChars - text.length()) {
                throw new IOException("Text exceeds configured size limit");
            }
            text.append(buffer, 0, count);
        }
        return text.toString();
    }
}
