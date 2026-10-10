package ca.pkay.rcloneexplorer.util;

import java.io.IOException;
import java.util.HashSet;
import org.junit.Test;
import static org.junit.Assert.*;

public class BisyncListingSafetyTest {
    @Test public void normalizationCollisionCannotSilentlyDropContent() throws Exception {
        HashSet<String> paths = new HashSet<>();
        BisyncListingSafety.addPath(paths, "folder/\u00e9.txt");
        try {
            BisyncListingSafety.addPath(paths, "folder/e\u0301.txt");
            fail("Two physical names must not collapse into one bisync object");
        } catch (IOException expected) { }
    }
    @Test public void unusualNamesRemainDistinctInsideTheirRoot() throws Exception {
        HashSet<String> paths = new HashSet<>();
        BisyncListingSafety.addPath(paths, "folder/a:b\n.txt");
        BisyncListingSafety.addPath(paths, "folder/a:b.txt");
        assertEquals(2, paths.size());
    }
    @Test public void absoluteAndTraversalPathsAreRejected() throws Exception {
        for (String path : new String[]{"/escape", "a/../escape", "a//b", "a/./b", "", "a\0b"}) {
            try {
                BisyncListingSafety.addPath(new HashSet<>(), path);
                fail("Unsafe path accepted");
            } catch (IOException expected) { }
        }
    }
}
