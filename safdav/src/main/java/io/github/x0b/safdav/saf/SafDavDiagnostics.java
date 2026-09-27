package io.github.x0b.safdav.saf;

import java.io.FileNotFoundException;
import java.io.IOException;

/** Fixed, path-free diagnostic categories for SAF provider failures. */
final class SafDavDiagnostics {

    private SafDavDiagnostics() {
    }

    static String category(Throwable failure) {
        if (failure instanceof SecurityException) {
            return "permission_denied";
        }
        if (failure instanceof FileNotFoundException) {
            return "not_found";
        }
        if (failure instanceof IllegalArgumentException) {
            return "invalid_request";
        }
        if (failure instanceof UnsupportedOperationException) {
            return "unsupported";
        }
        if (failure instanceof IOException) {
            return "io_failure";
        }
        if (failure instanceof IllegalStateException) {
            return "invalid_state";
        }
        return "provider_failure";
    }
}
