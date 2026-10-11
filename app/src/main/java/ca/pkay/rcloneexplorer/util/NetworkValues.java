package ca.pkay.rcloneexplorer.util;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/** Shared native network argument encoding. Values are never written to diagnostics. */
public final class NetworkValues {
    private NetworkValues() {}
    public static String dnsEndpoint(InetAddress address) {
        String host = address.getHostAddress();
        return (address instanceof Inet6Address ? "[" + host + "]" : host) + ":53";
    }
    public static String proxy(String scheme, String host, int port, String user, String password) {
        if (!("http".equals(scheme) || "https".equals(scheme) || "socks5".equals(scheme) || "socks5h".equals(scheme)) ||
                port < 1 || port > 65535) throw new IllegalArgumentException("Invalid proxy protocol or port");
        if (host == null || host.isEmpty() || host.indexOf('@') >= 0 || host.indexOf('/') >= 0)
            throw new IllegalArgumentException("Proxy host must be a hostname or IP address, not a URL");
        try {
            URI endpoint = new URI(scheme, null, host, port, null, null, null);
            if (endpoint.getHost() == null) throw new IllegalArgumentException("Invalid proxy host");
            String auth = (user == null || user.isEmpty()) && (password == null || password.isEmpty()) ? "" :
                    encode(user) + ":" + encode(password) + "@";
            return scheme + "://" + auth + endpoint.getRawAuthority();
        } catch (java.net.URISyntaxException invalid) {
            throw new IllegalArgumentException("Invalid proxy host", invalid);
        }
    }
    private static String encode(String value) {
        try { return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8.name()).replace("+", "%20"); }
        catch (java.io.UnsupportedEncodingException impossible) { throw new AssertionError(impossible); }
    }
}
