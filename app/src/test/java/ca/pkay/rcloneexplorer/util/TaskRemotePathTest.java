package ca.pkay.rcloneexplorer.util;

import android.app.Application;
import androidx.test.core.app.ApplicationProvider;
import ca.pkay.rcloneexplorer.Items.RemoteItem;
import ca.pkay.rcloneexplorer.Rclone;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.assertEquals;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, application = Application.class)
public class TaskRemotePathTest {
    @Test public void wrappersKeepTheirConfiguredRootDespiteUnderlyingLocalType() {
        Application context = ApplicationProvider.getApplicationContext();
        RemoteItem alias = new RemoteItem("saved-alias", "local").setIsAlias(true);
        alias.setIsPathAlias(true);
        RemoteItem crypt = new RemoteItem("saved-crypt", "local").setIsCrypt(true);
        RemoteItem cache = new RemoteItem("saved-cache", "local").setIsCache(true);
        for (RemoteItem remote : new RemoteItem[]{alias, crypt, cache}) {
            assertEquals(remote.getName() + ":", Rclone.buildRemoteSection(remote, "//" + remote.getName(), context));
            assertEquals(remote.getName() + ":folder/a+b:c", Rclone.buildRemoteSection(remote, "folder/a+b:c", context));
        }
    }

    @Test public void bareLocalAndOrdinaryCloudRootsRetainTheirExistingSemantics() {
        Application context = ApplicationProvider.getApplicationContext();
        RemoteItem local = new RemoteItem("local-files", "local");
        String prefix = Rclone.getLocalRemotePathPrefix(local, context) + "/";
        assertEquals("local-files:" + prefix, Rclone.buildRemoteSection(local, "//local-files", context));
        RemoteItem drive = new RemoteItem("drive", "protondrive");
        assertEquals("drive:", Rclone.buildRemoteSection(drive, "//drive", context));
        assertEquals("drive:documents", Rclone.buildRemoteSection(drive, "documents", context));
    }
}
