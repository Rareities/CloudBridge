package ca.pkay.rcloneexplorer.util;

/** Restricts external share inputs to third-party content-provider URIs. */
public final class IncomingShareUriPolicy {

    private IncomingShareUriPolicy() {}

    public static boolean allows(String scheme, String authority, String applicationId) {
        if (scheme == null || !"content".equalsIgnoreCase(scheme)
                || authority == null || authority.trim().isEmpty()
                || applicationId == null || applicationId.trim().isEmpty()) {
            return false;
        }

        String normalizedAuthority = authority.trim().toLowerCase(java.util.Locale.ROOT);
        String ownPackage = applicationId.trim().toLowerCase(java.util.Locale.ROOT);
        // An exported activity uses this app's UID when opening URIs. It therefore has full
        // access to our own providers even when the external caller supplied no URI grant.
        return !normalizedAuthority.equals(ownPackage)
                && !normalizedAuthority.equals(ownPackage + ".fileprovider")
                && !normalizedAuthority.equals(ownPackage + ".vcp")
                && !normalizedAuthority.equals(ownPackage + ".androidx-startup");
    }

    /**
     * Require the incoming intent's read-grant flag and an effective Android read permission.
     * Android exposes the permission check at UID scope, not the provenance of each temporary
     * grant. Do not reject a valid incoming grant merely because SAF permission was persisted
     * earlier; the caller must still obtain the URI only from this incoming share's stream extra.
     */
    public static boolean allows(String scheme, String authority, String applicationId,
                                 boolean incomingReadGrantFlag, boolean appCanReadUri) {
        return allows(scheme, authority, applicationId)
                && incomingReadGrantFlag
                && appCanReadUri;
    }
}
