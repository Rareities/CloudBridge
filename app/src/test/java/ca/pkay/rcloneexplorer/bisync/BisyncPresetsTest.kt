package ca.pkay.rcloneexplorer.bisync

import ca.pkay.rcloneexplorer.Items.Task
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class BisyncPresetsTest {
    @Test fun presetsPreserveSafetyControlsAndReplaceTuningOverrides() {
        val original = BisyncOptions(backupLocalPath = "/backups", backupRemotePath = "/backups", requireAccess = true,
            conflictSuffix = "keep-both", customOptions = mapOf("checkers" to "16"))
        val snapshot = BisyncPresets.apply(BisyncPresets.Id.LOW_MEMORY, original)
        assertEquals(original.backupLocalPath, snapshot.backupLocalPath)
        assertEquals(original.backupRemotePath, snapshot.backupRemotePath)
        assertTrue(snapshot.requireAccess)
        assertEquals("keep-both", snapshot.conflictSuffix)
        assertFalse(snapshot.customOptions.containsKey("checkers"))
        val args = snapshot.arguments(8)
        assertEquals("1", args[args.indexOf("--transfers") + 1])
        assertEquals("0M", args[args.indexOf("--buffer-size") + 1])
    }
    @Test fun everyPresetHasSafeEffectiveConnectionBudget() {
        for (id in BisyncPresets.Id.entries) {
            val flags = BisyncPresets.apply(id, BisyncOptions()).arguments(8).chunked(2).associate { it[0] to it[1] }
            assertTrue(flags.getValue("--max-connections").toInt() >=
                flags.getValue("--transfers").toInt() + flags.getValue("--checkers").toInt() + 1)
        }
    }
    @Test(expected = IllegalArgumentException::class) fun overridesCannotCreateDeadlockingBudget() {
        BisyncOptions(customOptions = mapOf("checkers" to "8", "transfers" to "4", "max-connections" to "7")).arguments()
    }
    @Test(expected = IllegalArgumentException::class) fun cannotSilentlyDowngradeUnknownSchema() {
        BisyncPresets.apply(BisyncPresets.Id.BALANCED, BisyncOptions(version = 99))
    }
    @Test fun snapshotsRoundTripWithoutLookingUpPreset() {
        for (id in BisyncPresets.Id.entries) {
            val snapshot = BisyncPresets.apply(id, BisyncOptions())
            assertEquals(snapshot, Json.decodeFromString<BisyncOptions>(Json.encodeToString(snapshot)))
            snapshot.validate()
        }
    }
    @Test fun equivalentCustomRowOrdersProduceSameArgvAndApproval() {
        val a = BisyncOptions(customOptions = linkedMapOf("transfers" to "1", "buffer-size" to "0M"))
        val b = a.copy(customOptions = linkedMapOf("buffer-size" to "0M", "transfers" to "1"))
        assertEquals(a.arguments(), b.arguments())
        val task = Task(1).apply { localPath = "/tmp/photos"; remotePath = "/photos"; remoteId = "drive"; bisyncOptions = a }
        val fingerprint = BisyncPolicy.fingerprint(task)
        task.bisyncOptions = b
        assertEquals(fingerprint, BisyncPolicy.fingerprint(task))
    }
}
