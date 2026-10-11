package ca.pkay.rcloneexplorer.util;

import android.content.Context;
import ca.pkay.rcloneexplorer.BuildConfig;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.json.JSONArray;
import org.json.JSONObject;

/** Verifies the installed native bytes once per immutable APK identity. */
public final class EngineProvenance {
    private static String verified;
    private EngineProvenance() {}
    public static synchronized void verify(Context context) throws IOException {
        File executable = new File(context.getApplicationInfo().nativeLibraryDir, "librclone.so");
        String key = executable.getAbsolutePath() + ":" + executable.length() + ":" + executable.lastModified() + ":" + BuildConfig.RCLONE_SOURCE_SHA;
        if (key.equals(verified)) return;
        try {
            if (!executable.isFile()) throw new IOException("The native engine is missing");
            ByteArrayOutputStream json = new ByteArrayOutputStream();
            try (InputStream input = context.getAssets().open("engine-provenance.json")) {
                byte[] bytes = new byte[4096]; int count;
                while ((count = input.read(bytes)) != -1) {
                    if (json.size() + count > 65536) throw new IOException("Engine provenance is invalid");
                    json.write(bytes, 0, count);
                }
            }
            JSONObject provenance = new JSONObject(new String(json.toByteArray(), StandardCharsets.UTF_8));
            if (!BuildConfig.RCLONE_SOURCE_SHA.equals(provenance.getString("commit")) ||
                    !"https://github.com/Rareities/rclone.git".equals(provenance.getString("repository")))
                throw new IOException("Engine provenance does not match this application");
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            try (InputStream input = new FileInputStream(executable)) {
                byte[] bytes = new byte[65536]; int count;
                while ((count = input.read(bytes)) != -1) sha.update(bytes, 0, count);
            }
            StringBuilder hash = new StringBuilder();
            for (byte value : sha.digest()) hash.append(String.format(java.util.Locale.ROOT, "%02x", value & 255));
            JSONArray entries = provenance.getJSONArray("artifacts");
            boolean found = false;
            for (int i = 0; i < entries.length(); i++) {
                JSONObject entry = entries.getJSONObject(i);
                if (hash.toString().equals(entry.getString("sha256")) && executable.length() == entry.getLong("bytes")) found = true;
            }
            if (!found) throw new IOException("The packaged engine failed its integrity check. Install a verified build; no transfer was started.");
            verified = key;
        } catch (IOException failure) { throw failure; }
        catch (Exception failure) { throw new IOException("Could not verify the packaged native engine", failure); }
    }
}
