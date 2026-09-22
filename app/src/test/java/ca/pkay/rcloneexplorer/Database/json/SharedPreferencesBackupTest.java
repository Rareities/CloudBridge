package ca.pkay.rcloneexplorer.Database.json;

import org.json.JSONException;
import org.junit.Test;

public class SharedPreferencesBackupTest {

    @Test
    public void legacyBooleanThemeIsAccepted() throws Exception {
        SharedPreferencesBackup.validate("{\"isDarkTheme\":true}");
    }

    @Test(expected = JSONException.class)
    public void invalidProxyPortIsRejectedBeforeEditorCreation() throws Exception {
        SharedPreferencesBackup.validate("{\"proxyPort\":70000}");
    }

    @Test(expected = JSONException.class)
    public void invalidTransferCountIsRejected() throws Exception {
        SharedPreferencesBackup.validate("{\"transfers\":\"not-a-number\"}");
    }
}
