package io.github.rareities.protonbridge.util;

import java.io.File;

/** Explicit LAN exposure needs transport security, not just a password. */
public final class ListenerPolicy {
    public static final String CERTIFICATE = "serve_tls_certificate";
    public static final String PRIVATE_KEY = "serve_tls_private_key";
    private ListenerPolicy() {}

    public static void validate(int protocol, int port, boolean remote, String user, String password,
                                String certificate, String key) {
        if (protocol < 1 || protocol > 4 || port < 1 || port > 65535)
            throw new IllegalArgumentException("Select a supported protocol and port from 1 to 65535");
        if (containsControl(user) || containsControl(password))
            throw new IllegalArgumentException("Listener credentials cannot contain line breaks or NUL");
        if (!remote) return;
        if (protocol != 1 && protocol != 2)
            throw new IllegalArgumentException("LAN serving requires HTTPS or HTTPS WebDAV. FTP and DLNA are limited to this device.");
        if (user == null || user.trim().isEmpty() || user.contains(":") || password == null || password.isEmpty())
            throw new IllegalArgumentException("LAN serving requires a username and password");
        if (!readable(certificate) || !readable(key))
            throw new IllegalArgumentException("Select readable PEM certificate and private-key files before enabling LAN access");
    }

    private static boolean containsControl(String value) {
        return value != null && (value.indexOf('\0') >= 0 || value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0);
    }
    private static boolean readable(String path) {
        if (path == null || path.isEmpty() || containsControl(path)) return false;
        File file = new File(path);
        return file.isAbsolute() && file.isFile() && file.canRead();
    }
}
