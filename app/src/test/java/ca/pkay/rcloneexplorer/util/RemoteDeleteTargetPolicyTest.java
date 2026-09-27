package ca.pkay.rcloneexplorer.util;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class RemoteDeleteTargetPolicyTest {
    @Test
    public void acceptsListedItemPathAndTrailingDirectorySeparator() {
        assertTrue(RemoteDeleteTargetPolicy.isSafeTarget("folder/report.md", "report.md"));
        assertTrue(RemoteDeleteTargetPolicy.isSafeTarget("/folder/nested/", "nested"));
    }

    @Test
    public void rejectsRootTraversalAndDotSegments() {
        assertFalse(RemoteDeleteTargetPolicy.isSafeTarget("/", "root"));
        assertFalse(RemoteDeleteTargetPolicy.isSafeTarget("///", "root"));
        assertFalse(RemoteDeleteTargetPolicy.isSafeTarget("folder/../report.md", "report.md"));
        assertFalse(RemoteDeleteTargetPolicy.isSafeTarget("./report.md", "report.md"));
        assertFalse(RemoteDeleteTargetPolicy.isSafeTarget("folder/..", ".."));
    }

    @Test
    public void rejectsMismatchedNameAndMalformedInput() {
        assertFalse(RemoteDeleteTargetPolicy.isSafeTarget("folder/report.md", "other.md"));
        assertFalse(RemoteDeleteTargetPolicy.isSafeTarget("", "report.md"));
        assertFalse(RemoteDeleteTargetPolicy.isSafeTarget("folder/report\u0000.md", "report\u0000.md"));
        assertFalse(RemoteDeleteTargetPolicy.isSafeTarget("folder/report.md", "folder/report.md"));
    }
}
