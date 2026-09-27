package ca.pkay.rcloneexplorer.workmanager;

import java.io.File;
import java.util.UUID;

/** Creates a bounded WorkManager tag for reconciling one staged-share invocation. */
public final class ShareUploadBatchTagPolicy {
    public static final String PREFIX = "share_upload_batch:";

    private ShareUploadBatchTagPolicy() {
    }

    public static String forStagingDirectory(File directory) {
        if (directory == null) {
            throw new NullPointerException("staging directory is required");
        }
        String name = directory.getName();
        if (!isOwnedShareDirectoryName(name)) {
            throw new IllegalArgumentException("staging directory is not invocation-owned");
        }
        return PREFIX + name;
    }

    public static boolean isValidTag(String tag) {
        return tag != null && tag.startsWith(PREFIX) &&
                isOwnedShareDirectoryName(tag.substring(PREFIX.length()));
    }

    public static boolean isTagForStagingDirectory(String tag, File directory) {
        return isValidTag(tag) && directory != null &&
                tag.equals(PREFIX + directory.getName());
    }

    private static boolean isOwnedShareDirectoryName(String name) {
        if (name == null || !name.startsWith("share-")) {
            return false;
        }
        try {
            String id = name.substring("share-".length());
            return UUID.fromString(id).toString().equals(id);
        } catch (IllegalArgumentException invalidUuid) {
            return false;
        }
    }
}
