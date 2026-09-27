package ca.pkay.rcloneexplorer;

import org.junit.Before;
import org.junit.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;

public class VirtualContentProviderTest {

    VirtualContentProvider provider;

    @Before
    public void setUp() throws Exception {
        provider = new VirtualContentProvider();
    }

    @Test
    public void awaitRcdServiceWaitsForConnectionCallback() throws Exception {
        Object monitor = new Object();
        AtomicBoolean connected = new AtomicBoolean(false);
        CountDownLatch waitStarted = new CountDownLatch(1);
        Thread connector = new Thread(() -> {
            try {
                if (waitStarted.await(1, TimeUnit.SECONDS)) {
                    synchronized (monitor) {
                        connected.set(true);
                        monitor.notifyAll();
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        connector.start();

        boolean result = VirtualContentProvider.awaitRcdServiceConnection(monitor, 1000, () -> {
            waitStarted.countDown();
            return connected.get();
        });

        connector.join(1000);
        assertTrue(result);
        assertFalse(connector.isAlive());
    }

    @Test
    public void awaitRcdServiceRechecksStateAfterDisconnectNotification() throws Exception {
        Object monitor = new Object();
        AtomicBoolean connected = new AtomicBoolean(false);
        CountDownLatch waitStarted = new CountDownLatch(1);
        Thread serviceLifecycle = new Thread(() -> {
            try {
                if (!waitStarted.await(1, TimeUnit.SECONDS)) return;
                synchronized (monitor) {
                    // A disconnect/spurious notification must not look like a successful bind.
                    monitor.notifyAll();
                }
                Thread.sleep(20);
                synchronized (monitor) {
                    connected.set(true);
                    monitor.notifyAll();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        serviceLifecycle.start();

        boolean result = VirtualContentProvider.awaitRcdServiceConnection(monitor, 1000, () -> {
            waitStarted.countDown();
            return connected.get();
        });

        serviceLifecycle.join(1000);
        assertTrue(result);
        assertFalse(serviceLifecycle.isAlive());
    }

    @Test
    public void awaitRcdServiceTimesOutWhenConnectionNeverArrives() {
        Object monitor = new Object();

        assertFalse(VirtualContentProvider.awaitRcdServiceConnection(monitor, 30, () -> false));
    }

    @Test
    public void awaitRcdServicePreservesInterruption() throws Exception {
        Object monitor = new Object();
        CountDownLatch waitStarted = new CountDownLatch(1);
        AtomicBoolean result = new AtomicBoolean(true);
        AtomicBoolean interrupted = new AtomicBoolean(false);
        Thread waiter = new Thread(() -> {
            result.set(VirtualContentProvider.awaitRcdServiceConnection(monitor, 1000, () -> {
                waitStarted.countDown();
                return false;
            }));
            interrupted.set(Thread.currentThread().isInterrupted());
        });
        waiter.start();

        assertTrue(waitStarted.await(1, TimeUnit.SECONDS));
        waiter.interrupt();
        waiter.join(1000);

        assertFalse(waiter.isAlive());
        assertFalse(result.get());
        assertTrue(interrupted.get());
    }

    @Test
    public void getChildName() {
        assertEquals("child:", provider.getChildName("child:"));
        assertEquals("child", provider.getChildName("remote:child"));
        assertEquals("child", provider.getChildName("remote:parent/child"));
        assertEquals("child", provider.getChildName("remote:parent/child/"));
        assertEquals("child", provider.getChildName("remotes/remote:child"));
        assertEquals("child", provider.getChildName("remotes/remote:/child"));
        assertEquals("child", provider.getChildName("remotes/remote:/child/"));
        assertEquals("child", provider.getChildName("remotes/remote:/parent/child"));
    }

    @Test
    public void getParent() {
        assertEquals("remote:", provider.getParent("remote:child"));
        assertEquals("remote:parent", provider.getParent("remote:parent/child"));
        assertEquals("remote:parent", provider.getParent("remote:parent/child/"));
        assertEquals("remotes/remote:", provider.getParent("remotes/remote:child"));
        assertEquals("remotes/remote:", provider.getParent("remotes/remote:/child"));
        assertEquals("remotes/remote:", provider.getParent("remotes/remote:/child/"));
        assertEquals("remotes/remote:/parent", provider.getParent("remotes/remote:/parent/child"));
    }

    @Test
    public void getRclonePath() {
        assertEquals("dir/file.pdf", provider.getRclonePath("remote:/dir/file.pdf"));
        assertEquals("dir/file.pdf", provider.getRclonePath("remotes/remote:/dir/file.pdf"));
    }

    @Test
    public void isChildDocumentId() {
        String root = VirtualContentProvider.ROOT_DOC_ID;
        String remoteRoot = VirtualContentProvider.ROOT_DOC_PREFIX + "vault:";
        assertTrue(provider.isChildDocument(root, remoteRoot));
        assertTrue(provider.isChildDocument(remoteRoot,
                VirtualContentProvider.ROOT_DOC_PREFIX + "vault:/notes/today.md"));
        assertFalse(provider.isChildDocument(
                VirtualContentProvider.ROOT_DOC_PREFIX + "vault:/notes",
                VirtualContentProvider.ROOT_DOC_PREFIX + "vault:/notes-old/today.md"));
        assertFalse(provider.isChildDocument(remoteRoot, remoteRoot));
        assertFalse(provider.isChildDocument(VirtualContentProvider.ROOT_DOC_PREFIX,
                VirtualContentProvider.ROOT_DOC_PREFIX + "vault:/notes"));
        assertFalse(provider.isChildDocument(VirtualContentProvider.ROOT_DOC_PREFIX + "vault:/notes/..",
                VirtualContentProvider.ROOT_DOC_PREFIX + "vault:/notes/today.md"));
    }

    @Test
    public void getTargetDocumentId() {
        assertEquals("remotes/remote:/item", VirtualContentProvider.getTargetDocumentId("remotes/remote:/dir/item", "remotes/remote:"));
        assertEquals("remotes/remote:/item", VirtualContentProvider.getTargetDocumentId("remotes/remote:/dir/item", "remotes/remote:/"));
    }

    @Test(expected = VirtualContentProvider.DocumentIdException.class)
    public void getTargetDocumentIdRooted() {
        VirtualContentProvider.getTargetDocumentId(
                VirtualContentProvider.getRootedDocumentId("remote:/dir/item"),
                VirtualContentProvider.getRootedDocumentId("remotes/remote:/"));
    }

    @Test
    public void cacheSubtreeIdentityUsesPathBoundaries() {
        assertTrue(VirtualContentProvider.isSameOrDescendantCacheId(
                "remote:/notes/today.md", "remote:/notes"));
        assertFalse(VirtualContentProvider.isSameOrDescendantCacheId(
                "remote:/notes-old/today.md", "remote:/notes"));
        assertTrue(VirtualContentProvider.isSameOrDescendantCacheId(
                "remote:/notes/today.md", "rclone/remotes/remote:/"));
    }

    @Test
    public void subtreeInvalidationRemovesStickyDescendantsButPreservesSibling() {
        VirtualContentProvider.FsState state = new VirtualContentProvider.FsState();
        RcloneRcd.ListItem descendant = new RcloneRcd.ListItem();
        descendant.name = "descendant.md";
        RcloneRcd.ListItem sibling = new RcloneRcd.ListItem();
        sibling.name = "sibling.md";
        state.put("remote:/notes/descendant.md", descendant, 1);
        state.put("remote:/notes-old/sibling.md", sibling, 1);

        state.removeSubtree("remote:/notes");

        assertFalse(state.getStickies().containsKey("remote:/notes/descendant.md"));
        assertTrue(state.getStickies().containsKey("remote:/notes-old/sibling.md"));
    }

    @Test
    public void concurrentCacheSearchAndSubtreeInvalidationRemainSafe() throws Exception {
        VirtualContentProvider.FsState state = new VirtualContentProvider.FsState();
        CountDownLatch start = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread writer = new Thread(() -> {
            try {
                start.await();
                for (int i = 0; i < 1000; i++) {
                    RcloneRcd.ListItem item = new RcloneRcd.ListItem();
                    item.name = "item";
                    state.put("remote:/folder/" + i, item, 1);
                    state.removeSubtree("remote:/folder");
                }
            } catch (Throwable e) {
                failure.compareAndSet(null, e);
            }
        });
        Thread reader = new Thread(() -> {
            try {
                start.await();
                for (int i = 0; i < 1000; i++) {
                    state.search("item");
                    state.getStickies();
                }
            } catch (Throwable e) {
                failure.compareAndSet(null, e);
            }
        });
        writer.start();
        reader.start();
        start.countDown();
        writer.join();
        reader.join();

        assertNull(failure.get());
    }

    @Test
    public void filesystemCacheRetainsRecentlyUsedEntriesAtItsByteLimit() {
        VirtualContentProvider.FsState state = new VirtualContentProvider.FsState();
        String recentlyUsed = "remote:/lru/recent";
        RcloneRcd.ListItem item = new RcloneRcd.ListItem();
        item.name = "entry";
        state.put(recentlyUsed, item, 0);
        for (int i = 0; i < 1332; i++) {
            state.put("remote:/lru/" + i, item, 0);
        }

        state.get(recentlyUsed); // Promote the oldest entry before overflowing the cache.
        state.put("remote:/lru/newest", item, 0);

        assertTrue(state.search("").containsKey(recentlyUsed));
        assertFalse(state.search("").containsKey("remote:/lru/0"));
        assertTrue(state.search("").containsKey("remote:/lru/newest"));
    }

    @Test(expected = IllegalArgumentException.class)
    public void getTargetDocumentIdRejectsDotSegmentLeaf() {
        VirtualContentProvider.getTargetDocumentId("remote:/folder/..", "remote:/target");
    }

    @Test
    public void getTargetByChild() {
        assertEquals("remotes/remote:/child",
                VirtualContentProvider.getTargetByChild("remotes/remote:/", "child"));
        assertEquals("remotes/remote:/child",
                VirtualContentProvider.getTargetByChild("remotes/remote:", "child"));
    }

    @Test
    public void parentDocumentIdPolicyPreservesSafAndLegacyParentIds() {
        assertEquals("remote:/", DocumentIdPolicy.requireParent(
                "rclone/remotes/remote:", VirtualContentProvider.ROOT_DOC_ID,
                VirtualContentProvider.ROOT_DOC_PREFIX));
        assertEquals("remote:/vault/notes", DocumentIdPolicy.requireParent(
                "rclone/remotes/remote:/vault/notes", VirtualContentProvider.ROOT_DOC_ID,
                VirtualContentProvider.ROOT_DOC_PREFIX));
        assertEquals("remote:/vault/notes", DocumentIdPolicy.requireParent(
                "remotes/remote:/vault/notes/", VirtualContentProvider.ROOT_DOC_ID,
                VirtualContentProvider.ROOT_DOC_PREFIX));
        assertEquals("remote:/vault/notes", DocumentIdPolicy.requireParent(
                "remote:vault/notes", VirtualContentProvider.ROOT_DOC_ID,
                VirtualContentProvider.ROOT_DOC_PREFIX));
    }

    @Test
    public void parentDocumentIdPolicyRejectsMalformedAndTraversalIds() {
        String[] invalidIds = {
                "rclone/remotesX/remote:/notes",
                "rclone/remotes/rclone/remotes/remote:/notes",
                "rclone/remotes/remote:/notes/../outside",
                "rclone/remotes/remote:/notes/./child",
                "rclone/remotes/remote:/notes//child",
                "rclone/remotes/:/notes"
        };
        for (String invalidId : invalidIds) {
            try {
                DocumentIdPolicy.requireParent(invalidId, VirtualContentProvider.ROOT_DOC_ID,
                        VirtualContentProvider.ROOT_DOC_PREFIX);
                fail("Expected malformed parent ID to be rejected: " + invalidId);
            } catch (IllegalArgumentException expected) {
                // Fail closed before an ID can be converted into a backend path.
            }
        }
    }

    @Test
    public void documentIdPolicyNormalizesRootsAndPreservesLiteralPathCharacters() {
        assertEquals(VirtualContentProvider.ROOT_DOC_ID, DocumentIdPolicy.requireDocument(
                VirtualContentProvider.ROOT_DOC_ID, VirtualContentProvider.ROOT_DOC_ID,
                VirtualContentProvider.ROOT_DOC_PREFIX));
        assertEquals("remote:", DocumentIdPolicy.requireDocument(
                "rclone/remotes/remote:/", VirtualContentProvider.ROOT_DOC_ID,
                VirtualContentProvider.ROOT_DOC_PREFIX));
        assertEquals("remote:/notes/part:2\\draft.md", DocumentIdPolicy.requireDocument(
                "rclone/remotes/remote:notes/part:2\\draft.md", VirtualContentProvider.ROOT_DOC_ID,
                VirtualContentProvider.ROOT_DOC_PREFIX));
    }

    @Test
    public void queryDocumentRejectsMalformedDocumentIdBeforeProviderAccess() throws Exception {
        try {
            provider.queryDocument("rclone/remotes/remote:/notes/../outside", null);
            fail("Expected malformed document ID to fail before provider access");
        } catch (java.io.FileNotFoundException expected) {
            assertTrue(expected.getCause() instanceof IllegalArgumentException);
        }
    }

    @Test
    public void openDocumentRejectsMalformedDocumentIdBeforeProviderAccess() throws Exception {
        try {
            provider.openDocument("rclone/remotes/remote:/notes/../outside", "r", null);
            fail("Expected malformed document ID to fail before provider access");
        } catch (java.io.FileNotFoundException expected) {
            assertTrue(expected.getCause() instanceof IllegalArgumentException);
        }
    }

    @Test
    public void renameDocumentRejectsMalformedSourceBeforeProviderAccess() throws Exception {
        try {
            provider.renameDocument("rclone/remotes/remote:/notes/../outside", "safe.md");
            fail("Expected malformed source ID to fail before provider access");
        } catch (java.io.FileNotFoundException expected) {
            assertTrue(expected.getCause() instanceof IllegalArgumentException);
        }
    }

    @Test
    public void deleteDocumentRejectsMalformedSourceBeforeProviderAccess() throws Exception {
        try {
            provider.deleteDocument("rclone/remotes/remote:/notes/../outside");
            fail("Expected malformed source ID to fail before provider access");
        } catch (java.io.FileNotFoundException expected) {
            assertTrue(expected.getCause() instanceof IllegalArgumentException);
        }
    }

    @Test
    public void copyDocumentRejectsMalformedSourceBeforeLookup() throws Exception {
        try {
            provider.copyDocument("rclone/remotes/remote:/notes/../outside",
                    "rclone/remotes/remote:/target");
            fail("Expected malformed source ID to fail before lookup");
        } catch (java.io.FileNotFoundException expected) {
            assertTrue(expected.getCause() instanceof IllegalArgumentException);
        }
    }

    @Test
    public void moveDocumentRejectsMalformedSourceBeforeLookup() throws Exception {
        try {
            provider.moveDocument("rclone/remotes/remote:/notes/../outside", "remote:/notes",
                    "rclone/remotes/remote:/target");
            fail("Expected malformed source ID to fail before lookup");
        } catch (java.io.FileNotFoundException expected) {
            assertTrue(expected.getCause() instanceof IllegalArgumentException);
        }
    }

    @Test
    public void moveDocumentRejectsMismatchedSourceParentBeforeLookup() throws Exception {
        try {
            provider.moveDocument("rclone/remotes/remote:/notes/today.md", "remote:/other",
                    "rclone/remotes/remote:/target");
            fail("Expected stale source parent to fail before lookup");
        } catch (java.io.FileNotFoundException expected) {
            assertTrue(expected.getCause() instanceof IllegalArgumentException);
        }
    }

    @Test
    public void getDocumentTypeRejectsMalformedDocumentIdBeforeCacheAccess() throws Exception {
        try {
            provider.getDocumentType("rclone/remotes/remote:/notes/../outside");
            fail("Expected malformed document ID to fail before cache access");
        } catch (java.io.FileNotFoundException expected) {
            assertTrue(expected.getCause() instanceof IllegalArgumentException);
        }
    }

    @Test
    public void queryChildDocumentsRejectsMalformedParentBeforeProviderAccess() throws Exception {
        try {
            provider.queryChildDocuments("rclone/remotes/remote:/notes/../outside", null, (String) null);
            fail("Expected malformed parent to fail before provider/backend resolution");
        } catch (java.io.FileNotFoundException expected) {
            assertTrue(expected.getCause() instanceof IllegalArgumentException);
        }
    }

    @Test
    public void queryRecentDocumentsRejectsMalformedRootBeforeSuperclassPath() throws Exception {
        try {
            provider.queryRecentDocuments("rclone/remotes/remote:/../outside", null);
            fail("Expected malformed root ID to be rejected before the TODO superclass path");
        } catch (java.io.FileNotFoundException expected) {
            assertTrue(expected.getCause() instanceof IllegalArgumentException);
        }
    }

    @Test
    public void recentDocumentsAcceptsOnlyTheDeclaredRootId() {
        assertTrue(VirtualContentProvider.isValidRecentRootId("rclone"));
        assertFalse(VirtualContentProvider.isValidRecentRootId("rclone/remotes"));
        assertFalse(VirtualContentProvider.isValidRecentRootId("rclone/remotes/remote:"));
    }

    @Test
    public void createWebLinkIntentRejectsMalformedDocumentIdBeforeSuperclassPath() throws Exception {
        try {
            provider.createWebLinkIntent("rclone/remotes/remote:/notes/../outside", null);
            fail("Expected malformed document ID to be rejected before the TODO superclass path");
        } catch (java.io.FileNotFoundException expected) {
            assertTrue(expected.getCause() instanceof IllegalArgumentException);
        }
    }

    @Test
    public void removeDocumentRejectsMismatchedParentBeforeRemoteAccess() throws Exception {
        try {
            provider.removeDocument("remote:/folder/file.txt", "remote:/different-folder");
            fail("Expected mismatched parent to be rejected before remote deletion");
        } catch (java.io.FileNotFoundException expected) {
            assertTrue(expected.getCause() instanceof IllegalArgumentException);
        }
    }

    @Test
    public void createDocumentRejectsMalformedParentBeforeProviderAccess() throws Exception {
        try {
            provider.createDocument("rclone/remotes/remote:/notes//child", "text/plain", "safe.md");
            fail("Expected malformed parent to fail before provider/backend resolution");
        } catch (java.io.FileNotFoundException expected) {
            assertTrue(expected.getCause() instanceof IllegalArgumentException);
        }
    }

    @Test
    public void copyDocumentRejectsMalformedParentBeforeSourceLookup() throws Exception {
        String rootedSource = VirtualContentProvider.getRootedDocumentId("remote:/folder/file");
        try {
            provider.copyDocument(rootedSource,
                    "rclone/remotes/remote:/target/../outside");
            fail("Expected malformed target parent to fail before source lookup");
        } catch (java.io.FileNotFoundException expected) {
            assertTrue(expected.getCause() instanceof IllegalArgumentException);
        }
    }

    @Test
    public void moveDocumentRejectsMalformedParentBeforeSourceLookup() throws Exception {
        String rootedSource = VirtualContentProvider.getRootedDocumentId("remote:/folder/file");
        try {
            provider.moveDocument(rootedSource, "remote:/folder",
                    "rclone/remotes/remote:/target//child");
            fail("Expected malformed target parent to fail before source lookup");
        } catch (java.io.FileNotFoundException expected) {
            assertTrue(expected.getCause() instanceof IllegalArgumentException);
        }
    }

    @Test
    public void createDocumentRejectsInvalidNameBeforeRemoteAccess() throws Exception {
        try {
            provider.createDocument("remote:/folder", null, "..");
            fail("Expected invalid create name to fail before remote access");
        } catch (java.io.FileNotFoundException expected) {
            assertTrue(expected.getCause() instanceof IllegalArgumentException);
        }
    }

    @Test
    public void renameDocumentRejectsInvalidNameBeforeRemoteAccess() throws Exception {
        try {
            provider.renameDocument("remote:/folder/file", "");
            fail("Expected empty rename name to fail before remote access");
        } catch (java.io.FileNotFoundException expected) {
            assertTrue(expected.getCause() instanceof IllegalArgumentException);
        }
    }

    @Test
    public void renameDocumentRejectsSeparatorBeforeRemoteAccess() throws Exception {
        try {
            provider.renameDocument("remote:/folder/file", "other/name");
            fail("Expected multi-component rename name to fail before remote access");
        } catch (java.io.FileNotFoundException expected) {
            assertTrue(expected.getCause() instanceof IllegalArgumentException);
        }
    }

    @Test
    public void copyDocumentRejectsDotLeafBeforeSourceLookup() throws Exception {
        String rootedSource = VirtualContentProvider.getRootedDocumentId("remote:/folder/..");
        String rootedParent = VirtualContentProvider.getRootedDocumentId("remote:/target");
        try {
            provider.copyDocument(rootedSource, rootedParent);
            fail("Expected invalid source leaf to fail before lookup");
        } catch (java.io.FileNotFoundException expected) {
            assertTrue(expected.getCause() instanceof IllegalArgumentException);
        }
    }

    @Test
    public void moveDocumentRejectsDotLeafBeforeSourceLookup() throws Exception {
        String rootedSource = VirtualContentProvider.getRootedDocumentId("remote:/folder/..");
        String rootedSourceParent = VirtualContentProvider.getRootedDocumentId("remote:/folder");
        String rootedTargetParent = VirtualContentProvider.getRootedDocumentId("remote:/target");
        try {
            provider.moveDocument(rootedSource, rootedSourceParent, rootedTargetParent);
            fail("Expected invalid source leaf to fail before lookup");
        } catch (java.io.FileNotFoundException expected) {
            assertTrue(expected.getCause() instanceof IllegalArgumentException);
        }
    }

    @Test
    public void getNoRootId() {
        assertEquals("remote:/item", VirtualContentProvider.getNoRootId(
                VirtualContentProvider.ROOT_DOC_PREFIX + "remote:/item"));
    }

    @Test
    public void getRootedDocumentId() {
        assertEquals(VirtualContentProvider.ROOT_DOC_PREFIX + "remote:/item",
                VirtualContentProvider.getRootedDocumentId("remote:/item"));
    }

    @Test
    public void getShortId() {
        assertEquals("remote:/item", VirtualContentProvider.getShortId(
                VirtualContentProvider.ROOT_DOC_PREFIX + "remote:/item"));
    }

    @Test
    public void isRemoteDocument() {
        assertTrue(VirtualContentProvider.isRemoteDocument(
                VirtualContentProvider.ROOT_DOC_PREFIX + "gdrive:"));
    }

    @Test
    public void getRelativeItemPath() {
        String lsPath = "home/taxes/2020.pdf";
        String expectedPath = "/home/taxes/2020.pdf";

        String actualPath = VirtualContentProvider.getRelativeItemPath(lsPath);

        assertEquals(expectedPath, actualPath);
    }

    @Test
    public void getRemoteName() {
        String documentId = "dropbox:/sheet.xls";
        String expectedName = "dropbox";

        String actualName = VirtualContentProvider.getRemoteName(documentId);

        assertEquals(actualName, expectedName);
    }
}
