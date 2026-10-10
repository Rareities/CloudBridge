package ca.pkay.rcloneexplorer.validation;

import android.content.Context;
import android.os.Build;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import static org.junit.Assert.*;

/** Executes only disposable local operations, never a configured cloud remote. */
@RunWith(AndroidJUnit4.class)
public class NativeEngineAcceptanceTest {
    private Context context;
    private File scratch;
    private File config;
    private File engine;

    @Before public void setUp() throws Exception {
        context = ApplicationProvider.getApplicationContext();
        scratch = new File(context.getCacheDir(), "engine-acceptance-" + System.nanoTime());
        assertTrue(scratch.mkdir());
        config = new File(scratch, "empty.conf");
        assertTrue(config.createNewFile());
        engine = new File(context.getApplicationInfo().nativeLibraryDir, "librclone.so");
        assertTrue("Packaged engine exists", engine.isFile());
    }

    @After public void tearDown() { remove(scratch); }

    @Test public void packagedEngineMatchesProvenanceAndStarts() throws Exception {
        JSONObject provenance;
        try (InputStream in = context.getAssets().open("rclone-build.json")) {
            provenance = new JSONObject(new String(read(in), StandardCharsets.UTF_8));
        }
        assertTrue(provenance.getString("commit").matches("[0-9a-f]{40}"));
        assertEquals("android", provenance.getString("goos"));
        assertTrue(provenance.getBoolean("cgo"));
        assertEquals("pie", provenance.getString("buildMode"));
        assertEquals(16384, provenance.getInt("maxPageSize"));
        String actualHash;
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream in = new FileInputStream(engine)) {
            byte[] block = new byte[65536]; int count;
            while ((count = in.read(block)) != -1) digest.update(block, 0, count);
        }
        StringBuilder hex = new StringBuilder();
        for (byte value : digest.digest()) hex.append(String.format(java.util.Locale.ROOT, "%02x", value & 255));
        actualHash = hex.toString();
        boolean matched = false;
        JSONObject binaries = provenance.getJSONObject("binaries");
        for (String abi : Build.SUPPORTED_ABIS) {
            if (binaries.has(abi) && actualHash.equals(binaries.getJSONObject(abi).getString("sha256"))) matched = true;
        }
        assertTrue("Installed executable hash matches a supported packaged ABI", matched);
        Result result = run("version");
        assertEquals(result.output, 0, result.exit);
        assertTrue(result.output.contains("rclone v"));
    }

    @Test public void argumentArrayPreservesUnusualLocalNames() throws Exception {
        File tree = new File(scratch, "tree with spaces"); assertTrue(tree.mkdir());
        String name = "literal $(not-a-command) é\nfile";
        write(new File(tree, name), "content");
        Result result = run("lsjson", tree.getAbsolutePath(), "--hash");
        assertEquals(result.output, 0, result.exit);
        JSONArray listing = new JSONArray(result.output);
        assertEquals(1, listing.length());
        assertEquals(name, listing.getJSONObject(0).getString("Name"));
    }

    @Test public void disposableBisyncInitializesAndDetectsSameSizeEdit() throws Exception {
        File a = new File(scratch, "a"); File b = new File(scratch, "b"); File state = new File(scratch, "state");
        assertTrue(a.mkdir()); assertTrue(b.mkdir());
        write(new File(a, "RCLONE_TEST"), "access"); write(new File(b, "RCLONE_TEST"), "access");
        write(new File(a, "file"), "OLD");
        String[] common = {"bisync", a.getAbsolutePath(), b.getAbsolutePath(), "--workdir", state.getAbsolutePath(),
                "--compare", "size,checksum", "--check-access", "--max-delete", "10", "--stats", "0"};
        ArrayList<String> initial = new ArrayList<>(Arrays.asList(common));
        initial.add("--resync"); initial.add("--resync-mode"); initial.add("path1");
        Result first = run(initial.toArray(new String[0])); assertEquals(first.output, 0, first.exit);
        write(new File(b, "file"), "NEW");
        Result second = run(common); assertEquals(second.output, 0, second.exit);
        try (InputStream in = new FileInputStream(new File(a, "file"))) {
            assertEquals("NEW", new String(read(in), StandardCharsets.UTF_8));
        }
    }

    private Result run(String... options) throws Exception {
        ArrayList<String> argv = new ArrayList<>();
        argv.add(engine.getAbsolutePath()); argv.add("--config"); argv.add(config.getAbsolutePath());
        argv.add("--ask-password=false"); argv.addAll(Arrays.asList(options));
        Process process = new ProcessBuilder(argv).redirectErrorStream(true).start();
        ExecutorService reader = Executors.newSingleThreadExecutor();
        Future<byte[]> output = reader.submit(() -> read(process.getInputStream()));
        try {
            String text = new String(output.get(45, TimeUnit.SECONDS), StandardCharsets.UTF_8);
            return new Result(process.waitFor(), text);
        } finally { process.destroy(); reader.shutdownNow(); }
    }

    private static void write(File file, String text) throws Exception {
        try (FileOutputStream out = new FileOutputStream(file)) { out.write(text.getBytes(StandardCharsets.UTF_8)); }
    }
    private static byte[] read(InputStream in) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream(); byte[] block = new byte[4096]; int count;
        while ((count = in.read(block)) != -1) out.write(block, 0, count);
        return out.toByteArray();
    }
    private static void remove(File file) {
        if (file == null) return;
        File[] children = file.listFiles(); if (children != null) for (File child : children) remove(child);
        file.delete();
    }
    private static final class Result {
        final int exit; final String output;
        Result(int exit, String output) { this.exit = exit; this.output = output; }
    }
}
