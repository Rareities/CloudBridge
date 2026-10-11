package io.github.rareities.protonbridge.util;

import android.content.Context;
import android.net.wifi.WifiManager;
import android.os.PowerManager;

import androidx.annotation.Nullable;

/**
 * Requests CPU and Wi-Fi availability for an owned active rclone transfer.
 *
 * <p>Locks do not bypass Doze, foreground-service timeouts or scheduler quotas.
 * Callers release them when the owned operation ends; device measurements are
 * required to assess their effect on throughput and energy use.
 */
public final class TransferLocks {

    private final PowerManager.WakeLock wakeLock;
    private final WifiManager.WifiLock wifiLock;

    private TransferLocks(PowerManager.WakeLock wakeLock, WifiManager.WifiLock wifiLock) {
        this.wakeLock = wakeLock;
        this.wifiLock = wifiLock;
    }

    /**
     * Acquires a {@link PowerManager#PARTIAL_WAKE_LOCK} plus a high-performance
     * {@link WifiManager.WifiLock}. Both are held until {@link #release()} (callers MUST call it
     * from a {@code finally} block). Returns {@code null} if the locks could not be acquired
     * (the caller may still proceed; transfers will simply be subject to Doze throttling).
     */
    @Nullable
    public static TransferLocks acquire(Context context, String tag) {
        PowerManager.WakeLock wake = null;
        WifiManager.WifiLock wifi = null;
        try {
            PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
            if (pm != null) {
                wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ProtonBridge:" + tag);
                wake.setReferenceCounted(false);
                wake.acquire();
            }
            WifiManager wm = (WifiManager) context.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (wm != null) {
                wifi = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "ProtonBridge:" + tag);
                wifi.setReferenceCounted(false);
                wifi.acquire();
            }
            return new TransferLocks(wake, wifi);
        } catch (RuntimeException e) {
            FLog.e("TransferLocks", "acquire failed, releasing partial state", e);
            if (wake != null && wake.isHeld()) {
                try { wake.release(); } catch (RuntimeException ignored) { }
            }
            if (wifi != null && wifi.isHeld()) {
                try { wifi.release(); } catch (RuntimeException ignored) { }
            }
            return null;
        }
    }

    /** Releases both locks if held. Safe to call from a {@code finally} block. */
    public void release() {
        try {
            if (wakeLock != null && wakeLock.isHeld()) {
                wakeLock.release();
            }
        } catch (RuntimeException e) {
            FLog.e("TransferLocks", "release wakeLock", e);
        }
        try {
            if (wifiLock != null && wifiLock.isHeld()) {
                wifiLock.release();
            }
        } catch (RuntimeException e) {
            FLog.e("TransferLocks", "release wifiLock", e);
        }
    }
}
