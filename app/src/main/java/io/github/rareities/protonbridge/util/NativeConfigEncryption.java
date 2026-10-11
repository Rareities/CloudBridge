package io.github.rareities.protonbridge.util;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;

/** Uses rclone's configuration encryption, including its own password validation and file format. */
public final class NativeConfigEncryption {
    private NativeConfigEncryption() {}

    public static boolean isEncrypted(File file) throws IOException {
        if (!file.isFile()) return false;
        if (file.length() > 4 * 1024 * 1024) throw new IOException("Configuration exceeds the supported size limit");
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String value = line.trim();
                if (value.isEmpty() || value.startsWith("#") || value.startsWith(";")) continue;
                return value.startsWith("RCLONE_ENCRYPT_V");
            }
        }
        return false;
    }

    public static void validatePassword(String password) {
        if (password == null || password.trim().isEmpty() || password.length() > 4096 ||
                password.indexOf('\n') >= 0 || password.indexOf('\r') >= 0 || password.indexOf('\u0000') >= 0)
            throw new IllegalArgumentException("Use a non-empty configuration password without line breaks");
    }

    public static String[] environment(String[] base, String password) {
        ArrayList<String> values = new ArrayList<>();
        for (String value : base) {
            if (!value.startsWith("RCLONE_CONFIG_PASS=") && !value.startsWith("RCLONE_PASSWORD_COMMAND=") &&
                    !value.startsWith("_RCLONE_CONFIG_KEY_FILE=") && !value.startsWith("RCLONE_ASK_PASSWORD=")) values.add(value);
        }
        values.add("RCLONE_ASK_PASSWORD=false");
        if (password != null && !password.isEmpty()) values.add("RCLONE_CONFIG_PASS=" + password);
        return values.toArray(new String[0]);
    }

    public static boolean canRead(String executable, File config, String password, String[] baseEnvironment) throws IOException {
        Process child = Runtime.getRuntime().exec(new String[]{executable, "--config", config.getAbsolutePath(),
                "--ask-password=false", "listremotes"}, environment(baseEnvironment, password));
        long started = System.nanoTime();
        ProcessRunner.Outcome result = new ProcessRunner().run(child,
                () -> System.nanoTime() - started > 30_000_000_000L, null, null);
        return result.success();
    }

    /** Call only with a private staged file. The original file is never edited here. */
    public static void encrypt(String executable, File staged, String previousPassword, String password,
                               String[] baseEnvironment) throws IOException {
        validatePassword(password);
        if (!canRead(executable, staged, previousPassword, baseEnvironment))
            throw new IOException("The configuration password is incorrect or the configuration is invalid");
        Process child = Runtime.getRuntime().exec(new String[]{executable, "--config", staged.getAbsolutePath(),
                "config", "encryption", "set"}, environment(baseEnvironment, previousPassword));
        byte[] input = (password + "\n" + password + "\n").getBytes(StandardCharsets.UTF_8);
        try (OutputStream stdin = child.getOutputStream()) { stdin.write(input); }
        catch (IOException error) { child.destroy(); throw new IOException("Could not supply the configuration password", error); }
        finally { Arrays.fill(input, (byte) 0); }
        long started = System.nanoTime();
        ProcessRunner.Outcome result = new ProcessRunner().run(child,
                () -> System.nanoTime() - started > 30_000_000_000L, null, null);
        // Older engines can return exit zero after an interactive configuration error.
        if (!result.success() || !isEncrypted(staged) || !canRead(executable, staged, password, baseEnvironment))
            throw new IOException("rclone did not confirm an encrypted, readable configuration");
    }
}
