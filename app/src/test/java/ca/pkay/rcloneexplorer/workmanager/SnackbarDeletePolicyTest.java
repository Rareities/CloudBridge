package ca.pkay.rcloneexplorer.workmanager;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import ca.pkay.rcloneexplorer.Items.FileItem;
import ca.pkay.rcloneexplorer.Items.RemoteItem;
import ca.pkay.rcloneexplorer.util.ConfigRevisionPolicy;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

public class SnackbarDeletePolicyTest {
    private static final int DISMISS_EVENT_SWIPE = 0;
    private static final int DISMISS_EVENT_ACTION = 1;
    private static final int DISMISS_EVENT_TIMEOUT = 2;
    private static final int DISMISS_EVENT_MANUAL = 3;
    private static final int DISMISS_EVENT_CONSECUTIVE = 4;
    private static final String CONFIG_REVISION = "00000000-0000-0000-0000-000000000001";
    private static final String REASSIGNED_CONFIG_REVISION = "00000000-0000-0000-0000-000000000002";

    @Test
    public void onlySnackbarTimeoutAuthorizesQueueing() {
        assertTrue(SnackbarDeletePolicy.isTimeout(
                DISMISS_EVENT_TIMEOUT, DISMISS_EVENT_TIMEOUT));
        assertFalse(SnackbarDeletePolicy.isTimeout(
                DISMISS_EVENT_SWIPE, DISMISS_EVENT_TIMEOUT));
        assertFalse(SnackbarDeletePolicy.isTimeout(
                DISMISS_EVENT_ACTION, DISMISS_EVENT_TIMEOUT));
        assertFalse(SnackbarDeletePolicy.isTimeout(
                DISMISS_EVENT_MANUAL, DISMISS_EVENT_TIMEOUT));
        assertFalse(SnackbarDeletePolicy.isTimeout(
                DISMISS_EVENT_CONSECUTIVE, DISMISS_EVENT_TIMEOUT));
        assertFalse(SnackbarDeletePolicy.isTimeout(-1, DISMISS_EVENT_TIMEOUT));
        assertTrue(SnackbarDeletePolicy.shouldEnqueue(
                DISMISS_EVENT_TIMEOUT, DISMISS_EVENT_TIMEOUT, CONFIG_REVISION));
        assertFalse(SnackbarDeletePolicy.shouldEnqueue(
                DISMISS_EVENT_TIMEOUT, DISMISS_EVENT_TIMEOUT, null));
    }

    @Test
    public void sameNameRemoteReassignmentIsFencedByCapturedConfigRevision() {
        RemoteItem confirmedRemote = new RemoteItem("shared-name", RemoteItem.LOCAL, "local");
        confirmedRemote.setConfigRevision(CONFIG_REVISION);
        PendingDeleteTarget target = PendingDeleteTarget.capture(
                confirmedRemote, confirmedRemote.getConfigRevision(),
                "/confirmed/item.txt", "item.txt", false);
        RemoteItem reassignedRemote = new RemoteItem(
                "shared-name", RemoteItem.GOOGLE_DRIVE, "drive");

        assertEquals(confirmedRemote.getName(), reassignedRemote.getName());
        assertFalse(confirmedRemote.getType() == reassignedRemote.getType());
        assertEquals("shared-name", target.createRemoteItem().getName());
        assertEquals(CONFIG_REVISION, target.getConfigRevision());
        assertFalse(ConfigRevisionPolicy.matches(
                target.getConfigRevision(), REASSIGNED_CONFIG_REVISION));
        assertTrue(SnackbarDeletePolicy.shouldEnqueue(
                DISMISS_EVENT_TIMEOUT, DISMISS_EVENT_TIMEOUT, target.getConfigRevision()));
    }

    @Test
    public void immutableQueueSnapshotCannotBeRetargetedByListChanges() {
        List<String> selected = new ArrayList<>();
        selected.add("remote-a:/first.txt");

        List<String> queued = SnackbarDeletePolicy.immutableSnapshot(selected);
        selected.clear();
        selected.add("remote-b:/different.txt");

        assertEquals(1, queued.size());
        assertEquals("remote-a:/first.txt", queued.get(0));
        assertThrows(UnsupportedOperationException.class, () -> queued.add("other:/item"));
    }

    @Test
    public void pendingTargetKeepsCapturedRemotePathAndItemAfterSourceChanges() {
        RemoteItem remote = new RemoteItem("origin", RemoteItem.LOCAL, "local")
                .setIsCrypt(true)
                .setIsAlias(true)
                .setIsCache(true)
                .pin(true);
        remote.setIsPathAlias(true);
        remote.setDrawerPinned(true);
        remote.setDisplayName("Original display name");
        remote.setConfigRevision(CONFIG_REVISION);

        PendingDeleteTarget target = PendingDeleteTarget.capture(
                remote, remote.getConfigRevision(), "/captured/folder/report.txt", "report.txt", false);

        remote.setIsCrypt(false);
        remote.setIsAlias(false);
        remote.setIsPathAlias(false);
        remote.setIsCache(false);
        remote.pin(false);
        remote.setDrawerPinned(false);
        remote.setDisplayName("Changed display name");

        assertEquals("origin", target.getRemoteName());
        assertEquals(CONFIG_REVISION, target.getConfigRevision());
        assertEquals("/captured/folder/report.txt", target.getFilePath());
        assertEquals("report.txt", target.getFileName());
        assertFalse(target.isDirectory());

        RemoteItem queuedRemote = target.createRemoteItem();
        assertEquals("origin", queuedRemote.getName());
        assertEquals(RemoteItem.LOCAL, queuedRemote.getType());
        assertEquals("Original display name", queuedRemote.getDisplayName());
        assertTrue(queuedRemote.isCrypt());
        assertTrue(queuedRemote.isAlias());
        assertTrue(queuedRemote.isPathAlias());
        assertTrue(queuedRemote.isCache());
        assertTrue(queuedRemote.isPinned());
        assertTrue(queuedRemote.isDrawerPinned());

        RemoteItem callerMutation = target.createRemoteItem();
        assertNotSame(queuedRemote, callerMutation);
        callerMutation.setDisplayName("Caller mutation");
        assertEquals("Original display name", target.createRemoteItem().getDisplayName());
    }

    @Test
    public void queuedFileItemContainsCapturedPathNameAndDirectoryKind() {
        RemoteItem remote = new RemoteItem("drive", RemoteItem.GOOGLE_DRIVE, "drive");
        PendingDeleteTarget fileTarget = PendingDeleteTarget.capture(
                remote, CONFIG_REVISION, "/vault/a.md", "a.md", false);
        PendingDeleteTarget directoryTarget = PendingDeleteTarget.capture(
                remote, CONFIG_REVISION, "/vault/archive", "archive", true);

        FileItem file = fileTarget.createFileItem(fileTarget.createRemoteItem());
        FileItem directory = directoryTarget.createFileItem(directoryTarget.createRemoteItem());

        assertEquals("/vault/a.md", file.getPath());
        assertEquals("a.md", file.getName());
        assertFalse(file.isDir());
        assertEquals("/vault/archive", directory.getPath());
        assertEquals("archive", directory.getName());
        assertTrue(directory.isDir());
    }

    @Test
    public void capturesTheEffectivePathForBothStartAtRootModes() {
        RemoteItem remote = new RemoteItem("drive", RemoteItem.GOOGLE_DRIVE, "drive");
        FileItem rooted = new FileItem(remote, "folder/a.md", "a.md", 0L,
                "not-a-time", "text/markdown", false, true);
        FileItem relative = new FileItem(remote, "folder/a.md", "a.md", 0L,
                "not-a-time", "text/markdown", false, false);

        PendingDeleteTarget rootedTarget = PendingDeleteTarget.capture(
                remote, CONFIG_REVISION, rooted);
        PendingDeleteTarget relativeTarget = PendingDeleteTarget.capture(
                remote, CONFIG_REVISION, relative);

        assertEquals("/folder/a.md", rootedTarget.getFilePath());
        assertEquals("folder/a.md", relativeTarget.getFilePath());
    }
}
