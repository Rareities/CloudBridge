package ca.pkay.rcloneexplorer.util;

import org.junit.Test;

import java.io.IOException;
import java.io.StringReader;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

public class BoundedTextReaderTest {

    @Test
    public void readsTextAtExactLimitIncludingUnicode() throws Exception {
        assertEquals("A\uD83D\uDE00", BoundedTextReader.read(new StringReader("A\uD83D\uDE00"), 3));
    }

    @Test
    public void rejectsTextBeyondLimit() throws Exception {
        try {
            BoundedTextReader.read(new StringReader("12345"), 4);
            fail("Expected the reader to reject text over its configured limit");
        } catch (IOException expected) {
            assertEquals("Text exceeds configured size limit", expected.getMessage());
        }
    }

    @Test
    public void acceptsEmptyInputAtZeroLimit() throws Exception {
        assertEquals("", BoundedTextReader.read(new StringReader(""), 0));
    }

    @Test
    public void rejectsFirstCharacterAtZeroLimit() throws Exception {
        try {
            BoundedTextReader.read(new StringReader("x"), 0);
            fail("Expected the reader to reject text over its configured limit");
        } catch (IOException expected) {
            assertEquals("Text exceeds configured size limit", expected.getMessage());
        }
    }
}
