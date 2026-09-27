package ca.pkay.rcloneexplorer.Database.json;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.EnumMap;
import java.util.Map;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class ImportTransactionJournalTest {

    @Rule
    public final TemporaryFolder temporaryFolder = new TemporaryFolder();

    private File preimageRoot;
    private File journalFile;
    private Map<ImportTransactionJournal.Store, String> paths;

    @Before
    public void setUp() throws Exception {
        preimageRoot = temporaryFolder.newFolder("preimages");
        journalFile = new File(temporaryFolder.getRoot(), "import-transaction.journal");
        paths = new EnumMap<>(ImportTransactionJournal.Store.class);
        addPreimage(ImportTransactionJournal.Store.DATABASE, "database.json", "db-before");
        addPreimage(ImportTransactionJournal.Store.PREFERENCES, "preferences.json",
                "preference-before-secret-sentinel");
        addPreimage(ImportTransactionJournal.Store.CONFIG, "config.snapshot", "config-before");
    }

    @Test
    public void roundTripsEveryStoreBoundaryAcrossRestartWithoutSerializingPreimageContents()
            throws Exception {
        ImportTransactionJournal journal = newJournal();
        ImportTransactionJournal.Transaction transaction = prepared()
                .withPhase(ImportTransactionJournal.Phase.APPLYING);
        journal.write(transaction);

        for (ImportTransactionJournal.Store store : ImportTransactionJournal.Store.values()) {
            transaction = transaction.withStoreStatus(store,
                    ImportTransactionJournal.StoreStatus.APPLYING);
            journal.write(transaction);
            assertRestartState(store, ImportTransactionJournal.StoreStatus.APPLYING);

            transaction = transaction.withStoreStatus(store,
                    ImportTransactionJournal.StoreStatus.APPLIED);
            journal.write(transaction);
            assertRestartState(store, ImportTransactionJournal.StoreStatus.APPLIED);
        }

        transaction = transaction.withPhase(ImportTransactionJournal.Phase.COMMITTED);
        journal.write(transaction);
        ImportTransactionJournal.Recovery terminal = newJournal().inspectForStartup();
        assertEquals(ImportTransactionJournal.RecoveryAction.NO_ACTION, terminal.getAction());
        assertEquals(ImportTransactionJournal.Phase.COMMITTED, terminal.getTransaction().getPhase());
        assertTrue(journalFile.length() <= ImportTransactionJournal.MAX_JOURNAL_BYTES);
        assertFalse(new String(readAll(journalFile), StandardCharsets.ISO_8859_1)
                .contains("secret-sentinel"));
        assertPreimagesStillExist();
    }

    @Test
    public void interruptedReplacementRecoversBackupAfterProcessRestart() throws Exception {
        ImportTransactionJournal.Transaction transaction = prepared();
        newJournal().write(transaction);
        File backup = new File(journalFile.getPath() + ".bak");
        assertTrue(journalFile.renameTo(backup)); // Simulate a crash after base -> backup.

        ImportTransactionJournal.Recovery recovery = newJournal().inspectForStartup();

        assertEquals(ImportTransactionJournal.RecoveryAction.NO_ACTION, recovery.getAction());
        assertEquals(transaction.getTransactionId(), recovery.getTransaction().getTransactionId());
        assertEquals(backup.getCanonicalFile(),
                recovery.getPreservedJournalFile().getCanonicalFile());
        assertPreimagesStillExist();
    }

    @Test
    public void interruptedRollbackAndFailedStoreStatusRequestRecovery() throws Exception {
        ImportTransactionJournal.Transaction transaction = prepared()
                .withPhase(ImportTransactionJournal.Phase.APPLYING)
                .withPhase(ImportTransactionJournal.Phase.ROLLING_BACK)
                .withStoreStatus(ImportTransactionJournal.Store.DATABASE,
                        ImportTransactionJournal.StoreStatus.ROLLBACK_PENDING)
                .withStoreStatus(ImportTransactionJournal.Store.DATABASE,
                        ImportTransactionJournal.StoreStatus.ROLLBACK_FAILED)
                .withPhase(ImportTransactionJournal.Phase.ROLLBACK_FAILED);
        newJournal().write(transaction);

        ImportTransactionJournal.Recovery recovery = newJournal().inspectForStartup();

        assertEquals(ImportTransactionJournal.RecoveryAction.RESUME_ROLLBACK,
                recovery.getAction());
        assertEquals(ImportTransactionJournal.StoreStatus.ROLLBACK_FAILED,
                recovery.getTransaction().getPreimage(ImportTransactionJournal.Store.DATABASE)
                        .getStatus());
        assertEquals("database.json", recovery.getTransaction()
                .getPreimage(ImportTransactionJournal.Store.DATABASE).getRelativePath());
        assertPreimagesStillExist();
    }

    @Test
    public void malformedJournalIsQuarantinedAndPreserved() throws Exception {
        byte[] malformed = "not a journal".getBytes(StandardCharsets.UTF_8);
        writeBytes(journalFile, malformed);

        ImportTransactionJournal.Recovery recovery = newJournal().inspectForStartup();

        assertQuarantinedBytes(recovery, malformed);
        assertEquals("Journal is malformed", recovery.getReason());
        assertPreimagesStillExist();
    }

    @Test
    public void unknownVersionIsQuarantinedAndPreserved() throws Exception {
        byte[] unknown = new byte[64];
        unknown[0] = 'C';
        unknown[1] = 'B';
        unknown[2] = 'I';
        unknown[3] = 'J';
        unknown[4] = 0;
        unknown[5] = (byte) (ImportTransactionJournal.FORMAT_VERSION + 1);
        writeBytes(journalFile, unknown);

        ImportTransactionJournal.Recovery recovery = newJournal().inspectForStartup();

        assertQuarantinedBytes(recovery, unknown);
        assertEquals("Journal version is not supported", recovery.getReason());
    }

    @Test
    public void oversizedJournalIsQuarantinedWithoutTruncation() throws Exception {
        byte[] oversized = new byte[ImportTransactionJournal.MAX_JOURNAL_BYTES + 1];
        oversized[0] = 'A';
        oversized[oversized.length - 1] = 'Z';
        writeBytes(journalFile, oversized);

        ImportTransactionJournal.Recovery recovery = newJournal().inspectForStartup();

        assertEquals(ImportTransactionJournal.RecoveryAction.QUARANTINE, recovery.getAction());
        assertEquals("Journal exceeds the maximum size", recovery.getReason());
        assertNotNull(recovery.getPreservedJournalFile());
        assertEquals(oversized.length, recovery.getPreservedJournalFile().length());
        byte[] preserved = readAll(recovery.getPreservedJournalFile());
        assertEquals('A', preserved[0]);
        assertEquals('Z', preserved[preserved.length - 1]);
        assertPreimagesStillExist();
    }

    @Test
    public void missingPreimageIsQuarantinedRatherThanReportedAsRecoverable() throws Exception {
        newJournal().write(prepared().withPhase(ImportTransactionJournal.Phase.APPLYING));
        assertTrue(new File(preimageRoot, "config.snapshot").delete());

        ImportTransactionJournal.Recovery recovery = newJournal().inspectForStartup();

        assertEquals(ImportTransactionJournal.RecoveryAction.QUARANTINE, recovery.getAction());
        assertEquals("A recorded preimage is unavailable", recovery.getReason());
        assertNotNull(recovery.getPreservedJournalFile());
        assertTrue(new File(preimageRoot, "database.json").isFile());
        assertTrue(new File(preimageRoot, "preferences.json").isFile());
    }

    @Test(expected = IllegalArgumentException.class)
    public void phaseCannotMoveBackToApplyingAfterRollbackBegins() {
        prepared().withPhase(ImportTransactionJournal.Phase.APPLYING)
                .withPhase(ImportTransactionJournal.Phase.ROLLING_BACK)
                .withPhase(ImportTransactionJournal.Phase.APPLYING);
    }

    @Test(expected = IllegalArgumentException.class)
    public void journalInsidePreimageRootIsRejected() throws Exception {
        new ImportTransactionJournal(new File(preimageRoot, "journal"), preimageRoot);
    }

    @Test(expected = IllegalArgumentException.class)
    public void duplicatePreimagePathsAreRejected() {
        paths.put(ImportTransactionJournal.Store.PREFERENCES, "database.json");
        prepared();
    }

    @Test
    public void quarantineUsesUniqueDestinationWhenAnOlderQuarantineExists() throws Exception {
        byte[] older = "older quarantine".getBytes(StandardCharsets.UTF_8);
        writeBytes(new File(journalFile.getPath() + ".quarantine"), older);
        writeBytes(journalFile, "invalid current journal".getBytes(StandardCharsets.UTF_8));

        ImportTransactionJournal.Recovery recovery = newJournal().inspectForStartup();

        assertEquals(ImportTransactionJournal.RecoveryAction.QUARANTINE, recovery.getAction());
        assertNotNull(recovery.getPreservedJournalFile());
        assertTrue(recovery.getPreservedJournalFile().getName()
                .startsWith(journalFile.getName() + ".quarantine."));
        assertFalse(journalFile.exists());
        assertArrayEquals(older, readAll(new File(journalFile.getPath() + ".quarantine")));
    }

    @Test
    public void staleOrDifferentActiveTransactionCannotReplaceDurableState() throws Exception {
        ImportTransactionJournal journal = newJournal();
        ImportTransactionJournal.Transaction applying = prepared()
                .withPhase(ImportTransactionJournal.Phase.APPLYING);
        journal.write(applying);

        try {
            journal.write(prepared());
            throw new AssertionError("A stale phase must not replace the active journal");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("regresses"));
        }

        ImportTransactionJournal.Transaction different =
                ImportTransactionJournal.Transaction.prepared(
                        "fedcba98-7654-3210-fedc-ba9876543210", 2L, paths)
                        .withPhase(ImportTransactionJournal.Phase.APPLYING);
        try {
            journal.write(different);
            throw new AssertionError("A second active transaction must not replace the journal");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("already owns"));
        }

        assertEquals(ImportTransactionJournal.RecoveryAction.RESUME_ROLLBACK,
                newJournal().inspectForStartup().getAction());
        assertEquals(applying.getTransactionId(),
                newJournal().inspectForStartup().getTransaction().getTransactionId());
    }

    @Test
    public void activeTransactionCannotSubstituteDifferentPreimagePaths() throws Exception {
        ImportTransactionJournal journal = newJournal();
        ImportTransactionJournal.Transaction applying = prepared()
                .withPhase(ImportTransactionJournal.Phase.APPLYING);
        journal.write(applying);

        Map<ImportTransactionJournal.Store, String> alternatePaths =
                new EnumMap<>(ImportTransactionJournal.Store.class);
        for (ImportTransactionJournal.Store store : ImportTransactionJournal.Store.values()) {
            String alternate = paths.get(store) + ".alternate";
            writeBytes(new File(preimageRoot, alternate), "alternate".getBytes(StandardCharsets.UTF_8));
            alternatePaths.put(store, alternate);
        }
        ImportTransactionJournal.Transaction substituted =
                ImportTransactionJournal.Transaction.prepared(
                        applying.getTransactionId(), applying.getCreatedAtEpochMillis(), alternatePaths)
                        .withPhase(ImportTransactionJournal.Phase.APPLYING);
        try {
            journal.write(substituted);
            throw new AssertionError("An active transaction must own its original preimage paths");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("regresses"));
        }

        ImportTransactionJournal.Transaction recovered = newJournal().inspectForStartup()
                .getTransaction();
        for (ImportTransactionJournal.Store store : ImportTransactionJournal.Store.values()) {
            assertEquals(paths.get(store), recovered.getPreimage(store).getRelativePath());
        }
    }

    @Test(expected = IllegalArgumentException.class)
    public void applyingStoreCannotSkipDirectlyToApplied() {
        prepared().withPhase(ImportTransactionJournal.Phase.APPLYING)
                .withStoreStatus(ImportTransactionJournal.Store.DATABASE,
                        ImportTransactionJournal.StoreStatus.APPLIED);
    }

    @Test(expected = IllegalArgumentException.class)
    public void rollbackStoreCannotRegressFromAppliedToPreimageReady() {
        prepared().withPhase(ImportTransactionJournal.Phase.APPLYING)
                .withStoreStatus(ImportTransactionJournal.Store.DATABASE,
                        ImportTransactionJournal.StoreStatus.APPLYING)
                .withStoreStatus(ImportTransactionJournal.Store.DATABASE,
                        ImportTransactionJournal.StoreStatus.APPLIED)
                .withPhase(ImportTransactionJournal.Phase.ROLLING_BACK)
                .withStoreStatus(ImportTransactionJournal.Store.DATABASE,
                        ImportTransactionJournal.StoreStatus.PREIMAGE_READY);
    }

    private void assertRestartState(ImportTransactionJournal.Store store,
                                   ImportTransactionJournal.StoreStatus status) throws Exception {
        ImportTransactionJournal.Recovery recovery = newJournal().inspectForStartup();
        assertEquals(ImportTransactionJournal.RecoveryAction.RESUME_ROLLBACK,
                recovery.getAction());
        assertEquals(status, recovery.getTransaction().getPreimage(store).getStatus());
        assertEquals(paths.get(store),
                recovery.getTransaction().getPreimage(store).getRelativePath());
    }

    private void assertQuarantinedBytes(ImportTransactionJournal.Recovery recovery, byte[] bytes)
            throws Exception {
        assertEquals(ImportTransactionJournal.RecoveryAction.QUARANTINE, recovery.getAction());
        assertNotNull(recovery.getPreservedJournalFile());
        assertTrue(recovery.getPreservedJournalFile().isFile());
        assertArrayEquals(bytes, readAll(recovery.getPreservedJournalFile()));
        assertFalse(journalFile.exists());
    }

    private void assertPreimagesStillExist() {
        for (String path : paths.values()) assertTrue(new File(preimageRoot, path).isFile());
    }

    private ImportTransactionJournal.Transaction prepared() {
        return ImportTransactionJournal.Transaction.prepared(
                "01234567-89ab-cdef-0123-456789abcdef", 1_790_000_000_000L, paths);
    }

    private ImportTransactionJournal newJournal() throws IOException {
        return new ImportTransactionJournal(journalFile, preimageRoot);
    }

    private void addPreimage(ImportTransactionJournal.Store store, String name, String contents)
            throws IOException {
        writeBytes(new File(preimageRoot, name), contents.getBytes(StandardCharsets.UTF_8));
        paths.put(store, name);
    }

    private static byte[] readAll(File file) throws IOException {
        byte[] bytes = new byte[(int) file.length()];
        try (FileInputStream input = new FileInputStream(file)) {
            int offset = 0;
            while (offset < bytes.length) {
                int count = input.read(bytes, offset, bytes.length - offset);
                if (count < 0) throw new IOException("Unexpected end of file");
                offset += count;
            }
        }
        return bytes;
    }

    private static void writeBytes(File file, byte[] bytes) throws IOException {
        try (FileOutputStream output = new FileOutputStream(file, false)) {
            output.write(bytes);
            output.getFD().sync();
        }
    }
}
