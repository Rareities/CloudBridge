package ca.pkay.rcloneexplorer.Database

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ca.pkay.rcloneexplorer.Items.FilterEntry

class BisyncPreflightTest {
    @Test
    fun unresolvedOrOverlappingRootsFailClosed() {
        val storage = hex('a')
        val parent = BisyncEndpointScope.from(storage, "/notes")
        val child = BisyncEndpointScope.from(storage, "notes/obsidian")
        val sibling = BisyncEndpointScope.from(storage, "archive")
        val root = BisyncEndpointScope.from(storage, "")

        assertTrue(parent.overlaps(child))
        assertFalse(parent.overlaps(sibling))
        assertTrue(root.isResolved)
        assertTrue(root.overlaps(child))
        assertFalse(BisyncEndpointScope.from(storage, null).isResolved)
        assertTrue(BisyncEndpointScope.unknown().overlaps(sibling))
    }

    @Test
    fun completeEmptyListingIsDistinctFromFailedOrTruncatedListing() {
        val empty = BisyncListingValidator.parseRecursiveList("[]", true, false)
        val failedWithPartialJson = BisyncListingValidator.parseRecursiveList("[]", false, false)
        val truncated = BisyncListingValidator.parseRecursiveList("[]", true, true)
        val malformed = BisyncListingValidator.parseRecursiveList("[{", true, false)

        assertTrue(empty.complete)
        assertEquals(0, empty.itemCount)
        assertFalse(failedWithPartialJson.complete)
        assertFalse(truncated.complete)
        assertFalse(malformed.complete)
    }

    @Test
    fun recursiveListingRejectsTraversalDuplicatesAndMalformedEntries() {
        val traversal = """[{"Path":"../secret","Name":"secret","IsDir":false,"Size":1,"ModTime":"2026-01-01T00:00:00Z"}]"""
        val duplicate = """[{"Path":"a","Name":"a","IsDir":false,"Size":1,"ModTime":"2026-01-01T00:00:00Z"},{"Path":"a","Name":"a","IsDir":false,"Size":1,"ModTime":"2026-01-01T00:00:00Z"}]"""
        val missingMetadata = """[{"Path":"a","Name":"a","IsDir":false}]"""

        assertFalse(BisyncListingValidator.parseRecursiveList(traversal, true, false).complete)
        assertFalse(BisyncListingValidator.parseRecursiveList(duplicate, true, false).complete)
        assertFalse(BisyncListingValidator.parseRecursiveList(missingMetadata, true, false).complete)
        assertFalse(BisyncListingValidator.parseRecursiveList("[]", true, false, 0).complete)
    }

    @Test
    fun selectedFiltersAreBoundedAndValidatedWithoutChangingTheirRules() {
        val parsed = BisyncFilterParser.parse("+/vault/**\r\n-*.tmp\n")
        assertTrue(parsed.valid)
        assertEquals(2, parsed.ruleCount)
        val entries = requireNotNull(parsed.entries)
        assertEquals(FilterEntry.FILTER_INCLUDE, entries[0].filterType)
        assertEquals("/vault/**", entries[0].filter)
        assertEquals(FilterEntry.FILTER_EXCLUDE, entries[1].filterType)
        assertEquals("*.tmp", entries[1].filter)

        assertFalse(BisyncFilterParser.parse("rule-without-action").valid)
        assertFalse(BisyncFilterParser.parse("+ \n").valid)
        assertFalse(BisyncFilterParser.parse("+ ${"x".repeat(BisyncFilterParser.MAX_PATTERN_CHARS + 1)}").valid)
        assertTrue(BisyncFilterParser.parse(null).valid)
    }

    @Test
    fun rootStatRequiresSuccessfulDirectoryProbe() {
        assertTrue(BisyncListingValidator.parseRootStat("""{"IsDir":true}""", true, false).rootIsDirectory)
        assertEquals(
            BisyncPreflightReason.ROOT_NOT_DIRECTORY,
            BisyncListingValidator.parseRootStat("""{"IsDir":false}""", true, false).failure
        )
        assertFalse(BisyncListingValidator.parseRootStat("{}", false, false).complete)
    }

    @Test
    fun absentNativeStateRequiresInitializationAndMissingAcceptedStateBlocks() {
        val initial = BisyncPreflightPolicy.evaluate(input())
        assertEquals(ProfileReadiness.INITIALIZATION_REQUIRED, initial.readiness)
        assertNull(initial.reason)
        assertNotNull(initial.candidateBaseline)

        val disappeared = BisyncPreflightPolicy.evaluate(
            input(nativeState = BisyncNativeState.ABSENT, previous = initial.candidateBaseline)
        )
        assertEquals(ProfileReadiness.BLOCKED, disappeared.readiness)
        assertEquals(BisyncPreflightReason.NATIVE_STATE_UNKNOWN, disappeared.reason)
    }

    @Test
    fun interruptedNativeStateIsDistinctAndUnrecognizedWireStatusFailsClosed() {
        val interrupted = BisyncNativeStateEvidence.fromWire(
            "INTERRUPTED", "CURRENT_LISTINGS_PARTIAL", recoveryListingsValid = true
        )
        assertEquals(BisyncNativeState.INTERRUPTED, interrupted.state)
        assertTrue(interrupted.recoveryListingsValid)
        assertEquals(
            BisyncPreflightReason.NATIVE_STATE_INTERRUPTED,
            BisyncPreflightPolicy.evaluate(input(nativeState = interrupted.state)).reason
        )

        val unknown = BisyncNativeStateEvidence.fromWire(
            "FUTURE_STATUS", "raw path or error", recoveryListingsValid = true
        )
        assertEquals(BisyncNativeState.UNKNOWN, unknown.state)
        assertEquals("INVALID_NATIVE_RESULT", unknown.reason)
        assertFalse(unknown.recoveryListingsValid)
    }

    @Test
    fun compatibleStateNeedsAnAcceptedBaseline() {
        val unverified = BisyncPreflightPolicy.evaluate(input(nativeState = BisyncNativeState.COMPATIBLE))
        assertEquals(ProfileReadiness.BLOCKED, unverified.readiness)
        assertEquals(BisyncPreflightReason.NATIVE_STATE_UNVERIFIED, unverified.reason)

        val initialized = BisyncPreflightPolicy.evaluate(
            input(nativeState = BisyncNativeState.COMPATIBLE, previous = initialBaseline())
        )
        assertEquals(ProfileReadiness.READY, initialized.readiness)
        assertNull(initialized.reason)
    }

    @Test
    fun changedEngineAccountRootAndFilterInvalidateAcceptedBaseline() {
        val baseline = initialBaseline()
        assertEquals(
            BisyncPreflightReason.ENGINE_CHANGED,
            BisyncPreflightPolicy.evaluate(input(
                engineRef = "rclone:v1.70.0@${commit('b')}",
                previous = baseline,
                nativeState = BisyncNativeState.COMPATIBLE
            )).reason
        )
        assertEquals(
            BisyncPreflightReason.ENDPOINT_IDENTITY_CHANGED,
            BisyncPreflightPolicy.evaluate(input(
                leftAccount = hex('c'), previous = baseline,
                nativeState = BisyncNativeState.COMPATIBLE
            )).reason
        )
        assertEquals(
            BisyncPreflightReason.ENDPOINT_IDENTITY_CHANGED,
            BisyncPreflightPolicy.evaluate(input(
                leftPath = "different-root", previous = baseline,
                nativeState = BisyncNativeState.COMPATIBLE
            )).reason
        )
        assertEquals(
            BisyncPreflightReason.FILTER_CHANGED,
            BisyncPreflightPolicy.evaluate(input(
                filterFingerprint = hex('d'), previous = baseline,
                nativeState = BisyncNativeState.COMPATIBLE
            )).reason
        )
        assertEquals(
            BisyncPreflightReason.PROFILE_CHANGED,
            BisyncPreflightPolicy.evaluate(input(
                maxDeleteCount = 24, previous = baseline,
                nativeState = BisyncNativeState.COMPATIBLE
            )).reason
        )
        assertEquals(
            BisyncPreflightReason.PROFILE_CHANGED,
            BisyncPreflightPolicy.evaluate(input(
                maxDeletePercent = 9, previous = baseline,
                nativeState = BisyncNativeState.COMPATIBLE
            )).reason
        )
    }

    @Test
    fun hashlessProviderCanUseSizeAndModtimeAndSizeOnlyIsDisclosed() {
        val normal = BisyncPreflightPolicy.evaluate(input(
            supportsModTime = true, nativeState = BisyncNativeState.ABSENT
        ))
        assertEquals(ProfileReadiness.INITIALIZATION_REQUIRED, normal.readiness)

        val unsupported = BisyncPreflightPolicy.evaluate(input(
            supportsModTime = false, nativeState = BisyncNativeState.ABSENT
        ))
        assertEquals(BisyncPreflightReason.COMPARISON_UNSUPPORTED, unsupported.reason)

        val sizeOnly = BisyncPreflightPolicy.evaluate(input(
            comparisonMode = BisyncComparisonMode.SIZE_ONLY,
            supportsModTime = false,
            nativeState = BisyncNativeState.ABSENT
        ))
        assertEquals(ProfileReadiness.INITIALIZATION_REQUIRED, sizeOnly.readiness)
        assertNotNull(sizeOnly.comparisonDisclosure)
        assertTrue(sizeOnly.comparisonDisclosure!!.contains("equal"))
    }

    @Test
    fun unsafeDeleteLimitsAndAmbiguousAccessBlock() {
        val invalidLimit = BisyncPreflightPolicy.evaluate(input(maxDeleteCount = 0))
        assertEquals(BisyncPreflightReason.DELETE_LIMIT_INVALID, invalidLimit.reason)
        assertEquals(
            BisyncPreflightReason.DELETE_LIMIT_INVALID,
            BisyncPreflightPolicy.evaluate(input(maxDeletePercent = 101)).reason
        )

        val incomplete = BisyncPreflightPolicy.evaluate(input(
            leftListing = BisyncListingEvidence(false, false, 0)
        ))
        assertEquals(BisyncPreflightReason.LISTING_INCOMPLETE, incomplete.reason)

        val overlapping = BisyncPreflightPolicy.evaluate(input(rightPath = "left", rightStorage = hex('f')))
        assertEquals(BisyncPreflightReason.ROOT_OVERLAP, overlapping.reason)

        val missingFilter = BisyncPreflightPolicy.evaluate(input(filterResolved = false))
        assertEquals(BisyncPreflightReason.FILTER_MISSING, missingFilter.reason)
    }

    private fun initialBaseline(): BisyncPreflightBaseline =
        requireNotNull(BisyncPreflightPolicy.evaluate(input()).candidateBaseline)

    private fun input(
        engineRef: String = "rclone:v1.70.0@${commit('a')}",
        leftAccount: String = hex('b'),
        leftPath: String = "left",
        rightPath: String = "right",
        rightStorage: String = hex('2'),
        filterFingerprint: String = hex('c'),
        filterResolved: Boolean = true,
        supportsModTime: Boolean = true,
        comparisonMode: BisyncComparisonMode = BisyncComparisonMode.SIZE_AND_MODTIME,
        nativeState: BisyncNativeState = BisyncNativeState.ABSENT,
        previous: BisyncPreflightBaseline? = null,
        leftListing: BisyncListingEvidence = BisyncListingEvidence(true, true, 0),
        maxDeletePercent: Int = 10,
        maxDeleteCount: Int = 25
    ) = BisyncPreflightInput(
        profileRevision = 4,
        profileFingerprint = hex('e'),
        engineRef = engineRef,
        stateVersion = BisyncPreflightPolicy.CURRENT_STATE_VERSION,
        left = BisyncEndpointEvidence(
            leftAccount,
            BisyncEndpointScope.from(hex('f'), leftPath),
            supportsModTime
        ),
        right = BisyncEndpointEvidence(
            hex('1'),
            BisyncEndpointScope.from(rightStorage, rightPath),
            supportsModTime
        ),
        leftListing = leftListing,
        rightListing = BisyncListingEvidence(true, true, 0),
        filterFingerprint = filterFingerprint,
        filterResolved = filterResolved,
        comparisonMode = comparisonMode,
        nativeState = nativeState,
        previous = previous,
        maxDeletePercent = maxDeletePercent,
        maxDeleteCount = maxDeleteCount
    )

    private fun hex(char: Char) = char.toString().repeat(64)
    private fun commit(char: Char) = char.toString().repeat(40)
}
