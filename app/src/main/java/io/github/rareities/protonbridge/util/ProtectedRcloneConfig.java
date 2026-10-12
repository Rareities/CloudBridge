package io.github.rareities.protonbridge.util;

import android.content.Context;
import android.util.AtomicFile;
import android.util.Base64;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.Lock;

/** Native encrypted configuration shared by CLI children and the document-provider daemon. */
public final class ProtectedRcloneConfig {
    private ProtectedRcloneConfig() {}
    private static final Set<Process> children = Collections.newSetFromMap(new ConcurrentHashMap<Process, Boolean>());
    private static final int MAX_CONFIG_BYTES = 4 * 1024 * 1024;

    public static boolean hasRunningProcess() {
        for (Process child : children) {
            try { child.exitValue(); children.remove(child); }
            catch (IllegalThreadStateException running) { }
        }
        return !children.isEmpty();
    }

    public static Process launch(Context context, String[] command, String[] environment) throws IOException {
        EngineProvenance.verify(context);
        Lock lock = ConfigurationExclusion.LOCK.writeLock();
        if (!lock.tryLock()) throw new IOException("Configuration is being prepared; retry after it finishes");
        NativeSessionGate.Lease session = null;
        try {
            requireRecoveredJournal(context);
            hasRunningProcess();
            if (!NativeSessionGate.isLocalMetadata(command) && !NativeSessionGate.isDaemon(command))
                session = NativeSessionGate.GLOBAL.acquireCommand();
            File main = new File(context.getFilesDir(), "rclone.conf");
            File selected = selectedConfig(command, main);
            String password;
            if (selected.equals(main)) password = configIndependent(command) ? null : ensure(context, command[0], environment);
            else password = NativeConfigEncryption.isEncrypted(selected) ? new ConfigPasswordStore(context).read() : null;
            if (selected.equals(main) && NativeSessionGate.isConfigMutation(command))
                ConfigurationGeneration.advance(context);
            Process child = Runtime.getRuntime().exec(command, NativeConfigEncryption.environment(environment, password));
            if (selected.equals(main)) children.add(child);
            if (session != null) { session.closeAfterExit(child); session = null; }
            return child;
        } finally {
            if (session != null) session.close();
            lock.unlock();
        }
    }

    /** Metadata/help commands do not read remotes and must never trigger migration. */
    private static boolean configIndependent(String[] command) {
        int last = command.length - 1;
        if (last < 1) return false;
        if (command[last].equals("--version") || command[last].equals("--help")) return true;
        return last >= 2 && command[last - 1].equals("config") && command[last].equals("providers");
    }

    /** A partially restored configuration must be rolled back before launching rclone. */
    private static void requireRecoveredJournal(Context context) throws IOException {
        File journal = new File(context.getFilesDir(), "restore-journal");
        if (ConfigurationExclusion.isRestoreRecoveryRequired() || new File(journal, "pending").exists() || new File(journal, "pending.bak").exists())
            throw new IOException("Configuration restoration was interrupted; restart the app to recover before using rclone");
    }

    /** Initial migration runs off the UI thread before remotes/version checks become available. */
    public static void prepare(Context context, String executable, String[] environment) throws IOException {
        EngineProvenance.verify(context);
        Lock lock = ConfigurationExclusion.LOCK.writeLock();
        lock.lock();
        try {
            requireRecoveredJournal(context);
            ensure(context, executable, environment);
        } finally { lock.unlock(); }
    }

    private static File selectedConfig(String[] command, File fallback) {
        for (int i = 1; i < command.length; i++) {
            if (command[i].equals("--config") && i + 1 < command.length) return new File(command[i + 1]);
            if (command[i].startsWith("--config=")) return new File(command[i].substring(9));
        }
        return fallback;
    }

    /** Caller owns ConfigurationExclusion's write lock, so migration cannot race with a new child. */
    private static String ensure(Context context, String executable, String[] environment) throws IOException {
        File main = new File(context.getFilesDir(), "rclone.conf");
        AtomicFile atomic = new AtomicFile(main);
        try (FileInputStream ignored = atomic.openRead()) { /* Recover an interrupted atomic replacement. */ }
        catch (FileNotFoundException missing) { }
        ConfigPasswordStore store = new ConfigPasswordStore(context);
        boolean encrypted = NativeConfigEncryption.isEncrypted(main);
        String password = null;
        boolean freshKey = false;
        try { password = store.read(); }
        catch (IOException invalidKey) {
            if (encrypted) throw invalidKey;
            // An interrupted migration can leave plaintext with an unusable wrapping
            // record. Re-protect that readable file, preserving it until encryption succeeds.
            freshKey = true;
        }
        if (!main.isFile()) {
            if (hasRunningProcess()) throw new IOException("Configuration is being updated by an active process; retry when it finishes");
            File[] candidates = nativeRecoveryFiles(main);
            if (candidates.length == 1 && NativeConfigEncryption.canRead(executable, candidates[0], password, environment)) {
                install(candidates[0], main);
                encrypted = NativeConfigEncryption.isEncrypted(main);
            } else if (candidates.length > 0 || password != null || freshKey) {
                throw new IOException("Configuration is missing. Recovery files and the existing password record have been preserved; import a verified backup.");
            } else if (!main.createNewFile()) {
                throw new IOException("Could not prepare the initial configuration file");
            }
        }
        if (encrypted) {
            if (password == null) throw new IOException("Configuration is encrypted. Unlock it in the app before scheduling transfers.");
            return password;
        }
        if (hasRunningProcess()) throw new IOException("Close active transfers and file streams before protecting the configuration");
        if (password == null) { password = randomPassword(); freshKey = true; }
        File staged = ConfigStaging.create(context, "config-protect-", false);
        try {
            if (main.isFile()) copy(main, staged);
            NativeConfigEncryption.encrypt(executable, staged, null, password, environment);
            // Store the wrapped password first. A crash leaves either the original readable
            // plaintext config or an encrypted config with its matching wrapped password.
            if (freshKey) {
                byte[] record = store.prepareRecord(password, true);
                try { store.installRecord(record); }
                finally { Arrays.fill(record, (byte) 0); }
            }
            install(staged, main);
            return password;
        } finally { ConfigStaging.discard(staged); }
    }

    public static void rememberVerifiedPassword(Context context, String executable, String password, String[] environment) throws IOException {
        Lock lock = ConfigurationExclusion.LOCK.writeLock();
        lock.lock();
        try {
            NativeConfigEncryption.validatePassword(password);
            File main = new File(context.getFilesDir(), "rclone.conf");
            if (!NativeConfigEncryption.isEncrypted(main) || !NativeConfigEncryption.canRead(executable, main, password, environment))
                throw new IOException("The configuration password is incorrect");
            ConfigPasswordStore store = new ConfigPasswordStore(context);
            try {
                store.read(); // Determine whether the previous wrapping key can still decrypt.
                store.save(password);
            } catch (IOException unreadable) {
                byte[] fresh = store.prepareRecord(password, true);
                try { store.installRecord(fresh); }
                finally { java.util.Arrays.fill(fresh, (byte) 0); }
            }
        } finally { lock.unlock(); }
    }

    public static boolean needsUnlock(Context context, String executable, String[] environment) {
        File main = new File(context.getFilesDir(), "rclone.conf");
        try {
            if (!NativeConfigEncryption.isEncrypted(main)) return false;
            String password = new ConfigPasswordStore(context).read();
            return password == null || !NativeConfigEncryption.canRead(executable, main, password, environment);
        } catch (IOException invalid) { return true; }
    }

    /** An import can prepare a replacement password record without changing the active one. */
    public static final class PreparedImport {
        public final byte[] passwordRecord;
        private final byte[] expectedGeneration;
        PreparedImport(byte[] passwordRecord, byte[] expectedGeneration) {
            this.passwordRecord = passwordRecord;
            this.expectedGeneration = expectedGeneration;
        }

        /** Check under ConfigurationExclusion's write lock immediately before install. */
        public void assertCurrent(Context context) throws IOException {
            if (!Arrays.equals(expectedGeneration, generation(context)))
                throw new IOException("Configuration changed during import. Retry with a fresh backup selection");
        }
    }

    /** Bind prepared ciphertext to the complete pair that existed during validation. */
    private static byte[] generation(Context context) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            File[] files = {new File(context.getFilesDir(), "rclone.conf"),
                    new File(context.getFilesDir(), "config-password.json")};
            int[] limits = {MAX_CONFIG_BYTES, 32768};
            byte[] buffer = new byte[8192];
            for (int i = 0; i < files.length; i++) {
                File file = files[i];
                boolean exists = file.exists() || new File(file.getPath() + ".bak").exists();
                digest.update((byte) (exists ? 1 : 0));
                if (exists) {
                    try (InputStream input = new AtomicFile(file).openRead()) {
                        int count, total = 0;
                        while ((count = input.read(buffer)) != -1) {
                            if (count > limits[i] - total) throw new IOException("Configuration state exceeds the supported size");
                            digest.update(buffer, 0, count);
                            total += count;
                        }
                    }
                }
            }
            return digest.digest();
        } catch (NoSuchAlgorithmException impossible) {
            throw new IOException("Cannot verify configuration generation", impossible);
        }
    }

    /** Re-encrypt a validated import. The caller commits its config and optional password record together. */
    public static PreparedImport protectImport(Context context, String executable, File staged, String suppliedPassword, String[] environment) throws IOException {
        Lock lock = ConfigurationExclusion.LOCK.writeLock();
        lock.lock();
        try {
            requireRecoveredJournal(context);
            ConfigPasswordStore store = new ConfigPasswordStore(context);
            String managedPassword = null;
            boolean invalidWrappingKey = false;
            try { managedPassword = store.read(); }
            catch (IOException error) { invalidWrappingKey = true; }
            String inputPassword = suppliedPassword == null || suppliedPassword.isEmpty() ? managedPassword : suppliedPassword;
            // An encrypted backup from another installation must always be unlocked
            // before any live config/password mutation.
            if (NativeConfigEncryption.isEncrypted(staged) && inputPassword == null)
                throw new IOException("Supply the backup configuration password to recover the encrypted file");
            if (!NativeConfigEncryption.canRead(executable, staged, inputPassword, environment))
                throw new IOException("The backup password is incorrect, missing, or its configuration is invalid");
            boolean newWrappingKey = invalidWrappingKey || managedPassword == null;
            if (newWrappingKey) managedPassword = randomPassword();
            NativeConfigEncryption.encrypt(executable, staged, inputPassword, managedPassword, environment);
            // The old wrapped password stays installed until the journalled config
            // replacement; an invalidated key cannot strand an explicit backup restore.
            return new PreparedImport(newWrappingKey ? store.prepareRecord(managedPassword, true) : null, generation(context));
        } finally { lock.unlock(); }
    }

    public static File exportEncrypted(Context context, String executable, String password, String[] environment) throws IOException {
        NativeConfigEncryption.validatePassword(password);
        if (password.length() < 12) throw new IOException("Use at least 12 characters for the backup password");
        Lock lock = ConfigurationExclusion.LOCK.writeLock();
        lock.lock();
        File staged = null;
        try {
            String current = ensure(context, executable, environment);
            if (hasRunningProcess()) throw new IOException("Finish transfers and close open file streams before exporting a backup");
            staged = ConfigStaging.create(context, "config-export-", true);
            copy(new File(context.getFilesDir(), "rclone.conf"), staged);
            NativeConfigEncryption.encrypt(executable, staged, current, password, environment);
            return staged;
        } catch (IOException error) {
            ConfigStaging.discard(staged);
            throw error;
        } finally { lock.unlock(); }
    }

    private static String randomPassword() {
        byte[] bytes = new byte[48];
        new SecureRandom().nextBytes(bytes);
        return Base64.encodeToString(bytes, Base64.NO_WRAP | Base64.URL_SAFE | Base64.NO_PADDING);
    }

    private static File[] nativeRecoveryFiles(File main) {
        String pattern = java.util.regex.Pattern.quote(main.getName()) + "\\.old[0-9]+";
        File[] files = main.getParentFile().listFiles(file -> file.isFile() && file.getName().matches(pattern));
        return files == null ? new File[0] : files;
    }

    public static void copy(File source, File destination) throws IOException {
        if (source.length() > MAX_CONFIG_BYTES) throw new IOException("Configuration exceeds the supported size limit");
        try (InputStream input = new FileInputStream(source); FileOutputStream output = new FileOutputStream(destination)) {
            transfer(input, output);
            output.getFD().sync();
        }
    }

    public static void install(File staged, File destination) throws IOException {
        AtomicFile atomic = new AtomicFile(destination);
        FileOutputStream output = atomic.startWrite();
        try (InputStream input = new FileInputStream(staged)) {
            transfer(input, output);
            output.getFD().sync();
            atomic.finishWrite(output);
        } catch (IOException error) { atomic.failWrite(output); throw error; }
        DurableFiles.syncDirectory(destination.getParentFile());
        try (InputStream expected = new FileInputStream(staged)) {
            DurableFiles.verifyContents(atomic, expected, MAX_CONFIG_BYTES);
        }
    }

    public static void transfer(InputStream input, java.io.OutputStream output) throws IOException {
        byte[] buffer = new byte[8192];
        int total = 0, count;
        while ((count = input.read(buffer)) != -1) {
            if (count > MAX_CONFIG_BYTES - total) throw new IOException("Configuration exceeds the supported size limit");
            output.write(buffer, 0, count);
            total += count;
        }
    }
}
