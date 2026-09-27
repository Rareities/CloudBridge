package ca.pkay.rcloneexplorer.util;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ProviderMetadataPolicyTest {
    @Test
    public void acceptsNonBlankStringNames() {
        assertTrue(ProviderMetadataPolicy.hasValidProviderName("s3"));
        assertTrue(ProviderMetadataPolicy.hasValidProviderName("Proton Drive"));
        assertTrue(ProviderMetadataPolicy.hasValidProviderName(" 雲端 "));
    }

    @Test
    public void rejectsMissingBlankAndNonStringNames() {
        assertFalse(ProviderMetadataPolicy.hasValidProviderName(null));
        assertFalse(ProviderMetadataPolicy.hasValidProviderName(""));
        assertFalse(ProviderMetadataPolicy.hasValidProviderName(" \t\r\n"));
        assertFalse(ProviderMetadataPolicy.hasValidProviderName(12));
    }
}
