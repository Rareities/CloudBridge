package ca.pkay.rcloneexplorer.Database;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class RunStateTest {

    @Test
    public void terminalAndActiveStatesAreExplicit() {
        assertTrue(RunState.SUCCESS.getTerminal());
        assertTrue(RunState.FAILED.getTerminal());
        assertFalse(RunState.RECOVERY_REQUIRED.getTerminal());
        assertTrue(RunState.Companion.getActiveValues().contains(RunState.QUEUED));
        assertTrue(RunState.Companion.getActiveValues().contains(RunState.RUNNING));
    }

    @Test
    public void unknownWireValuesFailClosedToRecovery() {
        assertTrue(RunState.Companion.fromWireValue("future-state") == RunState.RECOVERY_REQUIRED);
        assertTrue(ProfileReadiness.Companion.fromWireValue("future-readiness") == ProfileReadiness.RECOVERY_REQUIRED);
        assertTrue(ProfileMode.Companion.fromWireValue("future-mode") == ProfileMode.UNKNOWN);
    }
}
