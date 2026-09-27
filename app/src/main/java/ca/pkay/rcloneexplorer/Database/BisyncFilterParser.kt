package ca.pkay.rcloneexplorer.Database

import ca.pkay.rcloneexplorer.Items.FilterEntry

data class BisyncFilterParseResult(
    val entries: List<FilterEntry>?,
    val valid: Boolean,
    val ruleCount: Int
)

/** Strictly validates the persisted CloudBridge filter grammar before passing argv to rclone. */
object BisyncFilterParser {
    const val MAX_RAW_CHARS = 1_048_576
    const val MAX_RULES = 4096
    const val MAX_PATTERN_CHARS = 8192

    @JvmStatic
    fun parse(raw: String?): BisyncFilterParseResult {
        if (raw == null) return BisyncFilterParseResult(emptyList(), true, 0)
        if (raw.length > MAX_RAW_CHARS || raw.contains('\u0000')) return invalid()

        val parsed = ArrayList<FilterEntry>()
        for (sourceLine in raw.split('\n')) {
            val line = if (sourceLine.endsWith('\r')) sourceLine.dropLast(1) else sourceLine
            if (line.isEmpty()) continue
            if (line.length < 2 || (line[0] != '+' && line[0] != '-')) return invalid(parsed.size)
            val pattern = line.substring(1)
            if (pattern.isBlank() || pattern.length > MAX_PATTERN_CHARS || pattern.contains('\r')) {
                return invalid(parsed.size)
            }
            val type = if (line[0] == '+') FilterEntry.FILTER_INCLUDE else FilterEntry.FILTER_EXCLUDE
            parsed.add(FilterEntry(type, pattern))
            if (parsed.size > MAX_RULES) return invalid(parsed.size)
        }
        return BisyncFilterParseResult(parsed, true, parsed.size)
    }

    private fun invalid(ruleCount: Int = 0) = BisyncFilterParseResult(null, false, ruleCount)
}
