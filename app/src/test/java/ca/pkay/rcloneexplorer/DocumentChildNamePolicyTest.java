package ca.pkay.rcloneexplorer;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class DocumentChildNamePolicyTest {

    @Test
    public void createFlattensSeparatorsAndPreservesLiteralNames() {
        assertEquals("report_final.md", DocumentChildNamePolicy.normalizeForCreate("report/final.md"));
        assertEquals("\u96EA\uD83C\uDF19.md",
                DocumentChildNamePolicy.requireSingleComponent("\u96EA\uD83C\uDF19.md"));
        assertEquals("parent\\child", DocumentChildNamePolicy.requireSingleComponent("parent\\child"));
    }

    @Test
    public void rejectsEmptyDotSeparatorAndNulComponents() {
        String[] invalidNames = {null, "", ".", "..", "a/b", "nul\0byte"};
        for (String name : invalidNames) {
            try {
                DocumentChildNamePolicy.requireSingleComponent(name);
                fail("Expected invalid document name to be rejected");
            } catch (IllegalArgumentException expected) {
                // Expected: names must be non-empty, non-special path components.
            }
        }
    }

    @Test
    public void targetKeepsRemoteAndParentComponentsStable() {
        String parent = "remotes/remote:/folder";
        String child = DocumentChildNamePolicy.normalizeForCreate("../outside");
        String target = DocumentChildNamePolicy.targetDocumentId(parent, child);

        assertEquals("remotes/remote:/folder/.._outside", target);
        assertTrue(target.startsWith(parent + "/"));
    }

    @Test(expected = IllegalArgumentException.class)
    public void targetRejectsUnnormalizedSeparator() {
        DocumentChildNamePolicy.targetDocumentId("remote:/folder", "../outside");
    }

    @Test
    public void targetSupportsParentIdsWithTrailingSeparators() {
        assertEquals("remotes/remote:/child",
                DocumentChildNamePolicy.targetDocumentId("remotes/remote:/", "child"));
    }
}
