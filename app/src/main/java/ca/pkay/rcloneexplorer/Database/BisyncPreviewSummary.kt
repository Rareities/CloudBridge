package ca.pkay.rcloneexplorer.Database

import com.fasterxml.jackson.core.JsonFactory
import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.core.JsonToken
import java.io.IOException

enum class BisyncPreviewStatus {
    COMPLETE,
    INCOMPLETE
}

/**
 * Path-free counters emitted by the pinned native Bisync implementation.
 * This is review data only; it is never an authorization to execute a sync.
 */
data class BisyncPreviewSummary(
    val status: BisyncPreviewStatus,
    val plannedTransfers: Long,
    val plannedBytes: Long,
    val plannedFileDeletes: Long,
    val plannedDirectoryDeletes: Long,
    val errorCount: Long,
    val conflictsKnown: Boolean = false
)

enum class BisyncPreviewUnavailableReason {
    PROCESS_FAILED,
    OUTPUT_TRUNCATED,
    OUTPUT_EMPTY,
    OUTPUT_TOO_LARGE,
    INVALID_JSON,
    UNSUPPORTED_VERSION,
    INVALID_SCHEMA
}

sealed class BisyncPreviewParseResult {
    data class Available(val summary: BisyncPreviewSummary) : BisyncPreviewParseResult()
    data class Unavailable(val reason: BisyncPreviewUnavailableReason) : BisyncPreviewParseResult()
}

/** Strict parser for the native path-free v1 preview protocol. */
object BisyncPreviewSummaryParser {
    const val MAX_OUTPUT_CHARS = 4 * 1024

    private val requiredFields = setOf(
        "version",
        "status",
        "plannedTransfers",
        "plannedBytes",
        "plannedFileDeletes",
        "plannedDirectoryDeletes",
        "errorCount",
        "conflictsKnown"
    )

    @JvmStatic
    fun parse(
        json: String?,
        processSucceededAndConfirmed: Boolean,
        outputTruncated: Boolean
    ): BisyncPreviewParseResult {
        if (!processSucceededAndConfirmed) return unavailable(BisyncPreviewUnavailableReason.PROCESS_FAILED)
        if (outputTruncated) return unavailable(BisyncPreviewUnavailableReason.OUTPUT_TRUNCATED)
        if (json.isNullOrBlank()) return unavailable(BisyncPreviewUnavailableReason.OUTPUT_EMPTY)
        if (json.length > MAX_OUTPUT_CHARS) return unavailable(BisyncPreviewUnavailableReason.OUTPUT_TOO_LARGE)

        return try {
            val factory = JsonFactory().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            factory.createParser(json).use { parser ->
                if (parser.nextToken() != JsonToken.START_OBJECT) {
                    return unavailable(BisyncPreviewUnavailableReason.INVALID_SCHEMA)
                }

                var version: Int? = null
                var status: BisyncPreviewStatus? = null
                var plannedTransfers: Long? = null
                var plannedBytes: Long? = null
                var plannedFileDeletes: Long? = null
                var plannedDirectoryDeletes: Long? = null
                var errorCount: Long? = null
                var conflictsKnown: Boolean? = null
                val seenFields = HashSet<String>(requiredFields.size)

                while (parser.nextToken() != JsonToken.END_OBJECT) {
                    if (parser.currentToken != JsonToken.FIELD_NAME) {
                        return unavailable(BisyncPreviewUnavailableReason.INVALID_SCHEMA)
                    }
                    val field = parser.currentName
                    if (field !in requiredFields || !seenFields.add(field)) {
                        return unavailable(BisyncPreviewUnavailableReason.INVALID_SCHEMA)
                    }
                    val valueToken = parser.nextToken()
                        ?: return unavailable(BisyncPreviewUnavailableReason.INVALID_SCHEMA)

                    when (field) {
                        "version" -> {
                            if (valueToken != JsonToken.VALUE_NUMBER_INT) {
                                return unavailable(BisyncPreviewUnavailableReason.INVALID_SCHEMA)
                            }
                            val parsed = parser.bigIntegerValue
                            if (parsed != java.math.BigInteger.ONE) {
                                return unavailable(BisyncPreviewUnavailableReason.UNSUPPORTED_VERSION)
                            }
                            version = 1
                        }
                        "status" -> {
                            if (valueToken != JsonToken.VALUE_STRING) {
                                return unavailable(BisyncPreviewUnavailableReason.INVALID_SCHEMA)
                            }
                            status = when (parser.text) {
                                "COMPLETE" -> BisyncPreviewStatus.COMPLETE
                                "INCOMPLETE" -> BisyncPreviewStatus.INCOMPLETE
                                else -> return unavailable(BisyncPreviewUnavailableReason.INVALID_SCHEMA)
                            }
                        }
                        "plannedTransfers" -> plannedTransfers = parser.nonNegativeLong(valueToken)
                        "plannedBytes" -> plannedBytes = parser.nonNegativeLong(valueToken)
                        "plannedFileDeletes" -> plannedFileDeletes = parser.nonNegativeLong(valueToken)
                        "plannedDirectoryDeletes" -> plannedDirectoryDeletes = parser.nonNegativeLong(valueToken)
                        "errorCount" -> errorCount = parser.nonNegativeLong(valueToken)
                        "conflictsKnown" -> {
                            if (valueToken != JsonToken.VALUE_FALSE) {
                                return unavailable(BisyncPreviewUnavailableReason.INVALID_SCHEMA)
                            }
                            conflictsKnown = false
                        }
                    }
                }

                if (parser.nextToken() != null || seenFields != requiredFields || version != 1) {
                    return unavailable(BisyncPreviewUnavailableReason.INVALID_SCHEMA)
                }
                val parsedStatus = status ?: return unavailable(BisyncPreviewUnavailableReason.INVALID_SCHEMA)
                val parsedErrorCount = errorCount ?: return unavailable(BisyncPreviewUnavailableReason.INVALID_SCHEMA)
                if (parsedStatus == BisyncPreviewStatus.COMPLETE && parsedErrorCount != 0L) {
                    return unavailable(BisyncPreviewUnavailableReason.INVALID_SCHEMA)
                }

                BisyncPreviewParseResult.Available(
                    BisyncPreviewSummary(
                        status = parsedStatus,
                        plannedTransfers = plannedTransfers ?: return unavailable(BisyncPreviewUnavailableReason.INVALID_SCHEMA),
                        plannedBytes = plannedBytes ?: return unavailable(BisyncPreviewUnavailableReason.INVALID_SCHEMA),
                        plannedFileDeletes = plannedFileDeletes ?: return unavailable(BisyncPreviewUnavailableReason.INVALID_SCHEMA),
                        plannedDirectoryDeletes = plannedDirectoryDeletes ?: return unavailable(BisyncPreviewUnavailableReason.INVALID_SCHEMA),
                        errorCount = parsedErrorCount,
                        conflictsKnown = conflictsKnown ?: return unavailable(BisyncPreviewUnavailableReason.INVALID_SCHEMA)
                    )
                )
            }
        } catch (_: ArithmeticException) {
            unavailable(BisyncPreviewUnavailableReason.INVALID_SCHEMA)
        } catch (_: InvalidPreviewSchemaException) {
            unavailable(BisyncPreviewUnavailableReason.INVALID_SCHEMA)
        } catch (_: IOException) {
            unavailable(BisyncPreviewUnavailableReason.INVALID_JSON)
        }
    }

    private fun JsonParser.nonNegativeLong(token: JsonToken): Long {
        if (token != JsonToken.VALUE_NUMBER_INT) {
            throw InvalidPreviewSchemaException()
        }
        val value = bigIntegerValue
        if (value.signum() < 0 || value.bitLength() > 63) {
            throw InvalidPreviewSchemaException()
        }
        // Sign and bit-length checks above prove this cast cannot overflow and avoid the
        // API-31-only BigInteger.longValueExact() on the app's API-23 minimum.
        return value.toLong()
    }

    private fun unavailable(reason: BisyncPreviewUnavailableReason) =
        BisyncPreviewParseResult.Unavailable(reason)

    private class InvalidPreviewSchemaException : IOException()
}
