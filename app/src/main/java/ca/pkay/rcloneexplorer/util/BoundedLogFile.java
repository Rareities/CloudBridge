package ca.pkay.rcloneexplorer.util;

import java.io.*;
import java.nio.charset.StandardCharsets;

/** Shared synchronization, byte limits and private-file I/O for app-owned logs. */
public final class BoundedLogFile {
    private BoundedLogFile() {}
    public static final int MAX_BYTES = 1024 * 1024;
    public static synchronized void append(File file, String entry) throws IOException {
        File parent = file.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) throw new IOException("Cannot create log directory");
        byte[] bytes = (entry + "\n").getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_BYTES / 4) throw new IOException("Log entry is too large");
        if (file.length() + bytes.length > MAX_BYTES) {
            try (FileOutputStream reset = new FileOutputStream(file, false)) { /* bounded retention */ }
        }
        try (FileOutputStream output = new FileOutputStream(file, true)) { output.write(bytes); }
    }
    public static synchronized String read(File file) throws IOException {
        if (!file.exists()) return "";
        try (RandomAccessFile input = new RandomAccessFile(file, "r")) {
            long start = Math.max(0, input.length() - MAX_BYTES);
            input.seek(start);
            // A legacy oversized file may start in the middle of a JSON record.
            if (start > 0) {
                int next;
                while ((next = input.read()) != -1 && next != '\n') { }
            }
            byte[] bytes = new byte[(int) (input.length() - input.getFilePointer())];
            input.readFully(bytes);
            return new String(bytes, StandardCharsets.UTF_8);
        }
    }
    public static synchronized void delete(File file) { if (file.exists()) file.delete(); }
}
