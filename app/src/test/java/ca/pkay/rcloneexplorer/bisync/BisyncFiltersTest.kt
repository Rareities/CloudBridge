package ca.pkay.rcloneexplorer.bisync

import ca.pkay.rcloneexplorer.Items.FilterEntry
import org.junit.Assert.*
import org.junit.Test

class BisyncFiltersTest {
    @Test fun preservesOrderAndMarkerBeforeCatchAll() {
        val content = BisyncFilters.encode(listOf(FilterEntry(0, "/日本語/**"), FilterEntry(1, "**")), ".marker")
        assertEquals("+ /.marker\n+ /日本語/**\n- **\n", content)
    }
    @Test fun changingRuleOrOrderInvalidatesApprovalDigest() {
        val include = FilterEntry(0, "*.jpg"); val exclude = FilterEntry(1, "private/**")
        assertNotEquals(BisyncFilters.digest(BisyncFilters.encode(listOf(include, exclude))),
            BisyncFilters.digest(BisyncFilters.encode(listOf(exclude, include))))
        assertNotEquals(BisyncFilters.digest(BisyncFilters.encode(emptyList())),
            BisyncFilters.digest(BisyncFilters.encode(listOf(include))))
    }
    @Test fun immutableSnapshotRejectsTamperingAndReusesIdenticalRules() {
        val root = java.nio.file.Files.createTempDirectory("bisync-rules-test").toFile()
        try {
            val rules = BisyncFilters.encode(listOf(FilterEntry(0, "*.jpg")))
            val file = BisyncFilters.snapshot(root, rules)
            assertEquals(file, BisyncFilters.snapshot(root, rules))
            file.writeText("+ **\n")
            try { BisyncFilters.snapshot(root, rules); fail("Tampering must reject execution") }
            catch (expected: IllegalStateException) { assertTrue(expected.message!!.contains("integrity")) }
        } finally { root.deleteRecursively() }
    }
    @Test(expected = IllegalArgumentException::class) fun preventsInjectedRules() {
        BisyncFilters.encode(listOf(FilterEntry(0, "*.jpg\n!")))
    }
    @Test(expected = IllegalArgumentException::class) fun rejectsUnknownRuleType() {
        BisyncFilters.encode(listOf(FilterEntry(7, "**")))
    }
}
