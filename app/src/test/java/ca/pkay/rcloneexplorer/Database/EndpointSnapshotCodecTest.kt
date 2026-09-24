package ca.pkay.rcloneexplorer.Database

import ca.pkay.rcloneexplorer.Items.RemoteItem
import ca.pkay.rcloneexplorer.Items.SyncDirectionObject
import ca.pkay.rcloneexplorer.Items.Task
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EndpointSnapshotCodecTest {
    @Test
    fun decodesUnicodeAndDelimiterBearingFieldsByUtf8Length() {
        val task = Task(1L).apply {
            remoteType = RemoteItem.GOOGLE_DRIVE
            remoteId = "drive|acct:🛰️"
            remotePath = "notes|drafts:α/終"
            localPath = "/storage/emulated/0/Notes|Archive:α"
            direction = SyncDirectionObject.SYNC_BIDIRECTIONAL
        }
        val spec = LegacyProfileMapper.fromTask(task, "rclone:test")

        val result = EndpointSnapshotCodec.decode(spec.endpointIdentity, spec.settings)

        assertTrue(result.isSupported)
        val snapshot = requireNotNull(result.snapshot)
        assertEquals(1, snapshot.schemaVersion)
        assertEquals(task.remoteType, snapshot.remote1.type)
        assertEquals(task.remoteId, snapshot.remote1.remoteName)
        assertEquals(task.remotePath, snapshot.remote1.path)
        assertEquals(task.localPath, snapshot.localPath)
        assertFalse(snapshot.toString().contains(task.remoteId))
        assertFalse(snapshot.toString().contains(task.localPath))
        assertFalse(snapshot.remote1.toString().contains(task.remotePath))
    }

    @Test
    fun truncatedEndpointAndUnknownSchemaFailClosed() {
        val task = Task(2L).apply {
            remoteType = RemoteItem.GOOGLE_DRIVE
            remoteId = "drive"
            direction = SyncDirectionObject.SYNC_LOCAL_TO_REMOTE
        }
        val spec = LegacyProfileMapper.fromTask(task, "rclone:test")

        val truncated = EndpointSnapshotCodec.decode(spec.endpointIdentity.dropLast(1), spec.settings)
        assertNull(truncated.snapshot)
        assertEquals(EndpointSnapshotDecodeFailure.MALFORMED_ENDPOINT_SNAPSHOT, truncated.failure)

        val futureSchema = EndpointSnapshotCodec.decode(
            spec.endpointIdentity,
            spec.settings.replaceFirst("6:schema1:1", "6:schema1:9")
        )
        assertNull(futureSchema.snapshot)
        assertEquals(EndpointSnapshotDecodeFailure.UNKNOWN_SCHEMA_VERSION, futureSchema.failure)
    }

    @Test
    fun classifierNeverApprovesBackupPlacementAndFailsClosedForUnsupportedShapes() {
        val base = EndpointSnapshot(
            schemaVersion = 1,
            remote1 = EndpointRemoteSnapshot(RemoteItem.GOOGLE_DRIVE, "drive", "root"),
            localPath = "/storage/emulated/0/Notes",
            remote2 = EndpointRemoteSnapshot(0, "", "")
        )

        val ordinary = EndpointSnapshotClassifier.classify(ProfileMode.BISYNC, base)
        assertFalse(ordinary.backupPlacementPermitted)
        assertEquals(EndpointCapability.LOCAL_PATH_REQUIRES_RUNTIME_PROOF, ordinary.endpoints[0].capability)
        assertEquals(EndpointCapability.REMOTE_CONFIG_REQUIRES_RUNTIME_PROOF, ordinary.endpoints[1].capability)

        val saf = EndpointSnapshotClassifier.classify(
            ProfileMode.BISYNC,
            base.copy(localPath = "content://provider/tree/123")
        )
        assertEquals(EndpointCapability.STORAGE_FRAMEWORK_UNSUPPORTED, saf.endpoints[0].capability)

        val wrapped = EndpointSnapshotClassifier.classify(
            ProfileMode.BISYNC,
            base.copy(remote1 = base.remote1.copy(type = RemoteItem.CRYPT))
        )
        assertEquals(EndpointCapability.WRAPPED_REMOTE_UNSUPPORTED, wrapped.endpoints[1].capability)

        val unknown = EndpointSnapshotClassifier.classify(
            ProfileMode.CLOUD_TO_CLOUD,
            base.copy(remote1 = base.remote1.copy(type = 999))
        )
        assertEquals(EndpointCapability.UNKNOWN_REMOTE_UNSUPPORTED, unknown.endpoints[0].capability)
        assertEquals(EndpointCapability.MISSING_ENDPOINT, unknown.endpoints[1].capability)

        val futureSnapshot = EndpointSnapshotClassifier.classify(
            ProfileMode.BISYNC,
            base.copy(schemaVersion = 99)
        )
        assertEquals(EndpointCapability.UNKNOWN_SNAPSHOT_VERSION, futureSnapshot.endpoints.single().capability)
    }

    @Test
    fun malformedSettingsAndUnknownModeDoNotProduceAuthorization() {
        val result = EndpointSnapshotCodec.decode("not-a-snapshot", "not-settings")
        assertFalse(result.isSupported)
        assertEquals(EndpointSnapshotDecodeFailure.MALFORMED_SETTINGS, result.failure)

        val snapshot = EndpointSnapshot(1, EndpointRemoteSnapshot(999, "", ""), "", EndpointRemoteSnapshot(0, "", ""))
        val assessment = EndpointSnapshotClassifier.classify(ProfileMode.UNKNOWN, snapshot)
        assertFalse(assessment.backupPlacementPermitted)
        assertEquals(EndpointCapability.UNKNOWN_MODE, assessment.endpoints.single().capability)
    }
}
