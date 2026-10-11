package io.github.rareities.protonbridge.util;

import org.junit.Test;
import java.io.File;
import java.net.InetAddress;
import java.net.URI;
import static org.junit.Assert.*;

public class NetworkAndListenerPolicyTest {
    @Test public void proxySecretsAndIpv6RemainDataNotUrlSyntax() throws Exception {
        String encoded = NetworkValues.proxy("http", "::1", 8080, "name@domain", "a/b:@ +");
        URI parsed = new URI(encoded);
        assertEquals("[::1]", parsed.getHost());
        assertEquals("name@domain:a/b:@ +", parsed.getUserInfo());
        assertFalse(encoded.contains("a/b:@ +"));
        assertTrue(NetworkValues.dnsEndpoint(InetAddress.getByName("2001:db8::1")).startsWith("["));
        assertTrue(NetworkValues.dnsEndpoint(InetAddress.getByName("2001:db8::1")).endsWith("]:53"));
    }
    @Test public void lanServingCannotFallBackToPlaintextOrAnonymousAccess() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> ListenerPolicy.validate(1, 8080, true, "u", "p", "", ""));
        assertThrows(IllegalArgumentException.class, () -> ListenerPolicy.validate(3, 8080, true, "u", "p", "", ""));
        assertThrows(IllegalArgumentException.class, () -> ListenerPolicy.validate(4, 8080, true, "u", "p", "", ""));
        ListenerPolicy.validate(1, 8080, false, "", "", "", "");
        File certificate = File.createTempFile("fixture-cert-", ".pem");
        File key = File.createTempFile("fixture-key-", ".pem");
        try {
            ListenerPolicy.validate(2, 8443, true, "u", "p", certificate.getAbsolutePath(), key.getAbsolutePath());
            assertThrows(IllegalArgumentException.class, () -> ListenerPolicy.validate(2, 8443, true, "", "p", certificate.getAbsolutePath(), key.getAbsolutePath()));
        } finally { certificate.delete(); key.delete(); }
    }
}
