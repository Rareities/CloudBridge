package ca.pkay.rcloneexplorer.util;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class StructuredDiagnosticPolicyTest {

    @Test
    public void sanitizesSecretsControlsAndPrivateLocationsAtIngestion() {
        StructuredDiagnosticPolicy.SanitizedError safe = StructuredDiagnosticPolicy.sanitize(
                "C:\\Users\\Alice\\private.txt content://provider/secret",
                "Upload failed password=secret-canary\r\nnext line");

        assertFalse(safe.getObjectName().contains("C:\\Users\\Alice"));
        assertFalse(safe.getObjectName().contains("content://provider/secret"));
        assertFalse(safe.getMessage().contains("secret-canary"));
        assertFalse(safe.getMessage().contains("\n"));
        assertTrue(safe.getMessage().contains("Upload failed"));
    }

    @Test
    public void malformedAndMissingFieldsBecomeSafeBoundedValues() {
        StructuredDiagnosticPolicy.SanitizedError safe =
                StructuredDiagnosticPolicy.sanitize(null, "\u0000\t\r\n");

        assertEquals("", safe.getObjectName());
        assertEquals("Remote operation failed", safe.getMessage());
        assertTrue(safe.getObjectName().length() <= StructuredDiagnosticPolicy.MAX_FIELD_CHARS);
        assertTrue(safe.getMessage().length() <= StructuredDiagnosticPolicy.MAX_FIELD_CHARS);
    }

    @Test
    public void collectorCapsRecordsAndExposesOmittedCount() {
        StructuredDiagnosticPolicy.Collector collector =
                new StructuredDiagnosticPolicy.Collector();

        for (int i = 0; i < StructuredDiagnosticPolicy.MAX_RECORDS + 5; i++) {
            assertEquals(i < StructuredDiagnosticPolicy.MAX_RECORDS,
                    collector.add("object-" + i, "message-" + i));
        }

        assertEquals(StructuredDiagnosticPolicy.MAX_RECORDS, collector.size());
        assertEquals(5, collector.getOmittedCount());
        assertEquals(StructuredDiagnosticPolicy.MAX_RECORDS, collector.getRecords().size());
    }

    @Test
    public void collectorCapsAggregateOutputAndNeverLeaksOversizedSecrets() {
        StructuredDiagnosticPolicy.Collector collector =
                new StructuredDiagnosticPolicy.Collector();
        String oversized = repeat('x', StructuredDiagnosticPolicy.MAX_FIELD_CHARS * 2)
                + " password=aggregate-secret-canary";

        for (int i = 0; i < StructuredDiagnosticPolicy.MAX_RECORDS; i++) {
            collector.add(oversized, oversized);
        }

        String formatted = collector.format("Offending file: ");
        assertTrue(formatted.length() <= StructuredDiagnosticPolicy.MAX_AGGREGATE_CHARS);
        assertTrue(collector.getOmittedCount() > 0);
        assertFalse(formatted.contains("aggregate-secret-canary"));
        assertTrue(formatted.contains("additional diagnostic error(s) omitted"));
    }

    @Test
    public void formattingUsesStringBuilderAndKeepsOrdinaryDiagnosticsReadable() {
        StructuredDiagnosticPolicy.Collector collector =
                new StructuredDiagnosticPolicy.Collector();
        collector.add("remote/file.txt", "rclone exited with status 7");

        String formatted = collector.format("Offending file: ");

        assertTrue(formatted.contains("rclone exited with status 7"));
        assertEquals("rclone exited with status 7\nOffending file: remote/file.txt\n", formatted);
        assertTrue(formatted.endsWith("\n"));
    }

    private static String repeat(char value, int count) {
        StringBuilder result = new StringBuilder(count);
        for (int i = 0; i < count; i++) {
            result.append(value);
        }
        return result.toString();
    }
}
