package ca.pkay.rcloneexplorer.util;

import java.util.Locale;

/** Classifies the result of the app's non-interactive config decryption probe. */
public final class ConfigEncryptionProbePolicy {

    public enum Status {
        ENCRYPTED,
        UNENCRYPTED,
        UNKNOWN
    }

    private static final String PASSWORD_FAILURE =
            "couldn't decrypt configuration, most likely wrong password";

    private ConfigEncryptionProbePolicy() {
    }

    /**
     * Only a successful, zero-exit probe proves the config is unencrypted. A non-zero exit proves
     * encryption only when rclone emitted its specific config password failure diagnostic.
     */
    public static Status classify(NativeExecutionHandle.TerminalState state, Integer exitCode,
                                  boolean passwordFailure) {
        if (state == NativeExecutionHandle.TerminalState.SUCCEEDED
                && exitCode != null && exitCode == 0) {
            return Status.UNENCRYPTED;
        }
        if (state == NativeExecutionHandle.TerminalState.FAILED
                && exitCode != null && exitCode != 0 && passwordFailure) {
            return Status.ENCRYPTED;
        }
        return Status.UNKNOWN;
    }

    /** Matches rclone's config-decryption diagnostic without retaining or logging raw stderr. */
    public static boolean isRecognizedPasswordFailureLine(String line) {
        if (line == null) return false;
        String normalized = line.toLowerCase(Locale.ROOT).replace('\u2019', '\'');
        return normalized.contains(PASSWORD_FAILURE);
    }

    /** Existing boolean callers must gate on uncertainty whenever a config file exists. */
    public static boolean shouldTreatAsEncrypted(boolean configFileExists, Status status) {
        return configFileExists && status != Status.UNENCRYPTED;
    }
}
