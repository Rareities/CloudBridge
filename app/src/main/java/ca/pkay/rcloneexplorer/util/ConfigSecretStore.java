package ca.pkay.rcloneexplorer.util;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.UUID;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * Stores the rclone config password as Keystore-wrapped ciphertext.
 *
 * The password is deliberately not part of the ordinary backup JSON or a log
 * record. A Keystore invalidation leaves the ciphertext in place and makes the
 * caller require explicit recovery instead of clearing a usable encrypted
 * config. Replacing a config rotates its generation before publishing the new
 * file; ciphertext is retained but cannot be loaded under the new generation.
 */
public final class ConfigSecretStore {

    private static final String KEYSTORE = "AndroidKeyStore";
    private static final String KEY_ALIAS = "cloudbridge.rclone.config.password.v1";
    private static final String PREFS = "cloudbridge.config-secret.v1";
    private static final String CIPHERTEXT = "ciphertext";
    private static final String GENERATION = "generation";
    private static final String BOUND_GENERATION = "bound_generation";
    private static final String LEGACY_GENERATION = "legacy-unbound";
    private static final int IV_BYTES = 12;
    private static final Object STORE_LOCK = new Object();

    private final Context context;
    private final SecureRandom random = new SecureRandom();

    public ConfigSecretStore(Context context) {
        this.context = context.getApplicationContext();
    }

    public BoundSecret load() throws GeneralSecurityException {
        SharedPreferences preferences = preferences();
        if (!hasUsableCiphertext(preferences)) {
            return null;
        }
        String encoded = preferences.getString(CIPHERTEXT, null);
        String persistedGeneration = preferences.getString(GENERATION, null);
        String generation = persistedGeneration == null
                ? LEGACY_GENERATION : persistedGeneration;
        byte[] packed;
        try {
            packed = Base64.decode(encoded, Base64.NO_WRAP | Base64.URL_SAFE);
        } catch (IllegalArgumentException e) {
            throw new GeneralSecurityException("Stored config secret is malformed", e);
        }
        if (packed.length <= IV_BYTES) {
            throw new GeneralSecurityException("Stored config secret is incomplete");
        }

        byte[] iv = new byte[IV_BYTES];
        byte[] ciphertext = new byte[packed.length - IV_BYTES];
        System.arraycopy(packed, 0, iv, 0, IV_BYTES);
        System.arraycopy(packed, IV_BYTES, ciphertext, 0, ciphertext.length);

        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, iv));
        // Historical ciphertext was not generation-bound. Keep it readable until a config
        // replacement explicitly rotates the generation; all newly saved secrets are bound.
        if (!LEGACY_GENERATION.equals(generation)) {
            cipher.updateAAD(generation.getBytes(StandardCharsets.UTF_8));
        }
        return new BoundSecret(new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8), generation);
    }

    public BoundSecret save(String password, String expectedGeneration) throws GeneralSecurityException {
        if (password == null || password.isEmpty()) {
            throw new GeneralSecurityException("Config password is empty");
        }
        synchronized (STORE_LOCK) {
            SharedPreferences preferences = preferences();
            String persistedGeneration = preferences.getString(GENERATION, null);
            String currentGeneration = persistedGeneration == null
                    ? LEGACY_GENERATION : persistedGeneration;
            if (expectedGeneration == null || !expectedGeneration.equals(currentGeneration)) {
                throw new GeneralSecurityException("Config changed while its password was being verified");
            }
            // Rotate even on an explicit re-unlock so other in-memory instances refresh the
            // newly verified password rather than continuing to use an older one.
            String generation = UUID.randomUUID().toString();
            byte[] iv = new byte[IV_BYTES];
            random.nextBytes(iv);

            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key(), new GCMParameterSpec(128, iv));
            cipher.updateAAD(generation.getBytes(StandardCharsets.UTF_8));
            byte[] ciphertext = cipher.doFinal(password.getBytes(StandardCharsets.UTF_8));
            byte[] packed = new byte[iv.length + ciphertext.length];
            System.arraycopy(iv, 0, packed, 0, iv.length);
            System.arraycopy(ciphertext, 0, packed, iv.length, ciphertext.length);

            boolean committed = preferences.edit()
                    .putString(CIPHERTEXT, Base64.encodeToString(packed, Base64.NO_WRAP | Base64.URL_SAFE))
                    .putString(GENERATION, generation)
                    .putString(BOUND_GENERATION, generation)
                    .commit();
            if (!committed) {
                throw new GeneralSecurityException("Unable to persist config secret");
            }
            return new BoundSecret(password, generation);
        }
    }

    /**
     * Durably revokes every saved/in-memory password before a config replacement starts.
     * If this commit fails, callers must not replace the active config.
     */
    public InvalidationToken invalidateForConfigReplacement() throws IOException {
        return invalidatePersistedSecret(preferences(), UUID.randomUUID().toString());
    }

    /** Remove the saved passphrase and revoke any in-memory copy. */
    public void clear() throws IOException {
        clearPersistedSecret(preferences(), UUID.randomUUID().toString());
    }

    static void clearPersistedSecret(SharedPreferences preferences) throws IOException {
        clearPersistedSecret(preferences, UUID.randomUUID().toString());
    }

    static void clearPersistedSecret(SharedPreferences preferences, String nextGeneration)
            throws IOException {
        synchronized (STORE_LOCK) {
            if (nextGeneration == null || nextGeneration.isEmpty()) {
                throw new IOException("Config-secret generation is invalid");
            }
            if (!preferences.edit()
                    .remove(CIPHERTEXT)
                    .remove(BOUND_GENERATION)
                    .putString(GENERATION, nextGeneration)
                    .commit()) {
                throw new IOException("Unable to persist config-secret invalidation");
            }
        }
    }

    static InvalidationToken invalidatePersistedSecret(
            SharedPreferences preferences, String nextGeneration) throws IOException {
        synchronized (STORE_LOCK) {
            if (nextGeneration == null || nextGeneration.isEmpty()) {
                throw new IOException("Config-secret generation is invalid");
            }
            String previousGeneration = currentGeneration(preferences);
            Snapshot previousSnapshot = new Snapshot(
                    preferences.getString(CIPHERTEXT, null),
                    preferences.getString(GENERATION, null),
                    preferences.getString(BOUND_GENERATION, null));
            InvalidationToken token = new InvalidationToken(
                    previousGeneration, nextGeneration, previousSnapshot);
            if (!preferences.edit().putString(GENERATION, nextGeneration).commit()) {
                // SharedPreferences can update its in-memory map even when the disk commit
                // reports failure. Return the opaque preimage to the owner so it can attempt
                // a generation-checked recovery while the config mutation barrier is held.
                throw new InvalidationException(token);
            }
            return token;
        }
    }

    /** Re-enables a pre-existing credential only if no later secret operation has rotated it. */
    static boolean restoreInvalidation(SharedPreferences preferences, InvalidationToken token) {
        if (token == null) return false;
        synchronized (STORE_LOCK) {
            String currentGeneration = currentGeneration(preferences);
            if (!token.nextGeneration.equals(currentGeneration)
                    && !(token.previousGeneration.equals(currentGeneration)
                    && matchesSnapshot(preferences, token.snapshot))) {
                return false;
            }
            SharedPreferences.Editor editor = preferences.edit();
            restorePreference(editor, CIPHERTEXT, token.snapshot.ciphertext);
            restorePreference(editor, GENERATION, token.snapshot.generation);
            restorePreference(editor, BOUND_GENERATION, token.snapshot.boundGeneration);
            // A false commit is not durable proof, even if this process's in-memory map now
            // matches. Keep the caller's config-mutation barrier pending in that case.
            return editor.commit() && matchesSnapshot(preferences, token.snapshot);
        }
    }

    private static boolean matchesSnapshot(SharedPreferences preferences, Snapshot snapshot) {
        return snapshot != null
                && equal(snapshot.ciphertext, preferences.getString(CIPHERTEXT, null))
                && equal(snapshot.generation, preferences.getString(GENERATION, null))
                && equal(snapshot.boundGeneration, preferences.getString(BOUND_GENERATION, null));
    }

    private static boolean equal(String left, String right) {
        return left == null ? right == null : left.equals(right);
    }

    public boolean restoreInvalidation(InvalidationToken token) {
        return restoreInvalidation(preferences(), token);
    }

    static boolean hasUsableCiphertext(SharedPreferences preferences) {
        String ciphertext = preferences.getString(CIPHERTEXT, null);
        if (ciphertext == null || ciphertext.isEmpty()) {
            return false;
        }
        String generation = preferences.getString(GENERATION, null);
        String boundGeneration = preferences.getString(BOUND_GENERATION, null);
        if (generation == null && boundGeneration == null) {
            return true; // Existing installs have legacy ciphertext without generation metadata.
        }
        return generation != null && generation.equals(boundGeneration);
    }

    static String currentGeneration(SharedPreferences preferences) {
        String generation = preferences.getString(GENERATION, null);
        return generation == null ? LEGACY_GENERATION : generation;
    }

    public String currentGeneration() {
        return currentGeneration(preferences());
    }

    /**
     * Captures the persisted ciphertext and its binding metadata without decrypting it.
     * The returned value is an opaque in-process preimage for conditional rollback.
     */
    public Snapshot snapshot() {
        return snapshotPersistedSecret(preferences());
    }

    /**
     * Restores an opaque preimage only while the current generation still matches the
     * caller's expected generation. Returns false on a generation conflict; throws if
     * the single preference commit cannot confirm persistence. Android may update the
     * in-memory SharedPreferences map before that failure is reported, so callers must
     * retain their transaction recovery state and fail closed after an IOException.
     */
    public boolean restoreSnapshot(Snapshot snapshot, String expectedCurrentGeneration)
            throws IOException {
        return restorePersistedSecret(preferences(), snapshot, expectedCurrentGeneration);
    }

    static Snapshot snapshotPersistedSecret(SharedPreferences preferences) {
        synchronized (STORE_LOCK) {
            return new Snapshot(
                    preferences.getString(CIPHERTEXT, null),
                    preferences.getString(GENERATION, null),
                    preferences.getString(BOUND_GENERATION, null));
        }
    }

    static boolean restorePersistedSecret(
            SharedPreferences preferences,
            Snapshot snapshot,
            String expectedCurrentGeneration) throws IOException {
        if (snapshot == null) {
            throw new IllegalArgumentException("Config-secret snapshot is missing");
        }
        if (expectedCurrentGeneration == null || expectedCurrentGeneration.isEmpty()) {
            throw new IllegalArgumentException("Expected config-secret generation is missing");
        }

        synchronized (STORE_LOCK) {
            if (!expectedCurrentGeneration.equals(currentGeneration(preferences))) {
                return false;
            }

            SharedPreferences.Editor editor = preferences.edit();
            restorePreference(editor, CIPHERTEXT, snapshot.ciphertext);
            restorePreference(editor, GENERATION, snapshot.generation);
            restorePreference(editor, BOUND_GENERATION, snapshot.boundGeneration);
            if (!editor.commit()) {
                throw new IOException("Config-secret snapshot restoration was not confirmed");
            }
            return true;
        }
    }

    private static void restorePreference(
            SharedPreferences.Editor editor, String key, String value) {
        if (value == null) {
            editor.remove(key);
        } else {
            editor.putString(key, value);
        }
    }

    public boolean isCurrent(BoundSecret secret) {
        return secret != null && secret.generation.equals(currentGeneration());
    }

    public static final class BoundSecret {
        private final String password;
        private final String generation;

        private BoundSecret(String password, String generation) {
            this.password = password;
            this.generation = generation;
        }

        public String password() {
            return password;
        }

        public String generation() {
            return generation;
        }
    }

    public static final class InvalidationToken {
        private final String previousGeneration;
        private final String nextGeneration;
        private final Snapshot snapshot;

        private InvalidationToken(String previousGeneration, String nextGeneration, Snapshot snapshot) {
            this.previousGeneration = previousGeneration;
            this.nextGeneration = nextGeneration;
            this.snapshot = snapshot;
        }

        /** Generation written by the invalidation; callers use it as a compare-and-set guard. */
        public String expectedGeneration() {
            return nextGeneration;
        }

        /** Generation observed immediately before invalidation. */
        public String previousGeneration() {
            return previousGeneration;
        }

        /** Opaque encrypted preimage captured under the same lock as the invalidation. */
        public Snapshot snapshot() {
            return snapshot;
        }

        /** True only when no save, clear, or other invalidation changed the generation in between. */
        public boolean follows(InvalidationToken priorInvalidation) {
            return priorInvalidation != null
                    && previousGeneration.equals(priorInvalidation.expectedGeneration());
        }
    }

    /** The invalidation commit failed; the owner must retain the token and recover or fail closed. */
    public static final class InvalidationException extends IOException {
        private final InvalidationToken token;

        private InvalidationException(InvalidationToken token) {
            super("Unable to persist config-secret invalidation");
            this.token = token;
        }

        public InvalidationToken token() {
            return token;
        }
    }

    /** Opaque snapshot of the exact persisted ciphertext and generation metadata. */
    public static final class Snapshot {
        private final String ciphertext;
        private final String generation;
        private final String boundGeneration;

        private Snapshot(String ciphertext, String generation, String boundGeneration) {
            this.ciphertext = ciphertext;
            this.generation = generation;
            this.boundGeneration = boundGeneration;
        }
    }

    private SharedPreferences preferences() {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private SecretKey key() throws GeneralSecurityException {
        try {
            KeyStore keyStore = KeyStore.getInstance(KEYSTORE);
            keyStore.load(null);
            if (!keyStore.containsAlias(KEY_ALIAS)) {
                KeyGenerator generator = KeyGenerator.getInstance(
                        KeyProperties.KEY_ALGORITHM_AES, KEYSTORE);
                generator.init(new KeyGenParameterSpec.Builder(
                        KEY_ALIAS,
                        KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .build());
                generator.generateKey();
            }
            return ((KeyStore.SecretKeyEntry) keyStore.getEntry(KEY_ALIAS, null)).getSecretKey();
        } catch (Exception e) {
            if (e instanceof GeneralSecurityException) {
                throw (GeneralSecurityException) e;
            }
            throw new GeneralSecurityException("Unable to access config secret key", e);
        }
    }
}
