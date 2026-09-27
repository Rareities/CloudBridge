package ca.pkay.rcloneexplorer.Database.json;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Bounded, durable metadata for a multi-store backup import or restore.
 *
 * <p>The journal stores only a transaction UUID, fixed enums, a timestamp, and relative
 * preimage paths. Preimage contents and credentials are never read or serialized. Paths are
 * restricted to safe relative names beneath the supplied private preimage root.</p>
 *
 * <p>Callers must durably save every preimage before writing a PREPARED transaction, and must
 * write APPLYING before changing any store. A startup caller can then use
 * {@link #inspectForStartup()} to resume rollback from the recorded paths. This class never
 * deletes preimages or other application data.</p>
 */
public final class ImportTransactionJournal {

    public static final int FORMAT_VERSION = 1;
    public static final int MAX_JOURNAL_BYTES = 16 * 1024;
    public static final int MAX_PREIMAGE_PATH_CHARS = 512;

    private static final byte[] MAGIC = new byte[]{'C', 'B', 'I', 'J'};
    private static final int DIGEST_BYTES = 32;
    private static final int MAX_TRANSACTION_ID_CHARS = 36;
    private static final String REASON_OVERSIZED = "Journal exceeds the maximum size";
    private static final String REASON_UNKNOWN_VERSION = "Journal version is not supported";
    private static final String REASON_MALFORMED = "Journal is malformed";
    private static final String REASON_MISSING_PREIMAGE = "A recorded preimage is unavailable";
    private static final ConcurrentHashMap<String, ReentrantLock> JOURNAL_LOCKS =
            new ConcurrentHashMap<>();

    private final File journalFile;
    private final File preimageRoot;
    private final ReentrantLock journalLock;

    public enum Phase {
        PREPARED(1),
        APPLYING(2),
        ROLLING_BACK(3),
        ROLLBACK_FAILED(4),
        ROLLED_BACK(5),
        COMMITTED(6);

        private final int wireValue;

        Phase(int wireValue) {
            this.wireValue = wireValue;
        }

        private static Phase fromWireValue(int value) throws InvalidJournalException {
            for (Phase phase : values()) {
                if (phase.wireValue == value) return phase;
            }
            throw new InvalidJournalException(REASON_MALFORMED);
        }
    }

    public enum Store {
        DATABASE(1),
        PREFERENCES(2),
        CONFIG(3);

        private final int wireValue;

        Store(int wireValue) {
            this.wireValue = wireValue;
        }

        private static Store fromWireValue(int value) throws InvalidJournalException {
            for (Store store : values()) {
                if (store.wireValue == value) return store;
            }
            throw new InvalidJournalException(REASON_MALFORMED);
        }
    }

    public enum StoreStatus {
        PREIMAGE_READY(1),
        APPLYING(2),
        APPLIED(3),
        ROLLBACK_PENDING(4),
        ROLLBACK_COMPLETE(5),
        ROLLBACK_FAILED(6);

        private final int wireValue;

        StoreStatus(int wireValue) {
            this.wireValue = wireValue;
        }

        private static StoreStatus fromWireValue(int value) throws InvalidJournalException {
            for (StoreStatus status : values()) {
                if (status.wireValue == value) return status;
            }
            throw new InvalidJournalException(REASON_MALFORMED);
        }
    }

    public enum RecoveryAction {
        NO_ACTION,
        RESUME_ROLLBACK,
        QUARANTINE
    }

    /** Immutable path and status for one data store's pre-import snapshot. */
    public static final class StorePreimage {
        private final Store store;
        private final String relativePath;
        private final StoreStatus status;

        private StorePreimage(Store store, String relativePath, StoreStatus status) {
            this.store = store;
            this.relativePath = relativePath;
            this.status = status;
        }

        public Store getStore() {
            return store;
        }

        public String getRelativePath() {
            return relativePath;
        }

        public StoreStatus getStatus() {
            return status;
        }
    }

    /** Immutable, versioned transaction state suitable for persisting at each store boundary. */
    public static final class Transaction {
        private final String transactionId;
        private final long createdAtEpochMillis;
        private final Phase phase;
        private final List<StorePreimage> preimages;

        private Transaction(String transactionId, long createdAtEpochMillis, Phase phase,
                            List<StorePreimage> preimages) {
            this.transactionId = transactionId;
            this.createdAtEpochMillis = createdAtEpochMillis;
            this.phase = phase;
            this.preimages = Collections.unmodifiableList(new ArrayList<>(preimages));
        }

        /** Creates a PREPARED transaction after all three durable preimages have been saved. */
        public static Transaction prepared(Map<Store, String> relativePreimagePaths) {
            return prepared(UUID.randomUUID().toString(), System.currentTimeMillis(),
                    relativePreimagePaths);
        }

        /** Deterministic overload for callers that already have a transaction ID and clock. */
        public static Transaction prepared(String transactionId, long createdAtEpochMillis,
                                           Map<Store, String> relativePreimagePaths) {
            if (relativePreimagePaths == null || relativePreimagePaths.size() != Store.values().length) {
                throw new IllegalArgumentException("A preimage path is required for every store");
            }
            ArrayList<StorePreimage> preimages = new ArrayList<>();
            for (Store store : Store.values()) {
                String path = relativePreimagePaths.get(store);
                validateRelativePath(path);
                preimages.add(new StorePreimage(store, path, StoreStatus.PREIMAGE_READY));
            }
            Transaction transaction = new Transaction(transactionId, createdAtEpochMillis,
                    Phase.PREPARED, preimages);
            validateTransaction(transaction);
            return transaction;
        }

        public String getTransactionId() {
            return transactionId;
        }

        public long getCreatedAtEpochMillis() {
            return createdAtEpochMillis;
        }

        public Phase getPhase() {
            return phase;
        }

        public List<StorePreimage> getPreimages() {
            return preimages;
        }

        public StorePreimage getPreimage(Store store) {
            for (StorePreimage preimage : preimages) {
                if (preimage.store == store) return preimage;
            }
            return null;
        }

        public Transaction withPhase(Phase nextPhase) {
            if (nextPhase == null) throw new IllegalArgumentException("Transaction phase is missing");
            validatePhaseTransition(phase, nextPhase);
            Transaction updated = new Transaction(transactionId, createdAtEpochMillis, nextPhase,
                    preimages);
            validateTransaction(updated);
            return updated;
        }

        public Transaction withStoreStatus(Store store, StoreStatus nextStatus) {
            if (store == null || nextStatus == null) {
                throw new IllegalArgumentException("Store and status are required");
            }
            if (phase == Phase.COMMITTED || phase == Phase.ROLLED_BACK) {
                throw new IllegalStateException("Terminal transactions cannot change store status");
            }
            ArrayList<StorePreimage> updatedPreimages = new ArrayList<>(preimages.size());
            boolean found = false;
            for (StorePreimage preimage : preimages) {
                if (preimage.store == store) {
                    validateStoreStatusTransition(phase, preimage.status, nextStatus);
                    updatedPreimages.add(new StorePreimage(store, preimage.relativePath, nextStatus));
                    found = true;
                } else {
                    updatedPreimages.add(preimage);
                }
            }
            if (!found) throw new IllegalArgumentException("Store is not in the transaction");
            Transaction updated = new Transaction(transactionId, createdAtEpochMillis, phase,
                    updatedPreimages);
            validateTransaction(updated);
            return updated;
        }
    }

    /** Result of the non-destructive startup inspection. */
    public static final class Recovery {
        private final RecoveryAction action;
        private final Transaction transaction;
        private final String reason;
        private final File preservedJournalFile;

        private Recovery(RecoveryAction action, Transaction transaction, String reason,
                         File preservedJournalFile) {
            this.action = action;
            this.transaction = transaction;
            this.reason = reason;
            this.preservedJournalFile = preservedJournalFile;
        }

        public RecoveryAction getAction() {
            return action;
        }

        public Transaction getTransaction() {
            return transaction;
        }

        public String getReason() {
            return reason;
        }

        /** Null when there was no journal or no journal needed quarantine. */
        public File getPreservedJournalFile() {
            return preservedJournalFile;
        }
    }

    /**
     * @param journalFile private app file used for the bounded journal
     * @param preimageRoot private app directory containing durable preimage files
     */
    public ImportTransactionJournal(File journalFile, File preimageRoot) throws IOException {
        if (journalFile == null || preimageRoot == null) {
            throw new IllegalArgumentException("Journal and preimage paths are required");
        }
        this.journalFile = journalFile.getCanonicalFile();
        this.preimageRoot = preimageRoot.getCanonicalFile();
        String preimageRootPath = this.preimageRoot.getPath();
        String preimageRootPrefix = preimageRootPath.endsWith(File.separator)
                ? preimageRootPath : preimageRootPath + File.separator;
        if (this.journalFile.equals(this.preimageRoot)
                || this.journalFile.getPath().startsWith(preimageRootPrefix)) {
            throw new IllegalArgumentException(
                    "Journal must be outside the private preimage root");
        }
        this.journalLock = lockFor(this.journalFile.getPath());
    }

    /** Atomically replaces the journal after validating its fixed schema and preimage files. */
    public void write(Transaction transaction) throws IOException {
        journalLock.lock();
        try {
            if (transaction == null) throw new IllegalArgumentException("Transaction is missing");
            validateTransaction(transaction);
            try {
                validatePreimageFiles(transaction);
            } catch (MissingPreimageException missing) {
                throw new IOException(REASON_MISSING_PREIMAGE, missing);
            }
            validateWriteAgainstPersisted(readPersistedTransaction(), transaction);
            byte[] bytes = encode(transaction);
            if (bytes.length > MAX_JOURNAL_BYTES) {
                throw new IOException(REASON_OVERSIZED);
            }
            writeAtomically(bytes);
        } finally {
            journalLock.unlock();
        }
    }

    /**
     * Reads at most {@link #MAX_JOURNAL_BYTES} and returns a rollback decision. Invalid,
     * unsupported, oversized, or incomplete journals are preserved in a quarantine file when
     * possible. No preimage path is ever deleted or modified.
     */
    public Recovery inspectForStartup() throws IOException {
        journalLock.lock();
        try {
            File source = journalFile;
            if (!source.exists()) {
                source = backupFile();
                if (!source.exists()) {
                    return new Recovery(RecoveryAction.NO_ACTION, null,
                            "No pending import transaction", null);
                }
            }

            final Transaction transaction;
            try {
                transaction = decode(readBounded(source));
                validatePreimageFiles(transaction);
            } catch (InvalidJournalException invalid) {
                return quarantine(source, invalid.reason);
            } catch (MissingPreimageException missing) {
                return quarantine(source, REASON_MISSING_PREIMAGE);
            }

            switch (transaction.phase) {
                case APPLYING:
                case ROLLING_BACK:
                case ROLLBACK_FAILED:
                    return new Recovery(RecoveryAction.RESUME_ROLLBACK, transaction,
                            "Import did not reach a terminal state", source);
                case PREPARED:
                    return new Recovery(RecoveryAction.NO_ACTION, transaction,
                            "No store mutation was recorded", source);
                case COMMITTED:
                case ROLLED_BACK:
                    return new Recovery(RecoveryAction.NO_ACTION, transaction,
                            "Transaction is already terminal", source);
                default:
                    // Defensive even though decode rejects unknown enum values.
                    return quarantine(source, REASON_MALFORMED);
            }
        } finally {
            journalLock.unlock();
        }
    }

    private void writeAtomically(byte[] bytes) throws IOException {
        File parent = journalFile.getParentFile();
        if (parent == null || (!parent.isDirectory() && !parent.mkdirs() && !parent.isDirectory())) {
            throw new IOException("Unable to create journal directory");
        }

        File temporary = temporaryFile();
        File backup = backupFile();
        try {
            try (FileOutputStream output = new FileOutputStream(temporary, false)) {
                output.write(bytes);
                output.flush();
                output.getFD().sync();
            }

            boolean movedCurrentJournal = false;
            if (journalFile.exists()) {
                // The base file remains authoritative until its rename succeeds. A leftover backup
                // is an older journal copy and can be removed without touching any preimage data.
                if (backup.exists() && !backup.delete()) {
                    throw new IOException("Unable to rotate prior journal backup");
                }
                if (!journalFile.renameTo(backup)) {
                    throw new IOException("Unable to preserve prior journal before replacement");
                }
                movedCurrentJournal = true;
            }

            if (!temporary.renameTo(journalFile)) {
                IOException failure = new IOException("Unable to install updated journal");
                if (movedCurrentJournal && !journalFile.exists() && !backup.renameTo(journalFile)) {
                    failure.addSuppressed(new IOException("Unable to restore prior journal"));
                }
                throw failure;
            }

            // The new base is fully written and synced. A stale backup is only journal metadata;
            // directory-entry durability remains platform dependent after the atomic rename.
            if (backup.exists()) backup.delete();
        } finally {
            if (temporary.exists()) temporary.delete();
        }
    }

    /** Reads the current base journal, or its backup only when the base is absent. */
    private Transaction readPersistedTransaction() throws IOException {
        File source = journalFile.exists() ? journalFile : backupFile();
        if (!source.exists()) return null;
        try {
            return decode(readBounded(source));
        } catch (InvalidJournalException invalid) {
            throw new IOException("Existing journal is invalid; refusing to overwrite it",
                    invalid);
        }
    }

    /** Prevents stale callers or a second active transaction from replacing durable state. */
    private static void validateWriteAgainstPersisted(Transaction persisted,
                                                       Transaction requested)
            throws IOException {
        if (persisted == null) return;
        if (!persisted.transactionId.equals(requested.transactionId)) {
            if (!isTerminal(persisted.phase)) {
                throw new IOException("An active import transaction already owns the journal");
            }
            return;
        }
        if (persisted.createdAtEpochMillis != requested.createdAtEpochMillis) {
            throw new IOException("Transaction metadata does not match the persisted journal");
        }
        try {
            validatePhaseTransition(persisted.phase, requested.phase);
            for (Store store : Store.values()) {
                StorePreimage persistedPreimage = persisted.getPreimage(store);
                StorePreimage requestedPreimage = requested.getPreimage(store);
                if (!persistedPreimage.relativePath.equals(requestedPreimage.relativePath)) {
                    throw new IllegalArgumentException(
                            "Transaction preimage paths do not match persisted ownership");
                }
                StoreStatus current = persistedPreimage.status;
                StoreStatus next = requestedPreimage.status;
                validateStoreStatusTransition(requested.phase, current, next);
            }
        } catch (IllegalArgumentException invalid) {
            throw new IOException("Requested journal state regresses persisted state", invalid);
        }
    }

    private static boolean isTerminal(Phase phase) {
        return phase == Phase.COMMITTED || phase == Phase.ROLLED_BACK;
    }

    private Recovery quarantine(File source, String reason) throws IOException {
        File destination;
        do {
            destination = new File(source.getPath() + ".quarantine." + UUID.randomUUID());
        } while (destination.exists());
        if (!source.renameTo(destination)) {
            throw new IOException("Unable to preserve invalid journal for inspection");
        }
        return new Recovery(RecoveryAction.QUARANTINE, null, reason, destination);
    }

    private Transaction decode(byte[] bytes) throws InvalidJournalException {
        if (bytes.length > MAX_JOURNAL_BYTES) {
            throw new InvalidJournalException(REASON_OVERSIZED);
        }
        if (bytes.length < MAGIC.length + 2 + DIGEST_BYTES) {
            throw new InvalidJournalException(REASON_MALFORMED);
        }
        for (int i = 0; i < MAGIC.length; i++) {
            if (bytes[i] != MAGIC[i]) throw new InvalidJournalException(REASON_MALFORMED);
        }

        int version = ((bytes[MAGIC.length] & 0xff) << 8)
                | (bytes[MAGIC.length + 1] & 0xff);
        if (version != FORMAT_VERSION) {
            throw new InvalidJournalException(REASON_UNKNOWN_VERSION);
        }

        int payloadLength = bytes.length - DIGEST_BYTES;
        byte[] payload = Arrays.copyOf(bytes, payloadLength);
        byte[] storedDigest = Arrays.copyOfRange(bytes, payloadLength, bytes.length);
        final byte[] expectedDigest;
        try {
            expectedDigest = sha256(payload);
        } catch (IOException impossible) {
            throw new InvalidJournalException(REASON_MALFORMED);
        }
        if (!MessageDigest.isEqual(storedDigest, expectedDigest)) {
            throw new InvalidJournalException(REASON_MALFORMED);
        }

        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(payload))) {
            byte[] magic = new byte[MAGIC.length];
            input.readFully(magic);
            int decodedVersion = input.readUnsignedShort();
            if (decodedVersion != FORMAT_VERSION) {
                throw new InvalidJournalException(REASON_UNKNOWN_VERSION);
            }
            String transactionId = readString(input, MAX_TRANSACTION_ID_CHARS);
            long createdAt = input.readLong();
            Phase phase = Phase.fromWireValue(input.readUnsignedByte());
            int count = input.readUnsignedByte();
            if (count != Store.values().length) {
                throw new InvalidJournalException(REASON_MALFORMED);
            }

            ArrayList<StorePreimage> preimages = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                Store store = Store.fromWireValue(input.readUnsignedByte());
                StoreStatus status = StoreStatus.fromWireValue(input.readUnsignedByte());
                String path = readString(input, MAX_PREIMAGE_PATH_CHARS);
                preimages.add(new StorePreimage(store, path, status));
            }
            if (input.available() != 0) throw new InvalidJournalException(REASON_MALFORMED);

            Transaction transaction = new Transaction(transactionId, createdAt, phase, preimages);
            try {
                validateTransaction(transaction);
            } catch (IllegalArgumentException invalid) {
                throw new InvalidJournalException(REASON_MALFORMED);
            }
            return transaction;
        } catch (InvalidJournalException invalid) {
            throw invalid;
        } catch (IllegalArgumentException | IOException e) {
            throw new InvalidJournalException(REASON_MALFORMED);
        }
    }

    private static byte[] encode(Transaction transaction) throws IOException {
        ByteArrayOutputStream payloadBuffer = new ByteArrayOutputStream();
        try (DataOutputStream output = new DataOutputStream(payloadBuffer)) {
            output.write(MAGIC);
            output.writeShort(FORMAT_VERSION);
            writeString(output, transaction.transactionId);
            output.writeLong(transaction.createdAtEpochMillis);
            output.writeByte(transaction.phase.wireValue);
            output.writeByte(transaction.preimages.size());
            for (StorePreimage preimage : transaction.preimages) {
                output.writeByte(preimage.store.wireValue);
                output.writeByte(preimage.status.wireValue);
                writeString(output, preimage.relativePath);
            }
        }
        byte[] payload = payloadBuffer.toByteArray();
        ByteArrayOutputStream result = new ByteArrayOutputStream(payload.length + DIGEST_BYTES);
        result.write(payload);
        result.write(sha256(payload));
        return result.toByteArray();
    }

    private void validatePreimageFiles(Transaction transaction)
            throws IOException, MissingPreimageException {
        if (!preimageRoot.isDirectory()) throw new MissingPreimageException();
        String rootPath = preimageRoot.getCanonicalPath();
        String rootPrefix = rootPath.endsWith(File.separator)
                ? rootPath : rootPath + File.separator;
        for (StorePreimage preimage : transaction.preimages) {
            File candidate = new File(preimageRoot,
                    preimage.relativePath.replace('/', File.separatorChar)).getCanonicalFile();
            if (!candidate.getPath().startsWith(rootPrefix) || !candidate.isFile()) {
                throw new MissingPreimageException();
            }
        }
    }

    private byte[] readBounded(File source) throws IOException, InvalidJournalException {
        long size = source.length();
        if (size > MAX_JOURNAL_BYTES) throw new InvalidJournalException(REASON_OVERSIZED);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream((int) Math.max(0, size));
        byte[] buffer = new byte[1024];
        try (FileInputStream input = new FileInputStream(source)) {
            int count;
            while ((count = input.read(buffer)) != -1) {
                if (bytes.size() + count > MAX_JOURNAL_BYTES) {
                    throw new InvalidJournalException(REASON_OVERSIZED);
                }
                bytes.write(buffer, 0, count);
            }
        }
        return bytes.toByteArray();
    }

    private static void validateTransaction(Transaction transaction) {
        if (transaction.transactionId == null
                || !transaction.transactionId.matches(
                "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
                || transaction.createdAtEpochMillis < 0
                || transaction.phase == null
                || transaction.preimages == null
                || transaction.preimages.size() != Store.values().length) {
            throw new IllegalArgumentException("Transaction metadata is invalid");
        }

        Set<String> paths = new HashSet<>();
        for (int i = 0; i < Store.values().length; i++) {
            StorePreimage preimage = transaction.preimages.get(i);
            if (preimage.store != Store.values()[i] || preimage.status == null) {
                throw new IllegalArgumentException("Transaction store records are invalid");
            }
            validateRelativePath(preimage.relativePath);
            if (!paths.add(preimage.relativePath)) {
                throw new IllegalArgumentException("Transaction preimage paths must be unique");
            }
        }

        switch (transaction.phase) {
            case PREPARED:
                requireStatuses(transaction, StoreStatus.PREIMAGE_READY);
                break;
            case APPLYING:
                requireOnlyStatuses(transaction, StoreStatus.PREIMAGE_READY,
                        StoreStatus.APPLYING, StoreStatus.APPLIED);
                break;
            case ROLLING_BACK:
                requireOnlyStatuses(transaction, StoreStatus.PREIMAGE_READY,
                        StoreStatus.APPLYING, StoreStatus.APPLIED,
                        StoreStatus.ROLLBACK_PENDING, StoreStatus.ROLLBACK_COMPLETE,
                        StoreStatus.ROLLBACK_FAILED);
                break;
            case ROLLBACK_FAILED:
                requireOnlyStatuses(transaction, StoreStatus.PREIMAGE_READY,
                        StoreStatus.APPLYING, StoreStatus.APPLIED,
                        StoreStatus.ROLLBACK_PENDING, StoreStatus.ROLLBACK_COMPLETE,
                        StoreStatus.ROLLBACK_FAILED);
                boolean hasFailure = false;
                for (StorePreimage preimage : transaction.preimages) {
                    hasFailure |= preimage.status == StoreStatus.ROLLBACK_FAILED;
                }
                if (!hasFailure) throw new IllegalArgumentException("Rollback failure is missing");
                break;
            case ROLLED_BACK:
                requireStatuses(transaction, StoreStatus.ROLLBACK_COMPLETE);
                break;
            case COMMITTED:
                requireStatuses(transaction, StoreStatus.APPLIED);
                break;
            default:
                throw new IllegalArgumentException("Transaction phase is unknown");
        }
    }

    private static void validatePhaseTransition(Phase current, Phase next) {
        if (current == next) return;
        boolean allowed;
        switch (current) {
            case PREPARED:
                allowed = next == Phase.APPLYING;
                break;
            case APPLYING:
                allowed = next == Phase.ROLLING_BACK || next == Phase.COMMITTED;
                break;
            case ROLLING_BACK:
                allowed = next == Phase.ROLLBACK_FAILED || next == Phase.ROLLED_BACK;
                break;
            case ROLLBACK_FAILED:
                allowed = next == Phase.ROLLING_BACK || next == Phase.ROLLED_BACK;
                break;
            case ROLLED_BACK:
            case COMMITTED:
                allowed = false;
                break;
            default:
                allowed = false;
        }
        if (!allowed) {
            throw new IllegalArgumentException(
                    "Invalid transaction phase transition: " + current + " -> " + next);
        }
    }

    /** Enforces per-store boundaries instead of only checking phase-wide enum membership. */
    private static void validateStoreStatusTransition(Phase phase, StoreStatus current,
                                                       StoreStatus next) {
        if (current == next) return;
        boolean allowed = false;
        if (phase == Phase.APPLYING) {
            allowed = (current == StoreStatus.PREIMAGE_READY
                    && next == StoreStatus.APPLYING)
                    || (current == StoreStatus.APPLYING
                    && next == StoreStatus.APPLIED);
        } else if (phase == Phase.ROLLING_BACK || phase == Phase.ROLLBACK_FAILED) {
            allowed = (current == StoreStatus.PREIMAGE_READY
                    && (next == StoreStatus.ROLLBACK_PENDING
                    || next == StoreStatus.ROLLBACK_COMPLETE))
                    || ((current == StoreStatus.APPLYING
                    || current == StoreStatus.APPLIED)
                    && next == StoreStatus.ROLLBACK_PENDING)
                    || (current == StoreStatus.ROLLBACK_PENDING
                    && (next == StoreStatus.ROLLBACK_COMPLETE
                    || next == StoreStatus.ROLLBACK_FAILED))
                    || (current == StoreStatus.ROLLBACK_FAILED
                    && (next == StoreStatus.ROLLBACK_PENDING
                    || next == StoreStatus.ROLLBACK_COMPLETE));
        }
        if (!allowed) {
            throw new IllegalArgumentException(
                    "Invalid store status transition: " + current + " -> " + next);
        }
    }

    private static ReentrantLock lockFor(String journalPath) {
        ReentrantLock existing = JOURNAL_LOCKS.get(journalPath);
        if (existing != null) return existing;
        ReentrantLock created = new ReentrantLock();
        ReentrantLock raced = JOURNAL_LOCKS.putIfAbsent(journalPath, created);
        return raced == null ? created : raced;
    }

    private static void requireStatuses(Transaction transaction, StoreStatus required) {
        for (StorePreimage preimage : transaction.preimages) {
            if (preimage.status != required) {
                throw new IllegalArgumentException("Transaction status does not match phase");
            }
        }
    }

    private static void requireOnlyStatuses(Transaction transaction, StoreStatus... allowed) {
        for (StorePreimage preimage : transaction.preimages) {
            boolean found = false;
            for (StoreStatus status : allowed) found |= preimage.status == status;
            if (!found) throw new IllegalArgumentException("Transaction status is invalid");
        }
    }

    private static void validateRelativePath(String path) {
        if (path == null || path.isEmpty() || path.length() > MAX_PREIMAGE_PATH_CHARS
                || path.startsWith("/") || path.indexOf('\\') >= 0 || path.indexOf(':') >= 0) {
            throw new IllegalArgumentException("Preimage path is invalid");
        }
        String[] segments = path.split("/", -1);
        for (String segment : segments) {
            if (segment.isEmpty() || segment.equals(".") || segment.equals("..")) {
                throw new IllegalArgumentException("Preimage path is invalid");
            }
            for (int i = 0; i < segment.length(); i++) {
                char character = segment.charAt(i);
                if (!((character >= 'a' && character <= 'z')
                        || (character >= 'A' && character <= 'Z')
                        || (character >= '0' && character <= '9')
                        || character == '.' || character == '_' || character == '-')) {
                    throw new IllegalArgumentException("Preimage path is invalid");
                }
            }
        }
    }

    private static void writeString(DataOutputStream output, String value) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        output.writeInt(bytes.length);
        output.write(bytes);
    }

    private static String readString(DataInputStream input, int maxChars)
            throws IOException, InvalidJournalException, CharacterCodingException {
        int length = input.readInt();
        if (length <= 0 || length > maxChars * 4 || length > input.available()) {
            throw new InvalidJournalException(REASON_MALFORMED);
        }
        byte[] bytes = new byte[length];
        input.readFully(bytes);
        String value = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString();
        if (value.length() > maxChars) throw new InvalidJournalException(REASON_MALFORMED);
        return value;
    }

    private static byte[] sha256(byte[] bytes) throws IOException {
        try {
            return MessageDigest.getInstance("SHA-256").digest(bytes);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IOException("SHA-256 is unavailable", impossible);
        }
    }

    private File temporaryFile() {
        File parent = journalFile.getParentFile();
        String name = journalFile.getName() + "." + UUID.randomUUID() + ".new";
        return new File(parent == null ? new File(".") : parent, name);
    }

    private File backupFile() {
        return new File(journalFile.getPath() + ".bak");
    }

    private static final class InvalidJournalException extends Exception {
        private final String reason;

        private InvalidJournalException(String reason) {
            this.reason = reason;
        }
    }

    private static final class MissingPreimageException extends Exception {}
}
