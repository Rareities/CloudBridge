package ca.pkay.rcloneexplorer.util;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class ConfigMutationCommandPolicyTest {
    @Test
    public void recognizesCreateUpdateDeleteAndInteractiveConfigWriters() {
        assertTrue(ConfigMutationCommandPolicy.isMutationCommand(new String[] {
                "rclone", "--config", "C:/private/rclone.conf", "config", "create", "drive", "drive"
        }));
        assertTrue(ConfigMutationCommandPolicy.isMutationCommand(new String[] {
                "rclone", "-vvv", "--config=C:/private/rclone.conf", "config", "update", "drive"
        }));
        assertTrue(ConfigMutationCommandPolicy.isMutationCommand(new String[] {
                "rclone", "--config", "C:/private/rclone.conf", "config", "delete", "drive"
        }));
        assertTrue(ConfigMutationCommandPolicy.isMutationCommand(new String[] {
                "rclone", "--config", "C:/private/rclone.conf", "config"
        }));
    }

    @Test
    public void doesNotTreatReadOnlyConfigCommandsOrOptionValuesAsMutations() {
        assertFalse(ConfigMutationCommandPolicy.isMutationCommand(new String[] {
                "rclone", "--config", "C:/private/rclone.conf", "config", "dump"
        }));
        assertFalse(ConfigMutationCommandPolicy.isMutationCommand(new String[] {
                "rclone", "--config", "C:/private/rclone.conf", "config", "providers"
        }));
        assertFalse(ConfigMutationCommandPolicy.isMutationCommand(new String[] {
                "rclone", "--config", "C:/private/rclone.conf", "config", "show"
        }));
        assertFalse(ConfigMutationCommandPolicy.isMutationCommand(new String[] {
                "rclone", "--config", "config", "config", "dump"
        }));
        assertFalse(ConfigMutationCommandPolicy.isMutationCommand(new String[] {
                "rclone", "obscure", "config"
        }));
    }
}
