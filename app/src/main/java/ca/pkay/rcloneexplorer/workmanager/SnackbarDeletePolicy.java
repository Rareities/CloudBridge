package ca.pkay.rcloneexplorer.workmanager;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import ca.pkay.rcloneexplorer.util.ConfigRevisionPolicy;

/** Pure decision and snapshot helpers for the deferred delete Snackbar boundary. */
public final class SnackbarDeletePolicy {
    private SnackbarDeletePolicy() {
    }

    public static boolean hasConfigRevision(String revision) {
        return ConfigRevisionPolicy.isValidRevision(revision);
    }

    public static boolean isTimeout(int dismissEvent, int timeoutEvent) {
        return dismissEvent == timeoutEvent;
    }

    public static boolean shouldEnqueue(int dismissEvent, int timeoutEvent, String revision) {
        return hasConfigRevision(revision) && isTimeout(dismissEvent, timeoutEvent);
    }

    public static <T> List<T> immutableSnapshot(List<? extends T> targets) {
        if (targets == null || targets.isEmpty()) {
            return Collections.emptyList();
        }
        return Collections.unmodifiableList(new ArrayList<>(targets));
    }
}
