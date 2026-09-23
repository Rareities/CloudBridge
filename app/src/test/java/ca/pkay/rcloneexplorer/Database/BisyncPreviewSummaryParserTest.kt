package ca.pkay.rcloneexplorer.Database

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BisyncPreviewSummaryParserTest {
    @Test
    fun acceptsExactNativeCompleteSummaryAsReviewData() {
        val result = parse(
            """{"version":1,"status":"COMPLETE","plannedTransfers":2,"plannedBytes":26,"plannedFileDeletes":0,"plannedDirectoryDeletes":0,"errorCount":0,"conflictsKnown":false}"""
        )

        assertTrue(result is BisyncPreviewParseResult.Available)
        val summary = (result as BisyncPreviewParseResult.Available).summary
        assertEquals(BisyncPreviewStatus.COMPLETE, summary.status)
        assertEquals(2L, summary.plannedTransfers)
        assertEquals(26L, summary.plannedBytes)
        assertFalse(summary.conflictsKnown)
    }

    @Test
    fun retainsNativeIncompleteStatusWithoutPromotingItToComplete() {
        val result = parse(
            """{"version":1,"status":"INCOMPLETE","plannedTransfers":0,"plannedBytes":0,"plannedFileDeletes":0,"plannedDirectoryDeletes":0,"errorCount":1,"conflictsKnown":false}"""
        )

        assertTrue(result is BisyncPreviewParseResult.Available)
        assertEquals(
            BisyncPreviewStatus.INCOMPLETE,
            (result as BisyncPreviewParseResult.Available).summary.status
        )
    }

    @Test
    fun rejectsDuplicateUnknownAndPathBearingFields() {
        val duplicate = validJson().replace("\"version\":1", "\"version\":1,\"version\":1")
        val unknown = validJson().dropLast(1) + ",\"futureField\":0}"
        val pathBearing = validJson().dropLast(1) + ",\"path\":\"private/name\"}"

        assertUnavailable(duplicate, BisyncPreviewUnavailableReason.INVALID_JSON)
        assertUnavailable(unknown, BisyncPreviewUnavailableReason.INVALID_SCHEMA)
        assertUnavailable(pathBearing, BisyncPreviewUnavailableReason.INVALID_SCHEMA)
    }

    @Test
    fun rejectsMissingWrongTypedNegativeFractionalAndOverflowValues() {
        assertUnavailable("{}", BisyncPreviewUnavailableReason.INVALID_SCHEMA)
        assertUnavailable(validJson().replace("\"plannedBytes\":26", "\"plannedBytes\":\"26\""), BisyncPreviewUnavailableReason.INVALID_SCHEMA)
        assertUnavailable(validJson().replace("\"plannedBytes\":26", "\"plannedBytes\":-1"), BisyncPreviewUnavailableReason.INVALID_SCHEMA)
        assertUnavailable(validJson().replace("\"plannedBytes\":26", "\"plannedBytes\":1.5"), BisyncPreviewUnavailableReason.INVALID_SCHEMA)
        assertUnavailable(validJson().replace("\"plannedBytes\":26", "\"plannedBytes\":9223372036854775808"), BisyncPreviewUnavailableReason.INVALID_SCHEMA)
        assertUnavailable(validJson().replace("\"conflictsKnown\":false", "\"conflictsKnown\":true"), BisyncPreviewUnavailableReason.INVALID_SCHEMA)
        assertUnavailable(validJson().replace("\"errorCount\":0", "\"errorCount\":1"), BisyncPreviewUnavailableReason.INVALID_SCHEMA)
    }

    @Test
    fun acceptsLargestNonNegativeLongWithoutOverflow() {
        val result = parse(validJson().replace("\"plannedBytes\":26", "\"plannedBytes\":9223372036854775807"))

        assertTrue(result is BisyncPreviewParseResult.Available)
        assertEquals(Long.MAX_VALUE, (result as BisyncPreviewParseResult.Available).summary.plannedBytes)
    }

    @Test
    fun requiresConfirmedSuccessfulUntruncatedBoundedSingleJsonOutput() {
        assertUnavailable(validJson(), BisyncPreviewUnavailableReason.PROCESS_FAILED, succeeded = false)
        assertUnavailable(validJson(), BisyncPreviewUnavailableReason.OUTPUT_TRUNCATED, truncated = true)
        assertUnavailable(" ", BisyncPreviewUnavailableReason.OUTPUT_EMPTY)
        assertUnavailable("{".repeat(BisyncPreviewSummaryParser.MAX_OUTPUT_CHARS + 1), BisyncPreviewUnavailableReason.OUTPUT_TOO_LARGE)
        assertUnavailable(validJson() + "{}", BisyncPreviewUnavailableReason.INVALID_SCHEMA)
        assertUnavailable("not json", BisyncPreviewUnavailableReason.INVALID_JSON)
        assertUnavailable(validJson().replace("\"version\":1", "\"version\":2"), BisyncPreviewUnavailableReason.UNSUPPORTED_VERSION)
    }

    private fun parse(
        json: String,
        succeeded: Boolean = true,
        truncated: Boolean = false
    ) = BisyncPreviewSummaryParser.parse(json, succeeded, truncated)

    private fun assertUnavailable(
        json: String,
        reason: BisyncPreviewUnavailableReason,
        succeeded: Boolean = true,
        truncated: Boolean = false
    ) {
        val result = parse(json, succeeded, truncated)
        assertTrue("Expected unavailable result, got $result", result is BisyncPreviewParseResult.Unavailable)
        assertEquals(reason, (result as BisyncPreviewParseResult.Unavailable).reason)
    }

    private fun validJson() =
        """{"version":1,"status":"COMPLETE","plannedTransfers":2,"plannedBytes":26,"plannedFileDeletes":0,"plannedDirectoryDeletes":0,"errorCount":0,"conflictsKnown":false}"""
}
