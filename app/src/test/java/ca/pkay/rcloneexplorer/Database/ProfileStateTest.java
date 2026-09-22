package ca.pkay.rcloneexplorer.Database;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;

import ca.pkay.rcloneexplorer.Items.SyncDirectionObject;
import ca.pkay.rcloneexplorer.Items.Task;

import org.junit.Test;

public class ProfileStateTest {

    @Test
    public void legacyDirectionsAreMappedWithoutCoercion() {
        assertEquals(ProfileMode.SYNC_ONE_WAY,
                ProfileMode.Companion.fromLegacyDirection(SyncDirectionObject.SYNC_LOCAL_TO_REMOTE));
        assertEquals(ProfileMode.COPY_ONE_WAY,
                ProfileMode.Companion.fromLegacyDirection(SyncDirectionObject.COPY_REMOTE_TO_LOCAL));
        assertEquals(ProfileMode.CLOUD_TO_CLOUD,
                ProfileMode.Companion.fromLegacyDirection(SyncDirectionObject.SYNC_REMOTE_TO_REMOTE));
        assertEquals(ProfileMode.BISYNC,
                ProfileMode.Companion.fromLegacyDirection(SyncDirectionObject.SYNC_BIDIRECTIONAL));
        assertEquals(ProfileMode.UNKNOWN, ProfileMode.Companion.fromLegacyDirection(99));
    }

    @Test
    public void bisyncAndUnknownModesRequireRepair() {
        Task bisync = new Task(7L);
        bisync.setDirection(SyncDirectionObject.SYNC_BIDIRECTIONAL);
        ProfileSpec bisyncSpec = LegacyProfileMapper.INSTANCE.fromTask(bisync, "rclone:1.76.0");
        assertEquals(ProfileReadiness.RECOVERY_REQUIRED, bisyncSpec.getReadiness());
        assertNotNull(bisyncSpec.getReason());

        Task unknown = new Task(8L);
        unknown.setDirection(99);
        ProfileSpec unknownSpec = LegacyProfileMapper.INSTANCE.fromTask(unknown, "rclone:1.76.0");
        assertEquals(ProfileReadiness.RECOVERY_REQUIRED, unknownSpec.getReadiness());
    }

    @Test
    public void fingerprintChangesWhenSemanticSettingsChange() {
        Task task = new Task(10L);
        task.setRemoteId("account");
        task.setRemotePath("notes");
        ProfileSpec first = LegacyProfileMapper.INSTANCE.fromTask(task, "rclone:1.76.0");
        task.setRemotePath("other");
        ProfileSpec second = LegacyProfileMapper.INSTANCE.fromTask(task, "rclone:1.76.0");
        assertNotEquals(first.getFingerprint(), second.getFingerprint());
    }

    @Test
    public void legacyProfileUuidMappingIsStable() {
        assertEquals(
                LegacyProfileMapper.INSTANCE.stableIdForLegacyTask(41L),
                LegacyProfileMapper.INSTANCE.stableIdForLegacyTask(41L)
        );
        assertNotEquals(
                LegacyProfileMapper.INSTANCE.stableIdForLegacyTask(41L),
                LegacyProfileMapper.INSTANCE.stableIdForLegacyTask(42L)
        );
    }
}
