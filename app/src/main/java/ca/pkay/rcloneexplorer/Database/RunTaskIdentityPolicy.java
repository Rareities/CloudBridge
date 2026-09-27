package ca.pkay.rcloneexplorer.Database;

/** Pure matching rules for binding a WorkManager task payload to its immutable run snapshot. */
public final class RunTaskIdentityPolicy {
    private RunTaskIdentityPolicy() {
    }

    public static boolean matchesLegacyTask(
            Long profileTaskId,
            long requestedTaskId,
            String profileFingerprint,
            String runFingerprint,
            String requestedTaskFingerprint) {
        return profileTaskId != null
                && profileTaskId == requestedTaskId
                && same(profileFingerprint, runFingerprint)
                && same(runFingerprint, requestedTaskFingerprint);
    }

    public static boolean matchesEphemeralTask(
            Long profileTaskId,
            String profileId,
            String runProfileId,
            String profileFingerprint,
            String runFingerprint,
            String requestedProfileId,
            String requestedFingerprint) {
        return profileTaskId == null
                && same(profileId, runProfileId)
                && same(runProfileId, requestedProfileId)
                && same(profileFingerprint, runFingerprint)
                && same(runFingerprint, requestedFingerprint);
    }

    private static boolean same(String left, String right) {
        return left != null && right != null && left.equals(right);
    }
}
