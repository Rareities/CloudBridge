package ca.pkay.rcloneexplorer.util;

import android.content.Context;
import android.util.AtomicFile;
import ca.pkay.rcloneexplorer.Database.json.Exporter;
import ca.pkay.rcloneexplorer.Database.json.Importer;
import ca.pkay.rcloneexplorer.Database.json.SharedPreferencesBackup;
import java.io.*;
import java.nio.charset.StandardCharsets;
import ca.pkay.rcloneexplorer.bisync.BisyncSession;

/** Durable rollback journal for the database/preferences/native-config restore boundary. */
public final class ConfigRestoreJournal {
    private static volatile boolean restoring;
    private ConfigRestoreJournal() {}
    public static boolean isRestoring() { return restoring; }
    private static File dir(Context c) { return new File(c.getFilesDir(), "restore-journal"); }

    public static synchronized void recover(Context c) throws IOException {
        File d = dir(c);
        if (!d.exists()) return;
        try (java.io.Closeable admission = BisyncSession.INSTANCE.previewLease(c)) {
            ca.pkay.rcloneexplorer.RcloneRcd.quiesceForConfigurationChange();
            recoverLocked(c);
        } catch (RuntimeException e) {
            throw new IOException("A running job prevents configuration recovery", e);
        }
        refreshSchedules(c);
    }

    private static void recoverLocked(Context c) throws IOException {
        File d = dir(c);
        if (!d.exists()) return;
        if (new File(d, "prepared").exists() && !new File(d, "committed").exists()) {
            try {
                String database = read(new File(d, "database"));
                // Restore our own snapshot exactly, including legacy dangling references.
                // User-provided imports continue to use stricter PreparedImport validation.
                java.util.ArrayList<ca.pkay.rcloneexplorer.Items.Task> tasks = Importer.createTasklist(database);
                java.util.ArrayList<ca.pkay.rcloneexplorer.Items.Trigger> triggers = Importer.createTriggerlist(database);
                java.util.ArrayList<ca.pkay.rcloneexplorer.Items.Filter> filters = Importer.createFilterList(database);
                try (ca.pkay.rcloneexplorer.Database.DatabaseHandler handler = new ca.pkay.rcloneexplorer.Database.DatabaseHandler(c)) {
                    handler.replaceAll(tasks, triggers, filters);
                }
                SharedPreferencesBackup.importJson(read(new File(d, "preferences")), c);
                File secret = new File(d, "unlock");
                if (secret.exists()) ConfigSecretStore.restore(c, read(secret));
                File config = new File(c.getFilesDir(), "rclone.conf");
                if (new File(d, "had-config").exists()) replace(config, new File(d, "config"));
                else if (config.exists()) {
                    if (!config.delete()) throw new IOException("Cannot restore absent config");
                    syncDirectory(c.getFilesDir());
                }
            } catch (Exception e) { throw new IOException("Previous restore requires recovery", e); }
        }
        clear(d);
    }

    public interface Preparation { void run() throws IOException; }

    public static synchronized void install(Context c, File staged, Runnable metadata) throws IOException {
        install(c, staged, metadata, () -> {});
    }

    /** Preparation and installation share admission, so staged encryption cannot overwrite a newer config. */
    public static synchronized void install(Context c, File staged, Runnable metadata, Preparation preparation) throws IOException {
        restoring = true;
        try (java.io.Closeable admission = BisyncSession.INSTANCE.previewLease(c)) {
            ca.pkay.rcloneexplorer.RcloneRcd.quiesceForConfigurationChange();
            recoverLocked(c);
            preparation.run();
            installLocked(c, staged, metadata);
        } catch (RuntimeException e) {
            throw new IOException("A running job prevents configuration restore", e);
        } finally {
            restoring = false;
            File d = dir(c);
            if (!new File(d, "prepared").exists() || new File(d, "committed").exists()) {
                refreshSchedules(c);
            }
        }
    }

    private static void refreshSchedules(Context c) {
        try { new ca.pkay.rcloneexplorer.Services.TriggerService(c).queueTrigger(); }
        catch (RuntimeException e) { FLog.w("ConfigRestoreJournal", "Schedules require reconciliation after configuration change"); }
    }

    private static void installLocked(Context c, File staged, Runnable metadata) throws IOException {
        recoverLocked(c);
        File d = dir(c);
        try {
            if (!d.mkdirs()) throw new IOException("Cannot create restore journal");
            syncDirectory(c.getFilesDir());
            write(new File(d, "database"), Exporter.create(c).getBytes(StandardCharsets.UTF_8));
            write(new File(d, "preferences"), SharedPreferencesBackup.export(c).getBytes(StandardCharsets.UTF_8));
            write(new File(d, "unlock"), ConfigSecretStore.snapshot(c).getBytes(StandardCharsets.UTF_8));
            File config = new File(c.getFilesDir(), "rclone.conf");
            if (config.exists()) {
                copy(config, new File(d, "config"));
                write(new File(d, "had-config"), new byte[]{1});
            }
            write(new File(d, "prepared"), new byte[]{1});
            syncDirectory(d);
            // An old queued operation must not resolve its remote alias against newly installed credentials.
            // This generation intentionally remains advanced after rollback.
            ConfigEpoch.advance(c);
            metadata.run();
            replace(config, staged);
            write(new File(d, "committed"), new byte[]{1});
            syncDirectory(d);
            try { clear(d); }
            catch (IOException cleanup) {
                // The durable committed decision is authoritative. Never report a rollback or
                // mutate installed metadata merely because obsolete snapshots cannot be removed.
                FLog.w("ConfigRestoreJournal", "Committed configuration cleanup deferred");
            }
        } catch (Exception e) {
            try { recoverLocked(c); } catch (IOException recovery) { e.addSuppressed(recovery); }
            throw new IOException("Configuration restore failed", e);
        }
    }

    private static void replace(File target, File source) throws IOException {
        AtomicFile atomic = new AtomicFile(target);
        FileOutputStream out = null;
        try {
            out = atomic.startWrite();
            try (InputStream in = new FileInputStream(source)) { transfer(in, out); }
            // AtomicFile logs some sync/rename failures instead of propagating them.
            out.getFD().sync();
            atomic.finishWrite(out);
            syncDirectory(target.getParentFile());
            if (!java.security.MessageDigest.isEqual(digest(source), digest(target))) {
                throw new IOException("Atomic configuration replacement was not installed");
            }
        } catch (IOException e) { if (out != null) atomic.failWrite(out); throw e; }
    }
    private static byte[] digest(File file) throws IOException {
        try {
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            try (InputStream in = new FileInputStream(file)) {
                byte[] bytes = new byte[8192]; int n; long total = 0;
                while ((n = in.read(bytes)) != -1) {
                    total += n;
                    if (total > 16L * 1024 * 1024) throw new IOException("Restore component exceeds size limit");
                    digest.update(bytes, 0, n);
                }
            }
            return digest.digest();
        } catch (java.security.NoSuchAlgorithmException e) { throw new IOException("Configuration integrity check unavailable", e); }
    }

    private static void copy(File source, File target) throws IOException {
        try (InputStream in = new FileInputStream(source); FileOutputStream out = new FileOutputStream(target)) {
            transfer(in, out); out.getFD().sync();
        }
    }
    private static void transfer(InputStream in, OutputStream out) throws IOException {
        byte[] bytes = new byte[8192]; int n;
        long total = 0;
        while ((n = in.read(bytes)) != -1) {
            total += n;
            if (total > 16L * 1024 * 1024) throw new IOException("Restore component exceeds size limit");
            out.write(bytes, 0, n);
        }
    }
    private static void write(File target, byte[] bytes) throws IOException {
        if (bytes.length > 16L * 1024 * 1024) throw new IOException("Restore component exceeds size limit");
        try (FileOutputStream out = new FileOutputStream(target)) { out.write(bytes); out.getFD().sync(); }
    }
    private static String read(File file) throws IOException {
        if (file.length() > 16 * 1024 * 1024) throw new IOException("Restore journal is too large");
        try (InputStream in = new FileInputStream(file); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            transfer(in, out); return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }
    interface DirectorySync { void sync(File directory) throws IOException; }

    private static void syncDirectory(File directory) throws IOException {
        if (directory == null || !directory.isDirectory()) throw new IOException("Restore directory is unavailable");
        java.io.FileDescriptor descriptor = null;
        try {
            descriptor = android.system.Os.open(directory.getAbsolutePath(),
                    android.system.OsConstants.O_RDONLY, 0);
            android.system.Os.fsync(descriptor);
        } catch (android.system.ErrnoException e) {
            throw new IOException("Cannot make restore journal durable", e);
        } finally {
            if (descriptor != null) {
                try { android.system.Os.close(descriptor); }
                catch (android.system.ErrnoException e) { throw new IOException("Cannot close restore directory", e); }
            }
        }
    }

    static void clear(File d) throws IOException {
        clear(d, ConfigRestoreJournal::syncDirectory);
    }

    /** DirectorySync is injected only for JVM tests; production always fsyncs each decision boundary. */
    static void clear(File d, DirectorySync directorySync) throws IOException {
        File[] files = d.listFiles();
        if (files == null) throw new IOException("Cannot inspect restore journal");
        // Remove rollback eligibility first. A crash while deleting snapshots must never
        // leave "prepared" pointing at a partially deleted rollback set.
        File prepared = new File(d, "prepared");
        if (prepared.exists() && !prepared.delete()) throw new IOException("Cannot retire restore journal");
        directorySync.sync(d);
        File committed = new File(d, "committed");
        for (File file : files) {
            if (file.equals(prepared) || file.equals(committed)) continue;
            if (!file.delete()) throw new IOException("Cannot remove restore journal entry");
        }
        directorySync.sync(d);
        // Keep the committed decision until all snapshots are gone.
        if (committed.exists() && !committed.delete()) throw new IOException("Cannot retire restore decision");
        directorySync.sync(d);
        if (!d.delete()) throw new IOException("Cannot remove restore journal");
        directorySync.sync(d.getParentFile());
    }
}
