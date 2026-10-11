package de.schuelken.cloudbridge.updates

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ReleaseSelectorTest {
    private fun release(tag: String, draft: Boolean = false, prerelease: Boolean = false) =
        JSONObject().put("tag_name", tag).put("draft", draft).put("prerelease", prerelease)
            .put("body", tag)

    @Test fun ignoresDraftsAndPrereleasesForStableUsersAndFindsNewestUnsorted() {
        val releases = JSONArray().put(release("v1.5.0-beta.7", prerelease = true))
            .put(release("v1.4.10"))
            .put(release("v99.0.0", draft = true))
            .put(release("v1.4.9"))
        assertEquals("v1.4.10", ReleaseSelector.newest("1.4.2", releases)?.tag)
        assertNull(ReleaseSelector.newest("1.4.11", releases))
    }

    @Test fun handlesPrereleasePrecedenceAndVeryLargeVersionNumbers() {
        val releases = JSONArray().put(release("v1.5.0-beta.9", prerelease = true))
            .put(release("v1.5.0-beta.10", prerelease = true))
            .put(release("v1.5.0-rc.1", prerelease = true))
        assertEquals("v1.5.0-rc.1", ReleaseSelector.newest("v1.5.0-beta.3", releases)?.tag)
        assertNull(ReleaseSelector.newest("1.5.0", releases))
        assertEquals("v30000000000.10.0", ReleaseSelector.newest("v30000000000.9.0",
            JSONArray().put(release("v30000000000.10.0")))?.tag)
    }

    @Test fun debugSuffixDoesNotSubscribeToPrereleaseAndStableBeatsRc() {
        val releases = JSONArray().put(release("v2.0.0-alpha.1", prerelease = true))
            .put(release("v2.0.0-rc.3", prerelease = true))
            .put(release("v2.0.0"))
        assertEquals("v2.0.0", ReleaseSelector.newest("v1.9.8-DEBUG", releases)?.tag)
        assertEquals("v2.0.0", ReleaseSelector.newest("v2.0.0-rc.2", releases)?.tag)
        assertNull(ReleaseSelector.newest("invalid", releases))
    }

    @Test fun doesNotTrustGithubReleaseOrderingOrUnknownTags() {
        val releases = JSONArray().put(release("nightly-build"))
            .put(release("v1.0.1"))
            .put(release("v1.0.12"))
            .put(release("v1.0.2"))
        assertEquals("v1.0.12", ReleaseSelector.newest("1.0.0", releases)?.tag)
    }
}
