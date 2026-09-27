package ca.pkay.rcloneexplorer.util

import java.io.File
import java.nio.file.Files
import org.junit.Assume.assumeNoException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class BisyncCommandModePolicyTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val root = File(System.getProperty("java.io.tmpdir"), "bisync-policy").absolutePath
    private val profileStateRoot = File(root, "files/bisync/profiles").absolutePath
    private val previewWorkRoot = File(root, "cache/bisync-preview").absolutePath
    private val workDirectory = File(previewWorkRoot, "preview").absolutePath
    private val stateDirectory = File(profileStateRoot, "profile").absolutePath

    private fun allowsReadOnlyInvocation(arguments: List<String>) =
        BisyncCommandModePolicy.allowsReadOnlyInvocation(arguments, profileStateRoot, previewWorkRoot)

    @Test
    fun allowsReadOnlyStateInspection() {
        assertTrue(allowsReadOnlyInvocation(listOf(
            "bisync", "/left", "remote:/right", "--inspect-state",
            "--workdir", stateDirectory,
            "--compare", "size,modtime"
        )))
    }

    @Test
    fun allowsAbsentStateDryRunPreviewWithResyncMode() {
        assertTrue(allowsReadOnlyInvocation(listOf(
            "bisync", "/left", "remote:/right", "--dry-run", "--preview-json",
            "--workdir", workDirectory,
            "--compare", "size,modtime", "--max-delete", "100", "--max-delete-count", "100",
            "--resync", "--resync-mode", "path1", "--filter", "+ *.md", "--delete-excluded"
        )))
    }

    @Test
    fun allowsCompatibleStateDryRunPreviewWithCopiedState() {
        assertTrue(allowsReadOnlyInvocation(listOf(
            "bisync", "/left", "remote:/right", "--dry-run", "--preview-json",
            "--workdir", workDirectory,
            "--compare", "size", "--max-delete", "80", "--max-delete-count", "25",
            "--preview-state-from", stateDirectory
        )))
    }

    @Test
    fun leavesNonBisyncCommandsUnchanged() {
        assertTrue(allowsReadOnlyInvocation(listOf("sync", "/left", "remote:/right")))
    }

    @Test
    fun rejectsMutationWithoutPreviewBoundary() {
        assertFalse(allowsReadOnlyInvocation(listOf("bisync", "/left", "remote:/right")))
        assertFalse(allowsReadOnlyInvocation(listOf(
            "bisync", "/left", "remote:/right", "--resync", "--resync-mode", "path1"
        )))
    }

    @Test
    fun rejectsIncompleteOrOverriddenDryRunAndUnknownOptions() {
        assertFalse(allowsReadOnlyInvocation(listOf(
            "bisync", "/left", "remote:/right", "--preview-json"
        )))
        assertFalse(allowsReadOnlyInvocation(listOf(
            "bisync", "/left", "remote:/right", "--dry-run=false", "--preview-json"
        )))
        assertFalse(allowsReadOnlyInvocation(listOf(
            "bisync", "/left", "remote:/right", "--dry-run", "--preview-json", "--recover"
        )))
    }

    @Test
    fun rejectsMixedInspectionAndPreviewModes() {
        assertFalse(allowsReadOnlyInvocation(listOf(
            "bisync", "/left", "remote:/right", "--inspect-state", "--dry-run", "--preview-json"
        )))
    }

    @Test
    fun requiresExactlyOnePreviewStateSource() {
        val base = listOf(
            "bisync", "/left", "remote:/right", "--dry-run", "--preview-json",
            "--workdir", workDirectory, "--compare", "size",
            "--max-delete", "100", "--max-delete-count", "100"
        )
        assertFalse(allowsReadOnlyInvocation(base))
        assertFalse(allowsReadOnlyInvocation(base + listOf(
            "--resync", "--resync-mode", "path1", "--preview-state-from", stateDirectory
        )))
    }

    @Test
    fun rejectsWorkAndStatePathsOutsideAppOwnedBisyncTrees() {
        val outside = File(root, "outside").absolutePath
        val sibling = "$previewWorkRoot-evil/preview"
        val traversal = File(previewWorkRoot, "../bisync-preview-evil/preview").absolutePath
        assertFalse(allowsReadOnlyInvocation(listOf(
            "bisync", "/left", "remote:/right", "--inspect-state",
            "--workdir", outside, "--compare", "size"
        )))
        assertFalse(allowsReadOnlyInvocation(listOf(
            "bisync", "/left", "remote:/right", "--dry-run", "--preview-json",
            "--workdir", workDirectory, "--compare", "size", "--max-delete", "100",
            "--max-delete-count", "100", "--preview-state-from", outside
        )))
        assertFalse(allowsReadOnlyInvocation(listOf(
            "bisync", "/left", "remote:/right", "--dry-run", "--preview-json",
            "--workdir", sibling, "--compare", "size", "--max-delete", "100",
            "--max-delete-count", "100", "--resync", "--resync-mode", "path1"
        )))
        assertFalse(allowsReadOnlyInvocation(listOf(
            "bisync", "/left", "remote:/right", "--dry-run", "--preview-json",
            "--workdir", traversal, "--compare", "size", "--max-delete", "100",
            "--max-delete-count", "100", "--resync", "--resync-mode", "path1"
        )))
    }

    @Test
    fun rejectsPreviewWorkDirectoryReachedThroughSymlinkEscape() {
        val testRoot = temporaryFolder.newFolder("bisync-policy-root")
        val allowedWorkRoot = File(testRoot, "cache/bisync-preview")
        val outsideRoot = temporaryFolder.newFolder("outside")
        Files.createDirectories(allowedWorkRoot.toPath())
        val escape = File(allowedWorkRoot, "redirect")
        try {
            Files.createSymbolicLink(escape.toPath(), outsideRoot.toPath())
        } catch (failure: Exception) {
            assumeNoException("Symbolic links are unavailable in this test environment", failure)
            return
        }

        val command = listOf(
            "bisync", "/left", "remote:/right", "--dry-run", "--preview-json",
            "--workdir", File(escape, "preview-run").absolutePath,
            "--compare", "size", "--max-delete", "100", "--max-delete-count", "100",
            "--resync", "--resync-mode", "path1"
        )
        assertFalse(BisyncCommandModePolicy.allowsReadOnlyInvocation(
            command,
            File(testRoot, "files/bisync/profiles").absolutePath,
            allowedWorkRoot.absolutePath
        ))
    }

    @Test
    fun locatesBisyncVerbAfterGlobalOptionsWhoseValuesLookLikeCommands() {
        val command = arrayOf(
            "/native/librclone.so", "--config", "sync",
            "--cache-chunk-path", "bisync", "--cache-db-path", "config", "-vvv",
            "bisync", "/left", "remote:/right", "--recover"
        )
        val commandIndex = EndpointConflictCoordinator.findCommandIndex(command)
        assertEquals(8, commandIndex)
        assertFalse(allowsReadOnlyInvocation(command.copyOfRange(commandIndex!!, command.size).asList()))
    }
}
