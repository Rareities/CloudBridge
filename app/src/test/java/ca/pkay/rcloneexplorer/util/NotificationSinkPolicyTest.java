package ca.pkay.rcloneexplorer.util;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class NotificationSinkPolicyTest {

    @Test
    public void titleAndContentAreRedactedSingleLineAndBounded() {
        String title = NotificationSinkPolicy.sanitizeTitle(
                "C:\\Users\\Alice\\private.txt password=title-secret\r\n" + repeat('t', 300));
        String content = NotificationSinkPolicy.sanitizeContent(
                "failed content://provider/private password=content-secret\nnext line");

        assertTrue(title.length() <= NotificationSinkPolicy.MAX_TITLE_CHARS);
        assertTrue(content.length() <= NotificationSinkPolicy.MAX_CONTENT_CHARS);
        assertFalse(title.contains("C:\\Users\\Alice"));
        assertFalse(title.contains("title-secret"));
        assertFalse(content.contains("content://provider/private"));
        assertFalse(content.contains("content-secret"));
        assertFalse(content.contains("\n"));
        assertTrue(content.contains("failed"));
    }

    @Test
    public void oversizedRawInputHasBoundedRedactionWorkAndVisibleTruncation() {
        String safe = NotificationSinkPolicy.sanitizeContent(
                "password=secret-canary " + repeat('x', 100_000));

        assertTrue(safe.length() <= NotificationSinkPolicy.MAX_CONTENT_CHARS);
        assertFalse(safe.contains("secret-canary"));
        assertTrue(safe.endsWith("…"));
    }

    @Test
    public void detailListsAreCopiedAndCappedByCountAndAggregateSize() {
        ArrayList<String> input = new ArrayList<>();
        for (int i = 0; i < NotificationSinkPolicy.MAX_DETAILS_LINES + 4; i++) {
            input.add("line-" + i);
        }

        ArrayList<String> safe = NotificationSinkPolicy.sanitizeDetails(input);
        input.clear();

        assertEquals(NotificationSinkPolicy.MAX_DETAILS_LINES, safe.size());
        assertEquals("line-0", safe.get(0));
    }

    @Test
    public void reportPrependsNewestFirstAndSanitizesExistingRows() {
        String first = NotificationSinkPolicy.prependReport("", "first", "old password=old-secret");
        String second = NotificationSinkPolicy.prependReport(first, "second", "new line\ncontent");

        assertTrue(second.startsWith("second: new line content\n"));
        assertTrue(second.indexOf("first:") > second.indexOf("second:"));
        assertFalse(second.contains("old-secret"));
        assertEquals(2, NotificationSinkPolicy.reportEntryCount(second));
    }

    @Test
    public void reportHistoryRespectsEntryAndUtf8ByteCaps() {
        String history = "";
        for (int i = 0; i < NotificationSinkPolicy.MAX_REPORT_ENTRIES + 20; i++) {
            history = NotificationSinkPolicy.prependReport(history, "task-" + i, repeatString("✓", 1000));
        }

        assertTrue(NotificationSinkPolicy.reportEntryCount(history)
                <= NotificationSinkPolicy.MAX_REPORT_ENTRIES);
        assertTrue(NotificationSinkPolicy.utf8Bytes(history)
                <= NotificationSinkPolicy.MAX_REPORT_BYTES);
        assertTrue(history.startsWith("task-" + (NotificationSinkPolicy.MAX_REPORT_ENTRIES + 19) + ":"));
    }

    @Test
    public void reportAggregationLineCountRetainsEmptyAndTrailingLineSemantics() {
        assertEquals(1, NotificationSinkPolicy.aggregationLineCount(null));
        assertEquals(1, NotificationSinkPolicy.aggregationLineCount(""));
        assertEquals(2, NotificationSinkPolicy.aggregationLineCount("entry\n"));
        assertEquals(2, NotificationSinkPolicy.aggregationLineCount("one\ntwo"));
    }

    private static String repeat(char value, int count) {
        char[] chars = new char[count];
        Arrays.fill(chars, value);
        return new String(chars);
    }

    private static String repeatString(String value, int count) {
        StringBuilder result = new StringBuilder(value.length() * count);
        for (int i = 0; i < count; i++) {
            result.append(value);
        }
        return result.toString();
    }
}
