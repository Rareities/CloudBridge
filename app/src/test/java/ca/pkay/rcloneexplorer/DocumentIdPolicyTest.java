package ca.pkay.rcloneexplorer;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class DocumentIdPolicyTest {

    private static final String ROOT_ID = "rclone/remotes";
    private static final String ROOT_PREFIX = ROOT_ID + "/";

    @Test
    public void canonicalizesRootAndRemoteDocuments() {
        assertEquals(ROOT_ID, DocumentIdPolicy.requireDocument(ROOT_ID, ROOT_ID, ROOT_PREFIX));
        assertEquals("vault:", DocumentIdPolicy.requireDocument(
                ROOT_PREFIX + "vault:/", ROOT_ID, ROOT_PREFIX));
        assertEquals("vault:/notes/today.md", DocumentIdPolicy.requireDocument(
                ROOT_PREFIX + "vault:notes/today.md", ROOT_ID, ROOT_PREFIX));
    }

    @Test
    public void preservesLiteralColonAndBackslashInsidePathComponents() {
        assertEquals("vault:/notes/part:2\\draft.md", DocumentIdPolicy.requireDocument(
                ROOT_PREFIX + "vault:/notes/part:2\\draft.md", ROOT_ID, ROOT_PREFIX));
    }

    @Test
    public void rejectsMalformedAndTraversalDocuments() {
        String[] invalid = {
                ROOT_PREFIX,
                "rclone/remotesX/vault:/notes",
                ROOT_PREFIX + "rclone/remotes/vault:/notes",
                ROOT_PREFIX + "vault:/notes/../outside",
                ROOT_PREFIX + "vault:/notes/./today.md",
                ROOT_PREFIX + "vault:/notes//today.md",
                ROOT_PREFIX + ":/notes",
                ROOT_PREFIX + "vault:/" + "x".repeat(4097)
        };
        for (String documentId : invalid) {
            try {
                DocumentIdPolicy.requireDocument(documentId, ROOT_ID, ROOT_PREFIX);
                fail("Expected malformed ID to be rejected");
            } catch (IllegalArgumentException expected) {
                // Expected fail-closed behavior.
            }
        }
    }

    @Test
    public void descendantCheckUsesSegmentBoundariesAndSameRemote() {
        assertTrue(DocumentIdPolicy.isChildOf(ROOT_ID, ROOT_PREFIX + "vault:", ROOT_ID, ROOT_PREFIX));
        assertTrue(DocumentIdPolicy.isChildOf(ROOT_PREFIX + "vault:",
                ROOT_PREFIX + "vault:/notes/today.md", ROOT_ID, ROOT_PREFIX));
        assertTrue(DocumentIdPolicy.isChildOf(ROOT_PREFIX + "vault:/notes",
                ROOT_PREFIX + "vault:/notes/today.md", ROOT_ID, ROOT_PREFIX));
        assertFalse(DocumentIdPolicy.isChildOf(ROOT_PREFIX + "vault:/notes",
                ROOT_PREFIX + "vault:/notes-old/today.md", ROOT_ID, ROOT_PREFIX));
        assertFalse(DocumentIdPolicy.isChildOf(ROOT_PREFIX + "vault:/notes",
                ROOT_PREFIX + "other:/notes/today.md", ROOT_ID, ROOT_PREFIX));
        assertFalse(DocumentIdPolicy.isChildOf(ROOT_PREFIX + "vault:/notes",
                ROOT_PREFIX + "vault:/notes", ROOT_ID, ROOT_PREFIX));
        assertFalse(DocumentIdPolicy.isChildOf(ROOT_PREFIX + "vault:/notes/..",
                ROOT_PREFIX + "vault:/notes/today.md", ROOT_ID, ROOT_PREFIX));
    }
}
