package ca.pkay.rcloneexplorer.util;

/** Fail-closed validation for an item path before a destructive rclone delete command. */
public final class RemoteDeleteTargetPolicy {
    private RemoteDeleteTargetPolicy() {
    }

    public static boolean isSafeTarget(String path, String name) {
        if (path == null || path.isEmpty() || name == null || name.isEmpty()
                || path.indexOf('\u0000') >= 0 || name.indexOf('\u0000') >= 0
                || name.indexOf('/') >= 0) {
            return false;
        }

        int end = path.length();
        while (end > 0 && path.charAt(end - 1) == '/') {
            end--;
        }
        if (end == 0) {
            return false; // Never allow the remote root as a deferred purge/delete target.
        }

        String targetPath = path.substring(0, end);
        int segmentStart = 0;
        for (int index = 0; index <= targetPath.length(); index++) {
            if (index == targetPath.length() || targetPath.charAt(index) == '/') {
                String segment = targetPath.substring(segmentStart, index);
                if (".".equals(segment) || "..".equals(segment)) {
                    return false;
                }
                segmentStart = index + 1;
            }
        }

        int finalSeparator = targetPath.lastIndexOf('/');
        String finalSegment = targetPath.substring(finalSeparator + 1);
        return !finalSegment.isEmpty() && finalSegment.equals(name);
    }
}
