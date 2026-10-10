package ca.pkay.rcloneexplorer.util;

import java.io.IOException;
import java.text.Normalizer;
import java.util.Set;

/** Rejects listing paths that cannot identify one object under the synchronization root. */
public final class BisyncListingSafety {
    private BisyncListingSafety() {}

    public static void addPath(Set<String> paths, String path) throws IOException {
        if (path == null || path.isEmpty() || path.startsWith("/") || path.indexOf('\0') >= 0) {
            throw new IOException("Root listing contains an invalid relative path");
        }
        for (String component : path.split("/", -1)) {
            if (component.isEmpty() || component.equals(".") || component.equals("..")) {
                throw new IOException("Root listing contains an unsafe path component");
            }
        }
        if (!paths.add(Normalizer.normalize(path, Normalizer.Form.NFC))) {
            throw new IOException("Root contains Unicode-normalized name collisions; resolve them before bisync");
        }
    }
}
