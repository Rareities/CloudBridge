package ca.pkay.rcloneexplorer.util;

import java.io.File;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;
import static org.junit.Assert.*;

public class OperationAdmissionTest {
    private File sidecar() throws IOException { return File.createTempFile("operation-admission", ".lock"); }
    private void expectBusy(File file) throws IOException {
        try (OperationAdmission.Lease ignored = OperationAdmission.acquire(file)) {
            fail("Admission must remain exclusively owned");
        } catch (IOException expected) { /* expected contention */ }
    }
    @Test public void standaloneChildrenCannotShareAndCloseIsIdempotent() throws Exception {
        File file = sidecar();
        try {
            OperationAdmission.Lease child = OperationAdmission.processLease(file);
            expectBusy(file);
            child.close(); child.close();
            try (OperationAdmission.Lease next = OperationAdmission.acquire(file)) { assertTrue(file.exists()); }
        } finally { file.delete(); }
    }
    @Test public void parentCloseCannotReleaseAChildProcess() throws Exception {
        File file = sidecar();
        try {
            OperationAdmission.Lease parent = OperationAdmission.acquire(file);
            OperationAdmission.Lease child = OperationAdmission.processLease(file);
            parent.close(); parent.close();
            expectBusy(file);
            child.close();
            try (OperationAdmission.Lease next = OperationAdmission.acquire(file)) { assertTrue(file.exists()); }
        } finally { file.delete(); }
    }
    @Test public void childCanCloseOnAnotherThreadWithoutClosingParent() throws Exception {
        File file = sidecar();
        try {
            OperationAdmission.Lease parent = OperationAdmission.acquire(file);
            OperationAdmission.Lease child = OperationAdmission.processLease(file);
            AtomicReference<Throwable> failure = new AtomicReference<>();
            Thread reaper = new Thread(() -> {
                try { child.close(); child.close(); } catch (Throwable e) { failure.set(e); }
            });
            reaper.start(); reaper.join();
            assertNull(failure.get());
            expectBusy(file);
            parent.close();
            try (OperationAdmission.Lease next = OperationAdmission.acquire(file)) { assertTrue(file.exists()); }
        } finally { file.delete(); }
    }
    @Test public void detachedParentRetainsLockWithoutGrantingInheritance() throws Exception {
        File file = sidecar();
        try {
            OperationAdmission.Lease parent = OperationAdmission.acquire(file);
            parent.detachContext(); parent.detachContext();
            try (OperationAdmission.Lease ignored = OperationAdmission.processLease(file)) {
                fail("Detached scope must not be inherited");
            } catch (IOException expected) { }
            parent.close();
            try (OperationAdmission.Lease next = OperationAdmission.acquire(file)) { assertTrue(file.exists()); }
        } finally { file.delete(); }
    }
    @Test public void nestedParentIsRejectedWithoutLosingOuterOwnership() throws Exception {
        File file = sidecar();
        try (OperationAdmission.Lease parent = OperationAdmission.acquire(file)) {
            expectBusy(file);
            try (OperationAdmission.Lease child = OperationAdmission.processLease(file)) { assertTrue(file.exists()); }
        } finally { file.delete(); }
    }    @Test public void configurationLeaseRequiresAnIdleParent() throws Exception {
        File file = sidecar();
        try (OperationAdmission.Lease parent = OperationAdmission.acquire(file)) {
            try (OperationAdmission.Lease configuration = OperationAdmission.configurationLease(file)) {
                try (OperationAdmission.Lease child = OperationAdmission.processLease(file)) {
                    assertTrue(file.exists());
                    try (OperationAdmission.Lease invalid = OperationAdmission.configurationLease(file)) {
                        fail("Active children must block configuration replacement");
                    } catch (IOException expected) { }
                }
            }
            try (OperationAdmission.Lease configuration = OperationAdmission.configurationLease(file)) {
                assertTrue(file.exists());
            }
        } finally { file.delete(); }
    }

}
