package ca.pkay.rcloneexplorer.Database

import ca.pkay.rcloneexplorer.Items.FilterEntry
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class BisyncPreviewCommandBuilderTest {
    @Test
    fun admissionCapabilitiesFailClosedForCompatibleStateOnThePublishedPin() {
        val publishedEngine = "rclone:1.76.0@${BisyncPreviewCommandBuilder.PUBLISHED_PREVIEW_ENGINE}"
        val cloneEngine = "rclone:1.76.0@${BisyncPreviewCommandBuilder.LOCAL_STATE_CLONE_ENGINE}"

        assertTrue(BisyncPreviewCommandBuilder.supportsPreview(publishedEngine, BisyncNativeState.ABSENT))
        assertFalse(BisyncPreviewCommandBuilder.supportsPreview(publishedEngine, BisyncNativeState.COMPATIBLE))
        assertTrue(BisyncPreviewCommandBuilder.supportsPreview(cloneEngine, BisyncNativeState.COMPATIBLE))
        assertFalse(BisyncPreviewCommandBuilder.supportsPreview("rclone:master", BisyncNativeState.ABSENT))
        assertFalse(BisyncPreviewCommandBuilder.supportsPreview(publishedEngine, BisyncNativeState.UNKNOWN))
    }

    @Test
    fun absentStateBindsExplicitPolicyAndEverySafetyOption() {
        val command = BisyncPreviewCommandBuilder.build(request(
            identity = identity(BisyncNativeState.ABSENT, BisyncPreviewResyncMode.NEWER),
            filters = listOf(
                FilterEntry(FilterEntry.FILTER_INCLUDE, "/keep/**"),
                FilterEntry(FilterEntry.FILTER_EXCLUDE, "/cache/**")
            ),
            deleteExcluded = true
        ))

        assertEquals(listOf(
            "bisync", "/private/left", "remote:/right",
            "--dry-run", "--preview-json", "--workdir", "${FileRoot.path}/preview",
            "--compare", "size", "--max-delete", "10", "--max-delete-count", "25",
            "--resync", "--resync-mode", "newer", "--delete-excluded",
            "--filter", "+ /keep/**", "--filter", "- /cache/**"
        ), command)
        assertFalse(command.contains("--force"))
        assertFalse(command.contains("--preview-state-from"))
    }

    @Test
    fun currentPublishedPinFailsClosedForCompatibleBaseline() {
        val failure = assertThrows(IllegalArgumentException::class.java) {
            BisyncPreviewCommandBuilder.build(request(
                identity = identity(BisyncNativeState.COMPATIBLE),
                acceptedStateDirectory = "${FileRoot.path}/accepted"
            ))
        }
        assertTrue(failure.message.orEmpty().contains("cannot clone"))
    }

    @Test
    fun cloneCapableExactCommitUsesSourceWithoutResync() {
        val command = BisyncPreviewCommandBuilder.build(request(
            identity = identity(
                BisyncNativeState.COMPATIBLE,
                engine = BisyncPreviewCommandBuilder.LOCAL_STATE_CLONE_ENGINE
            ),
            acceptedStateDirectory = "${FileRoot.path}/accepted"
        ))

        assertTrue(command.containsAll(listOf("--preview-state-from", "${FileRoot.path}/accepted")))
        assertFalse(command.contains("--resync"))
        assertFalse(command.contains("--resync-mode"))
    }

    @Test
    fun unknownPinsAndUnsafeOrUnmodeledRequestsAreRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            BisyncPreviewCommandBuilder.build(request(identity = identity(
                BisyncNativeState.ABSENT, BisyncPreviewResyncMode.PATH1,
                engine = "${"a".repeat(40)}"
            )))
        }
        assertThrows(IllegalArgumentException::class.java) {
            BisyncPreviewCommandBuilder.build(request(path1 = "/left\u0000--force"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            BisyncPreviewCommandBuilder.build(request(checksumRequested = true))
        }
        assertThrows(IllegalArgumentException::class.java) {
            BisyncPreviewCommandBuilder.build(request(filters = listOf(
                FilterEntry(FilterEntry.FILTER_INCLUDE, "safe\n--force")
            )))
        }
        assertThrows(IllegalArgumentException::class.java) {
            BisyncPreviewCommandBuilder.build(request(identity = identity(BisyncNativeState.ABSENT)))
        }
    }

    private fun request(
        identity: BisyncPreviewIdentity = identity(BisyncNativeState.ABSENT, BisyncPreviewResyncMode.PATH1),
        path1: String = "/private/left",
        filters: List<FilterEntry> = emptyList(),
        deleteExcluded: Boolean = false,
        acceptedStateDirectory: String? = null,
        checksumRequested: Boolean = false
    ) = BisyncPreviewCommandRequest(
        identity = identity,
        previewId = UUID.fromString("11111111-1111-4111-8111-111111111111").toString(),
        path1 = path1,
        path2 = "remote:/right",
        workDirectory = "${FileRoot.path}/preview",
        filters = filters,
        deleteExcluded = deleteExcluded,
        acceptedStateDirectory = acceptedStateDirectory,
        checksumRequested = checksumRequested
    )

    private fun identity(
        nativeState: BisyncNativeState,
        initializationMode: BisyncPreviewResyncMode? = null,
        engine: String = BisyncPreviewCommandBuilder.PUBLISHED_PREVIEW_ENGINE
    ) = BisyncPreviewIdentity(
        profileId = "22222222-2222-4222-8222-222222222222",
        profileRevision = 1,
        profileFingerprint = digest('1'),
        engineRef = "rclone:1.76.0@$engine",
        stateVersion = 1,
        leftAccountFingerprint = digest('2'),
        leftScopeFingerprint = digest('3'),
        rightAccountFingerprint = digest('4'),
        rightScopeFingerprint = digest('5'),
        filterFingerprint = digest('6'),
        comparisonMode = BisyncComparisonMode.SIZE_ONLY,
        initializationMode = initializationMode,
        maxDeletePercent = 10,
        maxDeleteCount = 25,
        nativeState = nativeState,
        acceptedBaselineFingerprint = if (nativeState == BisyncNativeState.COMPATIBLE) digest('7') else null
    )

    private fun digest(char: Char) = char.toString().repeat(64)

    private object FileRoot {
        val path: String = (System.getProperty("java.io.tmpdir")
            ?: throw IllegalStateException("java.io.tmpdir is not set")).replace('\\', '/')
    }
}
