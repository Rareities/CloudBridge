package ca.pkay.rcloneexplorer.workmanager;

import ca.pkay.rcloneexplorer.Items.FileItem;
import ca.pkay.rcloneexplorer.Items.RemoteItem;
import ca.pkay.rcloneexplorer.util.ConfigRevisionPolicy;
import ca.pkay.rcloneexplorer.util.RemoteDeleteTargetPolicy;

/** Immutable snapshot of the remote and item selected for a deferred delete. */
public final class PendingDeleteTarget {
    private static final String PLACEHOLDER_MOD_TIME = "1970-01-01T00:00:00Z";
    private static final String PLACEHOLDER_MIME_TYPE = "application/x-cloudbridge-delete-target";

    private final String remoteName;
    private final String configRevision;
    private final int remoteType;
    private final String remoteTypeReadable;
    private final String remoteDisplayName;
    private final boolean remoteCrypt;
    private final boolean remoteAlias;
    private final boolean remotePathAlias;
    private final boolean remoteCache;
    private final boolean remotePinned;
    private final boolean remoteDrawerPinned;
    private final String filePath;
    private final String fileName;
    private final boolean directory;

    private PendingDeleteTarget(RemoteItem remote, String configRevision, String filePath,
                                String fileName, boolean directory) {
        if (remote == null || !ConfigRevisionPolicy.isValidRevision(configRevision)
                || !RemoteDeleteTargetPolicy.isSafeTarget(filePath, fileName)) {
            throw new IllegalArgumentException("A delete target requires a remote snapshot, revision, path, and item name");
        }
        remoteName = remote.getName();
        this.configRevision = configRevision;
        remoteType = remote.getType();
        remoteTypeReadable = remote.getTypeReadable();
        remoteDisplayName = remote.getDisplayName();
        remoteCrypt = remote.isCrypt();
        remoteAlias = remote.isAlias();
        remotePathAlias = remote.isPathAlias();
        remoteCache = remote.isCache();
        remotePinned = remote.isPinned();
        remoteDrawerPinned = remote.isDrawerPinned();
        this.filePath = filePath;
        this.fileName = fileName;
        this.directory = directory;
    }

    public static PendingDeleteTarget capture(RemoteItem remote, String configRevision, FileItem file) {
        if (file == null) {
            throw new IllegalArgumentException("A delete target requires a file item");
        }
        return capture(remote, configRevision, file.getPath(), file.getName(), file.isDir());
    }

    /** Scalar entry point keeps target capture independently testable from Android UI/Parcel code. */
    public static PendingDeleteTarget capture(
            RemoteItem remote, String configRevision, String filePath, String fileName, boolean directory) {
        return new PendingDeleteTarget(remote, configRevision, filePath, fileName, directory);
    }

    public String getRemoteName() {
        return remoteName;
    }

    public String getConfigRevision() {
        return configRevision;
    }

    public String getFilePath() {
        return filePath;
    }

    public String getFileName() {
        return fileName;
    }

    public boolean isDirectory() {
        return directory;
    }

    /** Returns a fresh Parcelable so callers cannot mutate the captured remote snapshot. */
    public RemoteItem createRemoteItem() {
        RemoteItem copy = new RemoteItem(remoteName, remoteType, remoteTypeReadable)
                .setIsCrypt(remoteCrypt)
                .setIsAlias(remoteAlias)
                .setIsCache(remoteCache)
                .pin(remotePinned);
        copy.setIsPathAlias(remotePathAlias);
        copy.setDrawerPinned(remoteDrawerPinned);
        copy.setDisplayName(remoteDisplayName);
        return copy;
    }

    /**
     * Creates the minimal FileItem payload consumed by the delete worker and its success notice.
     * Its path, name, and directory bit are the captured values; unrelated listing metadata is
     * intentionally not carried into the deferred operation.
     */
    public FileItem createFileItem(RemoteItem remote) {
        return new FileItem(
                remote,
                filePath,
                fileName,
                0L,
                PLACEHOLDER_MOD_TIME,
                PLACEHOLDER_MIME_TYPE,
                directory,
                false);
    }
}
