package io.github.rareities.protonbridge.util;

import android.content.Context;
import android.os.PowerManager;
import androidx.annotation.Nullable;

/** CPU ownership for active service/RC transfers outside WorkManager.
 * Wi-Fi power saving remains under Android's control. This does not bypass Doze.
 * WorkManager foreground workers already have framework-owned CPU wake locks.
 */
public final class TransferLocks {
    private final PowerManager.WakeLock wakeLock;
    private TransferLocks(PowerManager.WakeLock wakeLock) { this.wakeLock = wakeLock; }

    /** Callers must release in their terminal/cleanup path, including cancellation. */
    @Nullable public static TransferLocks acquire(Context context, String tag) {
        PowerManager.WakeLock wake = null;
        try {
            PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
            if (pm == null) return null;
            wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ProtonBridge:" + tag);
            wake.setReferenceCounted(false);
            wake.acquire();
            return new TransferLocks(wake);
        } catch (RuntimeException e) {
            if (wake != null && wake.isHeld()) {
                try { wake.release(); } catch (RuntimeException ignored) { }
            }
            FLog.w("TransferLocks", "CPU availability lock could not be acquired");
            return null;
        }
    }

    public void release() {
        try { if (wakeLock.isHeld()) wakeLock.release(); }
        catch (RuntimeException e) { FLog.w("TransferLocks", "CPU availability lock could not be released"); }
    }
}
