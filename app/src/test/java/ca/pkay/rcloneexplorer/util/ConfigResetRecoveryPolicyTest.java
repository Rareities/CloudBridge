package ca.pkay.rcloneexplorer.util;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.concurrent.atomic.AtomicInteger;

public class ConfigResetRecoveryPolicyTest {
    @Test
    public void restoresAndFinalizesOnlyWhenTheOriginalConfigIsConfirmed() {
        AtomicInteger restores = new AtomicInteger();
        AtomicInteger finalizers = new AtomicInteger();

        boolean recovered = ConfigResetRecoveryPolicy.restoreIfConfigUnchanged(
                true, false, true,
                () -> { restores.incrementAndGet(); return true; },
                () -> { finalizers.incrementAndGet(); return true; });

        assertTrue(recovered);
        assertTrue(restores.get() == 1);
        assertTrue(finalizers.get() == 1);
    }

    @Test
    public void doesNotRestoreOrFinalizeWhenConfigStateIsUncertain() {
        AtomicInteger restores = new AtomicInteger();
        AtomicInteger finalizers = new AtomicInteger();

        assertFalse(ConfigResetRecoveryPolicy.restoreIfConfigUnchanged(
                false, false, true,
                () -> { restores.incrementAndGet(); return true; },
                () -> { finalizers.incrementAndGet(); return true; }));
        assertFalse(ConfigResetRecoveryPolicy.restoreIfConfigUnchanged(
                true, true, true,
                () -> { restores.incrementAndGet(); return true; },
                () -> { finalizers.incrementAndGet(); return true; }));
        assertTrue(restores.get() == 0);
        assertTrue(finalizers.get() == 0);
    }

    @Test
    public void failedSecretRestoreKeepsRevisionBarrierPending() {
        AtomicInteger finalizers = new AtomicInteger();

        assertFalse(ConfigResetRecoveryPolicy.restoreIfConfigUnchanged(
                true, false, true,
                () -> false,
                () -> { finalizers.incrementAndGet(); return true; }));
        assertTrue(finalizers.get() == 0);
    }

    @Test
    public void failedRevisionFinalizationIsNotReportedAsRecovery() {
        assertFalse(ConfigResetRecoveryPolicy.restoreIfConfigUnchanged(
                true, false, true,
                () -> true,
                () -> false));
    }
}
