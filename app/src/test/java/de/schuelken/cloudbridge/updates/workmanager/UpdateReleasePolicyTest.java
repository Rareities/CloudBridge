package de.schuelken.cloudbridge.updates.workmanager;

import org.junit.Test;

import java.util.Arrays;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.fail;

public class UpdateReleasePolicyTest {
    @Test
    public void linksTargetTheRareitiesFork() {
        assertEquals("Rareities", UpdateReleasePolicy.REPOSITORY_OWNER);
        assertEquals("CloudBridge", UpdateReleasePolicy.REPOSITORY_NAME);
        assertEquals("https://github.com/Rareities/CloudBridge", UpdateReleasePolicy.REPOSITORY_URL);
        assertEquals("https://api.github.com/repos/Rareities/CloudBridge/releases?per_page=100&page=1",
                UpdateReleasePolicy.RELEASES_API_URL);
        assertEquals(100, UpdateReleasePolicy.RELEASES_PER_PAGE);
        assertEquals("https://api.github.com/repos/Rareities/CloudBridge/releases?per_page=100&page=2",
                UpdateReleasePolicy.releasesApiUrl(2));
        assertEquals("https://github.com/Rareities/CloudBridge/releases/latest",
                UpdateReleasePolicy.LATEST_RELEASE_URL);
        try {
            UpdateReleasePolicy.releasesApiUrl(UpdateReleasePolicy.MAX_RELEASE_PAGES + 1);
            fail("Expected pages past the bounded scan to be rejected");
        } catch (IllegalArgumentException expected) {
            // A bounded scan fails closed rather than silently claiming it saw every release.
        }
    }

    @Test
    public void releaseNotificationTargetsTheSelectedTagAndRejectsUnsafeStoredValues() {
        assertEquals("https://github.com/Rareities/CloudBridge/releases/tag/v1.2.3",
                UpdateReleasePolicy.releasePageUrl("v1.2.3"));
        assertEquals("https://github.com/Rareities/CloudBridge/releases/tag/v1.2.3-beta.4+build.7",
                UpdateReleasePolicy.releasePageUrl("v1.2.3-beta.4+build.7"));
        assertEquals(UpdateReleasePolicy.RELEASES_INDEX_URL,
                UpdateReleasePolicy.releasePageUrl("../../attacker.example"));
        assertEquals(UpdateReleasePolicy.RELEASES_INDEX_URL,
                UpdateReleasePolicy.releasePageUrl(""));
    }

    @Test
    public void stableInstallSelectsHighestStableVersionRegardlessOfApiOrder() {
        UpdateReleasePolicy.ReleaseCandidate lower = release("v2.0.0", false, false);
        UpdateReleasePolicy.ReleaseCandidate higher = release("v10.0.0", false, false);
        assertSame(higher, UpdateReleasePolicy.newestEligibleRelease("1.9.9",
                Arrays.asList(lower, higher)));
    }

    @Test
    public void stableInstallDoesNotCrossIntoPrereleaseChannel() {
        assertNull(UpdateReleasePolicy.newestEligibleRelease("1.0.1", Arrays.asList(
                release("v9.0.0-beta.1", true, false))));
    }

    @Test
    public void scansCandidatesBeyondTheOriginalFirstTenEntries() {
        UpdateReleasePolicy.ReleaseCandidate older = release("v1.0.2", false, false);
        UpdateReleasePolicy.ReleaseCandidate later = release("v1.1.0", false, false);
        UpdateReleasePolicy.ReleaseCandidate[] entries = new UpdateReleasePolicy.ReleaseCandidate[12];
        Arrays.fill(entries, older);
        entries[11] = later;
        assertSame(later, UpdateReleasePolicy.newestEligibleRelease("1.0.1", Arrays.asList(entries)));
    }

    @Test
    public void prereleaseInstallAcceptsNewerPrereleaseAndStableRelease() {
        assertEquals("v1.2.0-beta.2", UpdateReleasePolicy.newestEligibleRelease("1.2.0-beta.1",
                Arrays.asList(release("v1.2.0-beta.2", true, false))).tagName);
        assertEquals("v1.2.0", UpdateReleasePolicy.newestEligibleRelease("1.2.0-beta.1",
                Arrays.asList(release("v1.2.0", false, false))).tagName);
    }

    @Test
    public void ignoresDraftsMalformedTagsAndChannelMetadataMismatches() {
        assertNull(UpdateReleasePolicy.newestEligibleRelease("1.0.0", Arrays.asList(
                release("v2.0.0", false, true),
                release("v3.0.0-beta", false, false),
                release("v4.0", false, false),
                release("v5.0.0-beta.01", true, false))));
    }

    @Test
    public void comparesPrereleaseNumericIdentifiersNumerically() {
        assertEquals("v1.0.0-beta.10", UpdateReleasePolicy.newestEligibleRelease("1.0.0-beta.2",
                Arrays.asList(release("v1.0.0-beta.10", true, false),
                        release("v1.0.0-beta.3", true, false))).tagName);
    }

    @Test
    public void comparesLexicalAndShorterPrereleaseIdentifiersAndStableReleasePrecedence() {
        assertEquals("v1.0.0-alpha.beta", UpdateReleasePolicy.newestEligibleRelease("1.0.0-alpha.10",
                Arrays.asList(release("v1.0.0-alpha.10", true, false),
                        release("v1.0.0-alpha.beta", true, false))).tagName);
        assertEquals("v1.0.0-alpha.1", UpdateReleasePolicy.newestEligibleRelease("1.0.0-alpha",
                Arrays.asList(release("v1.0.0-alpha.1", true, false))).tagName);
        assertEquals("v1.0.0", UpdateReleasePolicy.newestEligibleRelease("1.0.0-beta.2",
                Arrays.asList(release("v1.0.0-beta.10", true, false),
                        release("v1.0.0", false, false))).tagName);
    }

    @Test
    public void ignoresBuildMetadataAndRejectsWhitespaceVersions() {
        assertNull(UpdateReleasePolicy.newestEligibleRelease("1.0.0+local.1",
                Arrays.asList(release("v1.0.0+local.2", false, false))));
        assertNull(UpdateReleasePolicy.newestEligibleRelease(" 1.0.0 ",
                Arrays.asList(release("v1.0.1", false, false))));
        assertNull(UpdateReleasePolicy.newestEligibleRelease("1.0.0",
                Arrays.asList(release(" v1.0.1 ", false, false))));
    }

    private static UpdateReleasePolicy.ReleaseCandidate release(String tag, boolean prerelease, boolean draft) {
        return new UpdateReleasePolicy.ReleaseCandidate(tag, prerelease, draft, "notes");
    }
}
