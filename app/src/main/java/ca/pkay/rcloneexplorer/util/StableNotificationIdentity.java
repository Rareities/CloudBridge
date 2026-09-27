package ca.pkay.rcloneexplorer.util;

import androidx.annotation.NonNull;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** Collision-resistant stable identity for per-remote notifications and PendingIntents. */
public final class StableNotificationIdentity {

    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private StableNotificationIdentity() {
    }

    @NonNull
    public static String forRemote(@NonNull String remoteName) {
        if (remoteName.isEmpty()) {
            throw new IllegalArgumentException("remoteName must not be empty");
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(remoteName.getBytes(StandardCharsets.UTF_8));
            char[] encoded = new char[digest.length * 2];
            for (int i = 0; i < digest.length; i++) {
                int value = digest[i] & 0xff;
                encoded[i * 2] = HEX[value >>> 4];
                encoded[i * 2 + 1] = HEX[value & 0x0f];
            }
            return new String(encoded);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }
}
