package de.schuelken.cloudbridge.updates.workmanager;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Fork-owned release links and deterministic version/channel selection for notifications. */
public final class UpdateReleasePolicy {
    public static final String REPOSITORY_OWNER = "Rareities";
    public static final String REPOSITORY_NAME = "CloudBridge";
    public static final String REPOSITORY_URL = "https://github.com/Rareities/CloudBridge";
    public static final String RELEASES_INDEX_URL = REPOSITORY_URL + "/releases";
    public static final int RELEASES_PER_PAGE = 100;
    public static final int MAX_RELEASE_PAGES = 5;
    private static final String RELEASES_API_BASE_URL =
            "https://api.github.com/repos/" + REPOSITORY_OWNER + "/" + REPOSITORY_NAME + "/releases";
    public static final String RELEASES_API_URL =
            RELEASES_API_BASE_URL + "?per_page=" + RELEASES_PER_PAGE + "&page=1";
    public static final String LATEST_RELEASE_URL = RELEASES_INDEX_URL + "/latest";
    private static final String RELEASE_TAG_URL_PREFIX = RELEASES_INDEX_URL + "/tag/";
    private static final Pattern VERSION_PATTERN = Pattern.compile(
            "^[vV]?((?:0|[1-9][0-9]*))\\.((?:0|[1-9][0-9]*))\\.((?:0|[1-9][0-9]*))"
                    + "(?:-([0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*))?"
                    + "(?:\\+([0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*))?$");

    private UpdateReleasePolicy() {}

    public static String releasesApiUrl(int page) {
        if (page < 1 || page > MAX_RELEASE_PAGES) {
            throw new IllegalArgumentException("Release page is outside the bounded scan");
        }
        return RELEASES_API_BASE_URL + "?per_page=" + RELEASES_PER_PAGE + "&page=" + page;
    }

    /** Returns the exact validated release page, or the safe release index for invalid stored data. */
    public static String releasePageUrl(String tagName) {
        return SemanticVersion.parse(tagName) == null
                ? RELEASES_INDEX_URL
                : RELEASE_TAG_URL_PREFIX + tagName;
    }

    /** Chooses the highest valid release newer than the installed version. */
    public static ReleaseCandidate newestEligibleRelease(
            String installedVersion,
            Iterable<ReleaseCandidate> releases
    ) {
        SemanticVersion installed = SemanticVersion.parse(installedVersion);
        if (installed == null || releases == null) return null;

        ReleaseCandidate newest = null;
        SemanticVersion newestVersion = null;
        for (ReleaseCandidate release : releases) {
            if (release == null || release.draft || release.tagName == null) continue;
            SemanticVersion candidate = SemanticVersion.parse(release.tagName);
            if (candidate == null) continue;

            // Stable installs stay on the stable channel. A pre-release install opts into
            // newer pre-releases, while a stable release can still supersede that pre-release.
            boolean tagIsPrerelease = !candidate.prerelease.isEmpty();
            if (tagIsPrerelease != release.prerelease) continue;
            if (tagIsPrerelease && installed.prerelease.isEmpty()) continue;
            if (candidate.compareTo(installed) <= 0) continue;
            if (newestVersion == null || candidate.compareTo(newestVersion) > 0) {
                newest = release;
                newestVersion = candidate;
            }
        }
        return newest;
    }

    /** Parsed GitHub release metadata. The changelog is kept only for the existing preference. */
    public static final class ReleaseCandidate {
        public final String tagName;
        public final boolean prerelease;
        public final boolean draft;
        public final String changelog;

        public ReleaseCandidate(String tagName, boolean prerelease, boolean draft, String changelog) {
            this.tagName = tagName;
            this.prerelease = prerelease;
            this.draft = draft;
            this.changelog = changelog;
        }
    }

    private static final class SemanticVersion implements Comparable<SemanticVersion> {
        private final BigInteger major;
        private final BigInteger minor;
        private final BigInteger patch;
        private final List<String> prerelease;

        private SemanticVersion(BigInteger major, BigInteger minor, BigInteger patch, List<String> prerelease) {
            this.major = major;
            this.minor = minor;
            this.patch = patch;
            this.prerelease = prerelease;
        }

        private static SemanticVersion parse(String value) {
            if (value == null) return null;
            Matcher matcher = VERSION_PATTERN.matcher(value);
            if (!matcher.matches()) return null;

            String prereleaseText = matcher.group(4);
            List<String> prerelease = new ArrayList<>();
            if (prereleaseText != null) {
                for (String identifier : prereleaseText.split("\\.", -1)) {
                    if (identifier.isEmpty()) return null;
                    if (identifier.matches("[0-9]+") && identifier.length() > 1 && identifier.charAt(0) == '0') {
                        return null;
                    }
                    prerelease.add(identifier);
                }
            }
            return new SemanticVersion(
                    new BigInteger(matcher.group(1)),
                    new BigInteger(matcher.group(2)),
                    new BigInteger(matcher.group(3)),
                    prerelease);
        }

        @Override
        public int compareTo(SemanticVersion other) {
            int core = major.compareTo(other.major);
            if (core == 0) core = minor.compareTo(other.minor);
            if (core == 0) core = patch.compareTo(other.patch);
            if (core != 0) return core;

            if (prerelease.isEmpty() || other.prerelease.isEmpty()) {
                if (prerelease.isEmpty() == other.prerelease.isEmpty()) return 0;
                return prerelease.isEmpty() ? 1 : -1;
            }

            int shared = Math.min(prerelease.size(), other.prerelease.size());
            for (int index = 0; index < shared; index++) {
                String left = prerelease.get(index);
                String right = other.prerelease.get(index);
                boolean leftNumeric = left.matches("[0-9]+");
                boolean rightNumeric = right.matches("[0-9]+");
                int comparison;
                if (leftNumeric && rightNumeric) {
                    comparison = new BigInteger(left).compareTo(new BigInteger(right));
                } else if (leftNumeric != rightNumeric) {
                    comparison = leftNumeric ? -1 : 1;
                } else {
                    comparison = left.compareTo(right);
                }
                if (comparison != 0) return comparison;
            }
            return Integer.compare(prerelease.size(), other.prerelease.size());
        }
    }
}
