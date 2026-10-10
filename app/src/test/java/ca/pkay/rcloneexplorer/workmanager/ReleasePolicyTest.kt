package de.schuelken.cloudbridge.updates.workmanager

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReleasePolicyTest {
    @Test fun stableSkipsDraftAndPreviewAndDoesNotTrustApiOrder() {
        val releases = listOf(
            ReleasePolicy.Release("v9.0.0", draft = true),
            ReleasePolicy.Release("v3.0.0-beta.1", prerelease = true),
            ReleasePolicy.Release("v1.0.3"), ReleasePolicy.Release("v1.0.4"))
        assertEquals("v1.0.4", ReleasePolicy.select("1.0.2", releases)?.tag)
    }
    @Test fun numericPrereleasesAndStablePromotionWork() {
        assertEquals("v1.0.2", ReleasePolicy.select("1.0.2-beta.2", listOf(
            ReleasePolicy.Release("v1.0.2-beta.10", prerelease = true),
            ReleasePolicy.Release("v1.0.2")))?.tag)
        assertEquals("v1.0.2-beta.10", ReleasePolicy.select("1.0.2-beta.2", listOf(
            ReleasePolicy.Release("v1.0.2-beta.10", prerelease = true)))?.tag)
    }
    @Test fun commitHashesUsePublicationDateNotLexicalOrdering() {
        val releases = listOf(
            ReleasePolicy.Release("v1.0.2-beta.fffffff", prerelease = true, publishedAt = "2026-10-01T00:00:00Z"),
            ReleasePolicy.Release("v1.0.2-beta.aaaaaaa", prerelease = true, publishedAt = "2026-10-02T00:00:00Z"))
        assertEquals("v1.0.2-beta.aaaaaaa", ReleasePolicy.select("1.0.2-beta.fffffff", releases)?.tag)
        assertNull(ReleasePolicy.select("1.0.2-beta.bbbbbbb", releases))
    }
    @Test fun malformedAndEqualVersionsNeverUpgrade() {
        assertNull(ReleasePolicy.select("1.0.2", listOf(ReleasePolicy.Release("v1.0.2"), ReleasePolicy.Release("garbage"))))
    }
}
