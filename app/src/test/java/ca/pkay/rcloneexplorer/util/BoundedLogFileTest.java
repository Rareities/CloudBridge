package ca.pkay.rcloneexplorer.util;

import java.io.File;
import java.io.RandomAccessFile;
import org.junit.Test;
import static org.junit.Assert.*;

public class BoundedLogFileTest {
    @Test public void writesAndReadsStayBounded() throws Exception {
        File log = File.createTempFile("bounded-log", ".txt");
        try {
            StringBuilder entry = new StringBuilder();
            for (int i = 0; i < 16000; i++) entry.append('x');
            for (int i = 0; i < 100; i++) BoundedLogFile.append(log, entry.toString());
            assertTrue(log.length() <= BoundedLogFile.MAX_BYTES);
            BoundedLogFile.append(log, "latest");
            assertTrue(BoundedLogFile.read(log).endsWith("latest\n"));
        } finally { log.delete(); }
    }
    @Test public void oversizedLegacyLogReadsOnlyCompleteTailRecords() throws Exception {
        File log = File.createTempFile("legacy-log", ".txt");
        try {
            try (RandomAccessFile out = new RandomAccessFile(log, "rw")) {
                out.setLength(BoundedLogFile.MAX_BYTES * 2L);
                out.seek(out.length());
                out.writeBytes("\nlatest\n");
            }
            assertEquals("latest\n", BoundedLogFile.read(log));
        } finally { log.delete(); }
    }
}
