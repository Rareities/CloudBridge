package ca.pkay.rcloneexplorer;

/** Pure classification for whether an RCD request may have started a remote mutation. */
final class RcdClaimFailurePolicy {
    private RcdClaimFailurePolicy() {
    }

    static boolean mayHaveStarted(RuntimeException failure) {
        if (failure instanceof RcloneRcd.RcdIOException) return true;
        if (failure instanceof RcloneRcd.RcdOpException) {
            int status = ((RcloneRcd.RcdOpException) failure).getStatus();
            return status < 0 || status >= 500;
        }
        // Unknown runtime failures are conservative: the HTTP request may already have
        // crossed the process boundary before response handling failed.
        return true;
    }
}
