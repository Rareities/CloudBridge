package ca.pkay.rcloneexplorer.Database;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
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
    public void filterContentAndImmutableEngineRefAreSemanticFingerprintInputs() {
        Task task = new Task(11L);
        task.setFilterId(3L);
        String engine = "rclone:1.76.0@d53551e1722305268c6072263f11066f1278a4a0";
        ProfileSpec first = LegacyProfileMapper.INSTANCE.fromTask(task, engine, "+ /private/**\n", false);
        ProfileSpec changedFilter = LegacyProfileMapper.INSTANCE.fromTask(task, engine, "+ /shared/**\n", false);
        ProfileSpec changedEngine = LegacyProfileMapper.INSTANCE.fromTask(
                task,
                "rclone:1.76.0@aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                "+ /private/**\n",
                false
        );

        assertNotEquals(first.getFingerprint(), changedFilter.getFingerprint());
        assertNotEquals(first.getFingerprint(), changedEngine.getFingerprint());
        assertFalse(first.getSettings().contains("/private/"));
    }

    @Test
    public void missingSelectedFilterIsDurablyBlocked() {
        Task task = new Task(12L);
        task.setFilterId(8L);

        ProfileSpec spec = LegacyProfileMapper.INSTANCE.fromTask(task, "rclone:1.76.0@d53551e1722305268c6072263f11066f1278a4a0", null, true);

        assertEquals(ProfileReadiness.BLOCKED, spec.getReadiness());
        assertNotNull(spec.getReason());
    }

    @Test
    public void titleOnlyEditDoesNotChangeSemanticFingerprint() {
        Task task = new Task(13L);
        ProfileSpec first = LegacyProfileMapper.INSTANCE.fromTask(task, "rclone:1.76.0@d53551e1722305268c6072263f11066f1278a4a0");
        task.setTitle("Renamed profile");
        ProfileSpec renamed = LegacyProfileMapper.INSTANCE.fromTask(task, "rclone:1.76.0@d53551e1722305268c6072263f11066f1278a4a0");

        assertEquals(first.getFingerprint(), renamed.getFingerprint());
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
