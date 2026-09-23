package ca.pkay.rcloneexplorer.util;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/** A secret-free, conservative identity used only for mutation conflict claims. */
public final class EndpointResource {
    private static final String FORMAT = "v1:";

    private final boolean global;
    private final String rootHash;
    private final List<String> segmentHashes;

    private EndpointResource(boolean global, String rootHash, List<String> segmentHashes) {
        this.global = global;
        this.rootHash = rootHash;
        this.segmentHashes = Collections.unmodifiableList(new ArrayList<>(segmentHashes));
    }

    /** Unknown, wrapped, malformed or otherwise ambiguous targets conflict with every claim. */
    public static EndpointResource global() {
        return new EndpointResource(true, "", Collections.emptyList());
    }

    /** Remote paths are compared conservatively across every remote of the same backend type. */
    public static EndpointResource remote(String backendType, String path) {
        if (backendType == null || backendType.trim().isEmpty() || path == null
                || path.startsWith(":") || path.contains("\u0000")) {
            return global();
        }
        List<String> segments = normalizeSegments(path);
        if (segments == null) {
            return global();
        }
        return keyed("remote:" + normalize(backendType), segments);
    }

    /** Canonicalizes existing symlinks without changing the path passed to rclone. */
    public static EndpointResource localFile(String path) {
        if (path == null || path.trim().isEmpty() || path.startsWith("content://")
                || path.startsWith("document://")) {
            return global();
        }
        try {
            File file = new File(path);
            if (!file.isAbsolute()) {
                return global();
            }
            String canonical = file.getCanonicalPath();
            List<String> segments = normalizeSegments(canonical);
            return segments == null ? global() : keyed("local-filesystem", segments);
        } catch (IOException | SecurityException failure) {
            return global();
        }
    }

    private static EndpointResource keyed(String root, List<String> segments) {
        ArrayList<String> hashed = new ArrayList<>(segments.size());
        for (String segment : segments) {
            hashed.add(sha256(segment));
        }
        return new EndpointResource(false, sha256(root), hashed);
    }

    private static List<String> normalizeSegments(String path) {
        if (path == null || path.contains("\u0000")) {
            return null;
        }
        String portable = path.replace('\\', '/');
        ArrayList<String> result = new ArrayList<>();
        for (String part : portable.split("/", -1)) {
            if (part.isEmpty() || part.equals(".")) {
                continue;
            }
            if (part.equals("..")) {
                if (result.isEmpty()) {
                    return null;
                }
                result.remove(result.size() - 1);
                continue;
            }
            result.add(normalize(part));
        }
        return result;
    }

    private static String normalize(String value) {
        return Normalizer.normalize(value, Normalizer.Form.NFC).toLowerCase(Locale.ROOT);
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                result.append(String.format(Locale.ROOT, "%02x", b & 0xff));
            }
            return result.toString();
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }

    public boolean overlaps(EndpointResource other) {
        if (other == null || global || other.global) {
            return true;
        }
        if (!rootHash.equals(other.rootHash)) {
            return false;
        }
        int shared = Math.min(segmentHashes.size(), other.segmentHashes.size());
        for (int i = 0; i < shared; i++) {
            if (!segmentHashes.get(i).equals(other.segmentHashes.get(i))) {
                return false;
            }
        }
        return true;
    }

    public boolean isGlobal() {
        return global;
    }

    /** Stores only one-way hashes; paths, remote names and account identifiers are not persisted. */
    public static String serializeAll(List<EndpointResource> resources) {
        if (resources == null || resources.isEmpty()) {
            return FORMAT + "G";
        }
        ArrayList<String> encoded = new ArrayList<>();
        for (EndpointResource resource : resources) {
            if (resource == null || resource.global) {
                return FORMAT + "G";
            }
            StringBuilder item = new StringBuilder("R|").append(resource.rootHash).append('|');
            for (int i = 0; i < resource.segmentHashes.size(); i++) {
                if (i > 0) item.append('.');
                item.append(resource.segmentHashes.get(i));
            }
            encoded.add(item.toString());
        }
        StringBuilder result = new StringBuilder(FORMAT);
        for (int i = 0; i < encoded.size(); i++) {
            if (i > 0) result.append(';');
            result.append(encoded.get(i));
        }
        return result.toString();
    }

    /** Malformed or future claim formats fail closed as a global conflict. */
    public static boolean overlapsStored(List<EndpointResource> requested, String stored) {
        if (requested == null || requested.isEmpty() || stored == null || !stored.startsWith(FORMAT)) {
            return true;
        }
        String body = stored.substring(FORMAT.length());
        if (body.equals("G")) {
            return true;
        }
        try {
            for (String encoded : body.split(";", -1)) {
                String[] fields = encoded.split("\\|", -1);
                if (fields.length != 3 || !fields[0].equals("R") || !isHash(fields[1])) {
                    return true;
                }
                ArrayList<String> segments = new ArrayList<>();
                if (!fields[2].isEmpty()) {
                    for (String segment : fields[2].split("\\.", -1)) {
                        if (!isHash(segment)) return true;
                        segments.add(segment);
                    }
                }
                EndpointResource existing = new EndpointResource(false, fields[1], segments);
                for (EndpointResource candidate : requested) {
                    if (candidate == null || candidate.overlaps(existing)) return true;
                }
            }
            return false;
        } catch (RuntimeException malformed) {
            return true;
        }
    }

    private static boolean isHash(String value) {
        return value != null && value.matches("[0-9a-f]{64}");
    }
}
