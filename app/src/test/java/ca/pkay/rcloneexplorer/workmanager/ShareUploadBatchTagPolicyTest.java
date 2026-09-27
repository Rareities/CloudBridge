package ca.pkay.rcloneexplorer.workmanager;

import org.junit.Test;

import java.io.File;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class ShareUploadBatchTagPolicyTest {
    private static final String DIRECTORY_NAME = "share-123e4567-e89b-12d3-a456-426614174000";

    @Test
    public void createsTagScopedToTheStagingInvocation() {
        assertEquals(ShareUploadBatchTagPolicy.PREFIX + DIRECTORY_NAME,
                ShareUploadBatchTagPolicy.forStagingDirectory(new File(DIRECTORY_NAME)));
    }

    @Test
    public void acceptsOnlyCanonicalInvocationTags() {
        assertTrue(ShareUploadBatchTagPolicy.isValidTag(
                ShareUploadBatchTagPolicy.PREFIX + DIRECTORY_NAME));
        assertFalse(ShareUploadBatchTagPolicy.isValidTag(null));
        assertFalse(ShareUploadBatchTagPolicy.isValidTag("share_upload_work"));
        assertFalse(ShareUploadBatchTagPolicy.isValidTag(
                ShareUploadBatchTagPolicy.PREFIX + "share-not-a-uuid"));
        assertFalse(ShareUploadBatchTagPolicy.isValidTag(
                ShareUploadBatchTagPolicy.PREFIX + "share-123E4567-E89B-12D3-A456-426614174000"));
    }

    @Test
    public void requiresTagToBelongToTheStagingDirectory() {
        File stagingDirectory = new File(DIRECTORY_NAME);
        assertTrue(ShareUploadBatchTagPolicy.isTagForStagingDirectory(
                ShareUploadBatchTagPolicy.PREFIX + DIRECTORY_NAME, stagingDirectory));
        assertFalse(ShareUploadBatchTagPolicy.isTagForStagingDirectory(
                ShareUploadBatchTagPolicy.PREFIX + "share-123e4567-e89b-12d3-a456-426614174001",
                stagingDirectory));
    }

    @Test
    public void refusesUnownedStagingDirectoryNames() {
        try {
            ShareUploadBatchTagPolicy.forStagingDirectory(new File("share-user-controlled"));
            fail("Expected an unowned directory name to be rejected");
        } catch (IllegalArgumentException expected) {
            // Expected.
        }
    }
}
