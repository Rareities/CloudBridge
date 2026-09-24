package ca.pkay.rcloneexplorer;

import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.*;

public class VirtualContentProviderTest {

    VirtualContentProvider provider;

    @Before
    public void setUp() throws Exception {
        provider = new VirtualContentProvider();
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
        assertTrue(provider.isChildDocument(VirtualContentProvider.ROOT_DOC_PREFIX, VirtualContentProvider.ROOT_DOC_PREFIX + "sub/doc"));
        assertFalse(provider.isChildDocument(VirtualContentProvider.ROOT_DOC_PREFIX + "sub/doc", VirtualContentProvider.ROOT_DOC_PREFIX));
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
