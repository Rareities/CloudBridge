package io.github.x0b.safdav.saf;

import org.junit.Test;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class SafDavDiagnosticsTest {

    @Test
    public void mapsProviderFailuresToFixedCategoriesWithoutUsingMessages() {
        assertEquals("permission_denied", SafDavDiagnostics.category(
                new SecurityException("content://private/tree/canary")));
        assertEquals("not_found", SafDavDiagnostics.category(
                new FileNotFoundException("/private/provider/path/canary")));
        assertEquals("invalid_request", SafDavDiagnostics.category(
                new IllegalArgumentException("request URI canary")));
        assertEquals("unsupported", SafDavDiagnostics.category(
                new UnsupportedOperationException("provider detail canary")));
        assertEquals("io_failure", SafDavDiagnostics.category(
                new IOException("provider path canary")));
        assertEquals("invalid_state", SafDavDiagnostics.category(
                new IllegalStateException("provider response canary")));
        assertEquals("provider_failure", SafDavDiagnostics.category(
                new Exception("secret exception detail canary")));
    }

    @Test
    public void categoriesAreBoundedAndAllowlisted() {
        Set<String> allowedCategories = new HashSet<>(Arrays.asList(
                "permission_denied", "not_found", "invalid_request", "unsupported",
                "io_failure", "invalid_state", "provider_failure"));
        String[] categories = {
                SafDavDiagnostics.category(new SecurityException()),
                SafDavDiagnostics.category(new FileNotFoundException()),
                SafDavDiagnostics.category(new IllegalArgumentException()),
                SafDavDiagnostics.category(new UnsupportedOperationException()),
                SafDavDiagnostics.category(new IOException()),
                SafDavDiagnostics.category(new IllegalStateException()),
                SafDavDiagnostics.category(new Exception()),
                SafDavDiagnostics.category(null)
        };
        for (String category : categories) {
            assertTrue(category.length() <= 24);
            assertTrue(allowedCategories.contains(category));
        }
    }
}
