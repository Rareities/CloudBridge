package ca.pkay.rcloneexplorer.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class ConfigEncryptionProbePolicyTest {

    @Test
    public void confirmedSuccessfulProbeIsUnencrypted() {
        assertEquals(ConfigEncryptionProbePolicy.Status.UNENCRYPTED,
                classify(NativeExecutionHandle.TerminalState.SUCCEEDED, 0, false));
    }

    @Test
    public void recognizedPasswordFailureOnConfirmedNonzeroExitIsEncrypted() {
        assertEquals(ConfigEncryptionProbePolicy.Status.ENCRYPTED,
                classify(NativeExecutionHandle.TerminalState.FAILED, 1, true));
    }

    @Test
    public void genericOrInconsistentFailuresRemainUnknown() {
        assertEquals(ConfigEncryptionProbePolicy.Status.UNKNOWN,
                classify(NativeExecutionHandle.TerminalState.FAILED, 1, false));
        assertEquals(ConfigEncryptionProbePolicy.Status.UNKNOWN,
                classify(NativeExecutionHandle.TerminalState.FAILED, 0, true));
        assertEquals(ConfigEncryptionProbePolicy.Status.UNKNOWN,
                classify(NativeExecutionHandle.TerminalState.SUCCEEDED, 1, true));
        assertEquals(ConfigEncryptionProbePolicy.Status.UNKNOWN,
                classify(NativeExecutionHandle.TerminalState.SUCCEEDED, null, false));
        assertEquals(ConfigEncryptionProbePolicy.Status.UNKNOWN,
                classify(null, 0, false));
    }

    @Test
    public void timeoutCancellationInterruptionAndUnconfirmedExitRemainUnknown() {
        for (NativeExecutionHandle.TerminalState state : new NativeExecutionHandle.TerminalState[] {
                NativeExecutionHandle.TerminalState.TIMED_OUT,
                NativeExecutionHandle.TerminalState.CANCELLED,
                NativeExecutionHandle.TerminalState.INTERRUPTED,
                NativeExecutionHandle.TerminalState.UNCONFIRMED
        }) {
            assertEquals(state.name(), ConfigEncryptionProbePolicy.Status.UNKNOWN,
                    classify(state, 1, true));
        }
    }

    @Test
    public void recognizesOnlyRcloneConfigPasswordFailureDiagnostic() {
        assertTrue(ConfigEncryptionProbePolicy.isRecognizedPasswordFailureLine(
                "ERROR : Couldn't decrypt configuration, most likely wrong password."));
        assertTrue(ConfigEncryptionProbePolicy.isRecognizedPasswordFailureLine(
                "ERROR : Couldn’t decrypt configuration, most likely wrong password."));
        assertFalse(ConfigEncryptionProbePolicy.isRecognizedPasswordFailureLine(
                "ERROR : connection failed: authentication required"));
        assertFalse(ConfigEncryptionProbePolicy.isRecognizedPasswordFailureLine(
                "ERROR : couldn't decrypt remote object"));
        assertFalse(ConfigEncryptionProbePolicy.isRecognizedPasswordFailureLine(null));
    }

    @Test
    public void legacyBooleanAdapterTreatsUnknownAsEncryptedOnlyForAnExistingConfig() {
        assertTrue(ConfigEncryptionProbePolicy.shouldTreatAsEncrypted(true,
                ConfigEncryptionProbePolicy.Status.UNKNOWN));
        assertTrue(ConfigEncryptionProbePolicy.shouldTreatAsEncrypted(true,
                ConfigEncryptionProbePolicy.Status.ENCRYPTED));
        assertFalse(ConfigEncryptionProbePolicy.shouldTreatAsEncrypted(true,
                ConfigEncryptionProbePolicy.Status.UNENCRYPTED));
        assertFalse(ConfigEncryptionProbePolicy.shouldTreatAsEncrypted(false,
                ConfigEncryptionProbePolicy.Status.UNKNOWN));
    }

    private static ConfigEncryptionProbePolicy.Status classify(
            NativeExecutionHandle.TerminalState state, Integer exitCode, boolean passwordFailure) {
        return ConfigEncryptionProbePolicy.classify(state, exitCode, passwordFailure);
    }
}
