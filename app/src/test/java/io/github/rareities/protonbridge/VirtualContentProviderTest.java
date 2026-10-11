package io.github.rareities.protonbridge;

import java.io.FileNotFoundException;
import java.util.concurrent.atomic.AtomicInteger;
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
        assertFalse(provider.isChildDocument("remotes/demo:/folder", "remotes/demo:/folder2/secret"));
        assertFalse(provider.isChildDocument("remotes/demo:/folder", "remotes/demo:/folder"));
        assertTrue(provider.isChildDocument("remotes/demo:", "remotes/demo:/folder/file"));
        assertFalse(provider.isChildDocument("remotes/demo:/folder", "remotes/demo:/folder/../private"));
        assertFalse(provider.isChildDocument("remotes/demo:/folder", "remotes/demo:/folder/./private"));
        assertFalse(provider.isChildDocument("remotes/demo:/folder", "remotes/demo:/folder//private"));
        assertFalse(provider.isChildDocument("remotes/demo:/folder", "remotes/demo:/folder/..\\private"));
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
    public void getTargetByChild() {
        assertEquals("remotes/remote:/child",
                VirtualContentProvider.getTargetByChild("remotes/remote:/", "child"));
        assertEquals("remotes/remote:/child",
                VirtualContentProvider.getTargetByChild("remotes/remote:", "child"));
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
        assertEquals("remote", provider.getRemoteName("remotes/remote:/dir/file"));
        assertEquals("", provider.getRclonePath("remote:"));
        assertThrows(IllegalArgumentException.class, () -> provider.getRclonePath("remote:/folder/../secret"));
        assertThrows(IllegalArgumentException.class, () -> provider.getNoRootId("wrongprefix/remote:/file"));
        assertThrows(IllegalArgumentException.class, () -> provider.getTargetByChild("remote:/folder", "../secret"));
    }

    @Test
    public void asynchronousMutationFailureCannotReturnSuccessOrInvalidateCache() {
        AtomicInteger invalidations = new AtomicInteger();
        VirtualContentProvider.RcMutation mutation = new VirtualContentProvider.RcMutation(invalidations::incrementAndGet);
        RcloneRcd.JobStatusResponse running = new RcloneRcd.JobStatusResponse();
        running.finished = false;
        running.success = true;
        mutation.handleJobStatus(running);
        assertThrows(FileNotFoundException.class, () -> mutation.awaitSuccess(0, "Rename"));
        RcloneRcd.JobStatusResponse failed = new RcloneRcd.JobStatusResponse();
        failed.finished = true;
        failed.success = false;
        failed.error = "remote refused mutation";
        mutation.handleJobStatus(failed);
        FileNotFoundException exception = assertThrows(FileNotFoundException.class,
                () -> mutation.awaitSuccess(0, "Rename"));
        assertTrue(exception.getMessage().contains("remote refused mutation"));
        assertEquals(0, invalidations.get());
    }

    @Test
    public void timeoutDoesNotReportSuccessAndLaterConfirmationIsAppliedOnlyOnce() throws Exception {
        AtomicInteger changes = new AtomicInteger();
        VirtualContentProvider.RcMutation mutation = new VirtualContentProvider.RcMutation(changes::incrementAndGet);
        FileNotFoundException unknown = assertThrows(FileNotFoundException.class,
                () -> mutation.awaitSuccess(0, "Copy"));
        assertTrue(unknown.getMessage().contains("unconfirmed"));
        assertEquals(0, changes.get());
        RcloneRcd.JobStatusResponse succeeded = new RcloneRcd.JobStatusResponse();
        succeeded.finished = true;
        succeeded.success = true;
        mutation.handleJobStatus(succeeded);
        mutation.handleJobStatus(succeeded);
        mutation.awaitSuccess(0, "Copy");
        assertEquals(1, changes.get());
    }
}
