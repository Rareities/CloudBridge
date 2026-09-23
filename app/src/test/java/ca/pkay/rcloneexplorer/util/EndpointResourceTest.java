package ca.pkay.rcloneexplorer.util;

import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeNoException;
import org.junit.Rule;
import org.junit.rules.TemporaryFolder;

public class EndpointResourceTest {
    @Rule public final TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void remotePathsUseSegmentBoundariesAndConservativeNormalization() {
        EndpointResource parent = EndpointResource.remote("S3", "bucket/team/");
        EndpointResource child = EndpointResource.remote("s3", "bucket/TEAM/project");
        EndpointResource sibling = EndpointResource.remote("s3", "bucket/team-old");
        EndpointResource otherBackend = EndpointResource.remote("drive", "bucket/team/project");

        assertTrue(parent.overlaps(child));
        assertFalse(parent.overlaps(sibling));
        assertFalse(child.overlaps(otherBackend));
    }

    @Test
    public void unicodeEquivalentPathsShareIdentity() {
        String composed = "docs/\u00e9cole";
        String decomposed = "docs/e\u0301cole";

        assertTrue(EndpointResource.remote("drive", composed)
                .overlaps(EndpointResource.remote("drive", decomposed)));
    }

    @Test
    public void ambiguousAndMalformedClaimsFailClosed() {
        EndpointResource direct = EndpointResource.remote("drive", "docs/a");
        assertTrue(EndpointResource.global().overlaps(direct));
        assertTrue(EndpointResource.overlapsStored(Collections.singletonList(direct), "unknown-format"));
        assertTrue(EndpointResource.overlapsStored(Collections.singletonList(direct), "v1:R|bad|bad"));
        assertFalse(EndpointResource.overlapsStored(
                Collections.singletonList(direct),
                EndpointResource.serializeAll(Collections.singletonList(
                        EndpointResource.remote("drive", "docs/b")))));
    }

    @Test
    public void durableIdentityDoesNotPersistPathOrRemoteName() {
        String secretPath = "users/private-vault/secret-document.txt";
        String stored = EndpointResource.serializeAll(Arrays.asList(
                EndpointResource.remote("secret-remote-name", secretPath)));

        assertTrue(stored.startsWith("v1:R|"));
        assertFalse(stored.contains("secret"));
        assertFalse(stored.contains("private-vault"));
        assertFalse(stored.contains("document"));
    }

    @Test
    public void configFingerprintDetectsSameLengthContentChanges() throws Exception {
        Path config = temporaryFolder.getRoot().toPath().resolve("rclone.conf");
        Files.write(config, "abcd".getBytes(StandardCharsets.UTF_8));
        long fixedTime = 1_700_000_000_000L;
        Files.setLastModifiedTime(config, java.nio.file.attribute.FileTime.fromMillis(fixedTime));
        String original = EndpointConflictCoordinator.fingerprintFile(config.toFile());
        assertNotNull(original);

        Files.write(config, "wxyz".getBytes(StandardCharsets.UTF_8));
        Files.setLastModifiedTime(config, java.nio.file.attribute.FileTime.fromMillis(fixedTime));
        String replaced = EndpointConflictCoordinator.fingerprintFile(config.toFile());

        assertNotNull(replaced);
        assertEquals(4L, Files.size(config));
        assertEquals(fixedTime, Files.getLastModifiedTime(config).toMillis());
        assertNotEquals(original, replaced);
    }

    @Test
    public void canonicalLocalPathsResolveSymlinkAliasesWhenSupported() throws Exception {
        Path sandbox = temporaryFolder.getRoot().toPath();
        Path target = Files.createDirectories(sandbox.resolve("physical"));
        Path alias = sandbox.resolve("alias");
        try {
            Files.createSymbolicLink(alias, target);
        } catch (IOException | UnsupportedOperationException | SecurityException unavailable) {
            assumeNoException(unavailable);
            return;
        }

        EndpointResource throughAlias = EndpointResource.localFile(alias.resolve("child").toString());
        EndpointResource direct = EndpointResource.localFile(target.resolve("child").toString());
        assertTrue(throughAlias.overlaps(direct));
    }
}
