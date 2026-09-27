package ca.pkay.rcloneexplorer.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BisyncBackupClaimParserTest {
    @Test
    fun parsesBothBackupRootsInSeparateAndEqualsForms() {
        val command = arrayOf(
            "bisync", "/sync/local", "drive:sync", "--backup-dir1", "/history/local/run-1",
            "--workdir", "/private/work", "--backup-dir2=drive:history/run-1"
        )

        assertEquals(
            listOf("/history/local/run-1", "drive:history/run-1"),
            EndpointConflictCoordinator.parseBisyncBackupDirTargets(command, 0)
        )
    }

    @Test
    fun skipsOptionValuesThatResembleBackupFlags() {
        val command = arrayOf(
            "bisync", "/sync/local", "drive:sync", "--filter",
            "--backup-dir1=not-a-real-option-value", "--backup-dir2", "drive:history/run-2"
        )

        assertEquals(
            listOf("drive:history/run-2"),
            EndpointConflictCoordinator.parseBisyncBackupDirTargets(command, 0)
        )
    }

    @Test
    fun absentBackupRootsAreAnEmptyListAndTerminatorEndsOptionParsing() {
        assertEquals(
            emptyList<String>(),
            EndpointConflictCoordinator.parseBisyncBackupDirTargets(
                arrayOf("bisync", "/sync/local", "drive:sync", "--", "--backup-dir1=/literal"), 0
            )
        )
    }

    @Test
    fun malformedOrAmbiguousBackupOptionsFailClosed() {
        val missingValue = arrayOf("bisync", "/sync/local", "drive:sync", "--backup-dir1")
        val emptyValue = arrayOf("bisync", "/sync/local", "drive:sync", "--backup-dir2=")
        val duplicateValue = arrayOf(
            "bisync", "/sync/local", "drive:sync", "--backup-dir1", "/history/one",
            "--backup-dir1=/history/two"
        )
        val invalidValue = arrayOf("bisync", "/sync/local", "drive:sync", "--backup-dir1", "--resync")
        val unknownOptionValue = arrayOf(
            "bisync", "/sync/local", "drive:sync", "--future-option", "--backup-dir2", "/history/two"
        )

        assertNull(EndpointConflictCoordinator.parseBisyncBackupDirTargets(missingValue, 0))
        assertNull(EndpointConflictCoordinator.parseBisyncBackupDirTargets(emptyValue, 0))
        assertNull(EndpointConflictCoordinator.parseBisyncBackupDirTargets(duplicateValue, 0))
        assertNull(EndpointConflictCoordinator.parseBisyncBackupDirTargets(invalidValue, 0))
        assertNull(EndpointConflictCoordinator.parseBisyncBackupDirTargets(unknownOptionValue, 0))
    }

    @Test
    fun knownValueOptionsBeforeEndpointsDoNotShiftTheClaimedTargets() {
        val command = arrayOf(
            "bisync", "--filter-from", "/storage/emulated/0/filters.txt",
            "/storage/emulated/0/sync", "drive:sync"
        )

        assertEquals(
            listOf("/storage/emulated/0/sync", "drive:sync"),
            EndpointConflictCoordinator.parsePositionalTargets(command, 1, 2)
        )
    }

    @Test
    fun unknownValueOptionBeforeEndpointsMakesClassificationAmbiguous() {
        val command = arrayOf(
            "bisync", "--future-option", "/storage/emulated/0/filters.txt",
            "/storage/emulated/0/sync", "drive:sync"
        )

        assertNull(EndpointConflictCoordinator.parsePositionalTargets(command, 1, 2))
    }
}
