package ca.pkay.rcloneexplorer;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class DirectoryProbeResultTest {

    @Test
    public void networkCategoryRequiresFailureExit() {
        assertTrue(new Rclone.DirectoryProbeResult(-1, "network is unreachable").isNetworkError());
        assertFalse(new Rclone.DirectoryProbeResult(0, "network is unreachable").isNetworkError());
        assertFalse(new Rclone.DirectoryProbeResult(7, "rclone probe failed").isNetworkError());
    }
}
