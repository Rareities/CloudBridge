package ca.pkay.rcloneexplorer.util;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class IncomingShareUriPolicyTest {

    private static final String APP_ID = "de.schuelken.cloudbridge";

    @Test
    public void acceptsThirdPartyUriOnlyWithShareReadGrant() {
        assertTrue(IncomingShareUriPolicy.allows(
                "content", "com.example.files", APP_ID, true, true));
    }

    @Test
    public void rejectsMissingIntentGrantEvenWhenAppHasAmbientReadAccess() {
        assertFalse(IncomingShareUriPolicy.allows(
                "content", "com.example.files", APP_ID, false, true));
    }

    @Test
    public void rejectsUriWithoutEffectiveReadPermission() {
        assertFalse(IncomingShareUriPolicy.allows(
                "content", "com.example.files", APP_ID, true, false));
    }

    @Test
    public void doesNotRejectValidIncomingShareBecauseSafPermissionWasPersistedEarlier() {
        assertTrue(IncomingShareUriPolicy.allows(
                "content", "com.example.files", APP_ID, true, true));
    }

    @Test
    public void stillRejectsOwnProviderEvenWithGrantFlags() {
        assertFalse(IncomingShareUriPolicy.allows(
                "content", APP_ID + ".vcp", APP_ID, true, true));
    }

    @Test
    public void rejectsNonContentSchemesEvenWithGrantFlags() {
        assertFalse(IncomingShareUriPolicy.allows(
                "file", "com.example.files", APP_ID, true, true));
    }
}
