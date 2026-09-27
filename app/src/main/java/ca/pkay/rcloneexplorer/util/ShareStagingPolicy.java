package ca.pkay.rcloneexplorer.util;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/** Android-independent policy for safely staging files received from external share intents. */
public final class ShareStagingPolicy {
    public static final long HARD_MAX_BYTES_PER_FILE = 4L * 1024L * 1024L * 1024L;
    public static final long HARD_MAX_TOTAL_BYTES = 8L * 1024L * 1024L * 1024L;
    public static final int HARD_MAX_FILES = 128;
    public static final int HARD_MAX_FILENAME_CODE_POINTS = 240;
    public static final int HARD_MAX_FILENAME_UTF8_BYTES = 255;

    private static final int COPY_BUFFER_SIZE = 32 * 1024;
    private static final int DIRECTORY_ATTEMPTS = 8;
    private static final int PARTIAL_FILE_ATTEMPTS = 8;

    public static final Limits DEFAULT_LIMITS = new Limits(
            HARD_MAX_BYTES_PER_FILE,
            HARD_MAX_TOTAL_BYTES,
            HARD_MAX_FILES,
            HARD_MAX_FILENAME_CODE_POINTS
    );

    private ShareStagingPolicy() {
    }

    @FunctionalInterface
    public interface InputStreamFactory {
        InputStream open() throws IOException;
    }

    @FunctionalInterface
    public interface CancellationCheck {
        boolean isCancelled();
    }

    public static final class LimitExceededException extends IOException {
        public LimitExceededException(String message) {
            super(message);
        }
    }

    public static final class Source {
        private final String displayName;
        private final InputStreamFactory inputStreamFactory;

        public Source(String displayName, InputStreamFactory inputStreamFactory) {
            if (inputStreamFactory == null) {
                throw new NullPointerException("inputStreamFactory");
            }
            this.displayName = displayName;
            this.inputStreamFactory = inputStreamFactory;
        }
    }

    /** Test callers may lower limits, but cannot raise the production hard ceilings. */
    public static final class Limits {
        private final long maxBytesPerFile;
        private final long maxTotalBytes;
        private final int maxFiles;
        private final int maxFilenameCodePoints;

        public Limits(long maxBytesPerFile, long maxTotalBytes, int maxFiles,
                      int maxFilenameCodePoints) {
            if (maxBytesPerFile < 0 || maxBytesPerFile > HARD_MAX_BYTES_PER_FILE) {
                throw new IllegalArgumentException("maxBytesPerFile is outside the hard limit");
            }
            if (maxTotalBytes < 0 || maxTotalBytes > HARD_MAX_TOTAL_BYTES) {
                throw new IllegalArgumentException("maxTotalBytes is outside the hard limit");
            }
            if (maxFiles < 0 || maxFiles > HARD_MAX_FILES) {
                throw new IllegalArgumentException("maxFiles is outside the hard limit");
            }
            if (maxFilenameCodePoints < 1 ||
                    maxFilenameCodePoints > HARD_MAX_FILENAME_CODE_POINTS) {
                throw new IllegalArgumentException("maxFilenameCodePoints is outside the hard limit");
            }
            this.maxBytesPerFile = maxBytesPerFile;
            this.maxTotalBytes = maxTotalBytes;
            this.maxFiles = maxFiles;
            this.maxFilenameCodePoints = maxFilenameCodePoints;
        }

        public long getMaxBytesPerFile() {
            return maxBytesPerFile;
        }

        public long getMaxTotalBytes() {
            return maxTotalBytes;
        }

        public int getMaxFiles() {
            return maxFiles;
        }

        public int getMaxFilenameCodePoints() {
            return maxFilenameCodePoints;
        }
    }

    /** A completed invocation. Cleanup only removes files owned by this staged share. */
    public static final class StagedShare {
        private final File cacheDirectory;
        private final File directory;
        private final List<File> files;
        private boolean cleaned;

        private StagedShare(File cacheDirectory, File directory, List<File> files) {
            this.cacheDirectory = cacheDirectory;
            this.directory = directory;
            this.files = Collections.unmodifiableList(new ArrayList<>(files));
        }

        public File getDirectory() {
            return directory;
        }

        public List<File> getFiles() {
            return files;
        }

        /** Idempotently removes this invocation's staged files and its now-empty directory. */
        public synchronized void cleanup() throws IOException {
            if (cleaned) {
                return;
            }
            verifyInvocationDirectory(cacheDirectory, directory);

            IOException failure = null;
            for (File file : files) {
                try {
                    verifyDirectChild(directory, file);
                    if (file.exists() && !file.delete()) {
                        throw new IOException("Unable to remove a staged share file");
                    }
                } catch (IOException e) {
                    failure = appendFailure(failure, e);
                }
            }
            if (failure == null) {
                if (directory.exists() && !directory.delete()) {
                    failure = new IOException("Unable to remove the staged share directory");
                } else {
                    cleaned = true;
                }
            }
            if (failure != null) {
                throw failure;
            }
        }
    }

    /** Copies every source into a fresh private child directory, publishing only completed files. */
    public static StagedShare stage(File appCacheDir, List<Source> sources, Limits limits)
            throws IOException {
        return stage(appCacheDir, sources, limits, () -> false);
    }

    public static StagedShare stage(File appCacheDir, List<Source> sources, Limits limits,
                                    CancellationCheck cancellationCheck) throws IOException {
        if (appCacheDir == null) {
            throw new NullPointerException("appCacheDir");
        }
        if (sources == null) {
            throw new NullPointerException("sources");
        }
        if (limits == null) {
            throw new NullPointerException("limits");
        }
        if (cancellationCheck == null) {
            throw new NullPointerException("cancellationCheck");
        }

        List<Source> sourceSnapshot = new ArrayList<>(sources);
        if (sourceSnapshot.isEmpty()) {
            throw new IOException("Share contains no files");
        }
        if (sourceSnapshot.size() > limits.maxFiles) {
            throw new LimitExceededException("Share contains more files than the configured limit");
        }
        for (Source source : sourceSnapshot) {
            if (source == null) {
                throw new IOException("Share contains an invalid source");
            }
        }

        File cacheDirectory = appCacheDir.getCanonicalFile();
        if (!cacheDirectory.isDirectory()) {
            throw new IOException("Share cache is not an existing directory");
        }

        List<File> ownedArtifacts = new ArrayList<>(sourceSnapshot.size() * 2);
        List<File> publishedFiles = new ArrayList<>(sourceSnapshot.size());
        Set<String> usedNameKeys = new HashSet<>();
        File invocationDirectory = createInvocationDirectory(cacheDirectory);
        long totalBytes = 0L;

        try {
            for (Source source : sourceSnapshot) {
                throwIfCancelled(cancellationCheck);
                File partial = createPartialFile(invocationDirectory);
                ownedArtifacts.add(partial);
                String publishedName = chooseAvailableName(
                        source.displayName, invocationDirectory, usedNameKeys,
                        limits.maxFilenameCodePoints
                );

                long remainingTotal = limits.maxTotalBytes - totalBytes;
                long copiedBytes = copyBounded(source, partial, limits.maxBytesPerFile,
                        remainingTotal, cancellationCheck);
                // Both bounds were checked before each addition in copyBounded.
                totalBytes += copiedBytes;

                File published = new File(invocationDirectory, publishedName);
                verifyDirectChild(invocationDirectory, published);
                if (published.exists()) {
                    throw new IOException("A staged share filename unexpectedly already exists");
                }

                // Track the destination before rename so even an asynchronous failure after the
                // move can be cleaned without scanning or deleting unrelated cache contents.
                ownedArtifacts.add(published);
                if (!partial.renameTo(published)) {
                    ownedArtifacts.remove(published);
                    throw new IOException("Unable to publish a completed staged share file");
                }
                ownedArtifacts.remove(partial);
                publishedFiles.add(published);
                usedNameKeys.add(collisionKey(publishedName));
            }
            return new StagedShare(cacheDirectory, invocationDirectory, publishedFiles);
        } catch (IOException e) {
            cleanupAfterFailure(cacheDirectory, invocationDirectory, ownedArtifacts, e);
            throw e;
        } catch (RuntimeException e) {
            cleanupAfterFailure(cacheDirectory, invocationDirectory, ownedArtifacts, e);
            throw e;
        } catch (Error e) {
            cleanupAfterFailure(cacheDirectory, invocationDirectory, ownedArtifacts, e);
            throw e;
        }
    }

    private static File createInvocationDirectory(File cacheDirectory) throws IOException {
        for (int attempt = 0; attempt < DIRECTORY_ATTEMPTS; attempt++) {
            File candidate = new File(cacheDirectory, "share-" + UUID.randomUUID());
            if (!candidate.mkdir()) {
                continue;
            }
            try {
                verifyInvocationDirectory(cacheDirectory, candidate);
                return candidate.getCanonicalFile();
            } catch (IOException e) {
                // The path was just created by this call. delete() removes a substituted symlink
                // itself rather than traversing its target.
                candidate.delete();
                throw e;
            } catch (RuntimeException e) {
                candidate.delete();
                throw e;
            } catch (Error e) {
                candidate.delete();
                throw e;
            }
        }
        throw new IOException("Unable to create a unique staged share directory");
    }

    private static File createPartialFile(File invocationDirectory) throws IOException {
        for (int attempt = 0; attempt < PARTIAL_FILE_ATTEMPTS; attempt++) {
            File partial = new File(invocationDirectory, ".partial-" + UUID.randomUUID());
            if (!partial.createNewFile()) {
                continue;
            }
            try {
                verifyDirectChild(invocationDirectory, partial);
                return partial.getCanonicalFile();
            } catch (IOException e) {
                partial.delete();
                throw e;
            } catch (RuntimeException e) {
                partial.delete();
                throw e;
            } catch (Error e) {
                partial.delete();
                throw e;
            }
        }
        throw new IOException("Unable to create a unique partial share file");
    }

    private static long copyBounded(Source source, File partial, long perFileLimit,
                                    long remainingTotalLimit,
                                    CancellationCheck cancellationCheck) throws IOException {
        InputStream opened = source.inputStreamFactory.open();
        if (opened == null) {
            throw new IOException("Share source did not provide an input stream");
        }

        long copied = 0L;
        byte[] buffer = new byte[COPY_BUFFER_SIZE];
        try (InputStream input = opened;
             OutputStream output = new BufferedOutputStream(
                     new FileOutputStream(partial), COPY_BUFFER_SIZE)) {
            while (true) {
                throwIfCancelled(cancellationCheck);
                int count = input.read(buffer, 0, buffer.length);
                if (count < 0) {
                    break;
                }
                if (count == 0) {
                    int oneByte = input.read();
                    if (oneByte < 0) {
                        break;
                    }
                    ensureWithinLimits(1L, copied, perFileLimit, remainingTotalLimit);
                    output.write(oneByte);
                    copied++;
                    continue;
                }

                ensureWithinLimits(count, copied, perFileLimit, remainingTotalLimit);
                output.write(buffer, 0, count);
                copied += count;
            }
        }
        return copied;
    }

    private static void throwIfCancelled(CancellationCheck cancellationCheck)
            throws InterruptedIOException {
        if (Thread.currentThread().isInterrupted() || cancellationCheck.isCancelled()) {
            throw new InterruptedIOException("Share staging was cancelled");
        }
    }

    private static void ensureWithinLimits(long nextCount, long copied, long perFileLimit,
                                          long remainingTotalLimit) throws IOException {
        // Subtraction avoids overflow even if a source stream is larger than Long.MAX_VALUE over
        // time (and copied never exceeds either non-negative limit).
        if (nextCount > perFileLimit - copied) {
            throw new LimitExceededException("Share file exceeds the configured per-file limit");
        }
        if (nextCount > remainingTotalLimit - copied) {
            throw new LimitExceededException("Share exceeds the configured aggregate size limit");
        }
    }

    private static String chooseAvailableName(String displayName, File directory,
                                             Set<String> usedNameKeys, int maxCodePoints)
            throws IOException {
        String safeName = sanitizeBasename(displayName);
        String stem = safeName;
        String extension = "";
        int dot = safeName.lastIndexOf('.');
        if (dot > 0 && dot < safeName.length() - 1) {
            stem = safeName.substring(0, dot);
            extension = safeName.substring(dot);
        }

        for (int duplicate = 1; duplicate <= HARD_MAX_FILES + 1; duplicate++) {
            String suffix = duplicate == 1 ? "" : " (" + duplicate + ")";
            String candidateName = fitName(stem, extension, suffix, maxCodePoints);
            File candidate = new File(directory, candidateName);
            verifyDirectChild(directory, candidate);
            if (!usedNameKeys.contains(collisionKey(candidateName)) && !candidate.exists()) {
                return candidateName;
            }
        }
        throw new IOException("Unable to resolve a unique staged share filename");
    }

    private static String sanitizeBasename(String displayName) {
        String supplied = displayName == null ? "" : displayName;
        String portableSeparators = supplied.replace('\\', '/');
        int lastSeparator = portableSeparators.lastIndexOf('/');
        String basename = portableSeparators.substring(lastSeparator + 1);

        StringBuilder safe = new StringBuilder(basename.length());
        for (int offset = 0; offset < basename.length();) {
            int codePoint = basename.codePointAt(offset);
            offset += Character.charCount(codePoint);
            if (codePoint == '/' || codePoint == '\\' || codePoint == ':' ||
                    codePoint == '*' || codePoint == '?' || codePoint == '"' ||
                    codePoint == '<' || codePoint == '>' || codePoint == '|' ||
                    Character.isISOControl(codePoint) ||
                    (codePoint >= Character.MIN_SURROGATE &&
                            codePoint <= Character.MAX_SURROGATE)) {
                safe.append('_');
            } else {
                safe.appendCodePoint(codePoint);
            }
        }

        String result = safe.toString().trim();
        if (result.isEmpty() || ".".equals(result) || "..".equals(result)) {
            return "shared-file";
        }
        return result;
    }

    private static String fitName(String stem, String extension, String suffix, int maxCodePoints)
            throws IOException {
        int suffixLength = codePointCount(suffix);
        if (suffixLength >= maxCodePoints) {
            throw new IOException("Filename limit is too small to resolve a duplicate safely");
        }
        int available = maxCodePoints - suffixLength;
        int extensionLength = codePointCount(extension);
        String fittedStem;
        String fittedExtension;
        if (extensionLength < available) {
            fittedStem = truncateCodePoints(stem, available - extensionLength);
            fittedExtension = extension;
        } else {
            fittedStem = "";
            fittedExtension = truncateCodePoints(extension, available);
        }

        String result = fittedStem + suffix + fittedExtension;
        while (!result.isEmpty() && (codePointCount(result) > maxCodePoints ||
                result.getBytes(StandardCharsets.UTF_8).length > HARD_MAX_FILENAME_UTF8_BYTES)) {
            // Keep the duplicate suffix intact, prefer preserving the extension, and trim by
            // Unicode code point so supplementary characters are never split.
            if (!fittedStem.isEmpty()) {
                fittedStem = truncateCodePoints(fittedStem, codePointCount(fittedStem) - 1);
            } else if (!fittedExtension.isEmpty()) {
                fittedExtension = truncateCodePoints(
                        fittedExtension, codePointCount(fittedExtension) - 1);
            } else {
                break;
            }
            result = fittedStem + suffix + fittedExtension;
        }
        if (result.isEmpty() || codePointCount(result) > maxCodePoints ||
                result.getBytes(StandardCharsets.UTF_8).length > HARD_MAX_FILENAME_UTF8_BYTES) {
            throw new IOException("Unable to fit a safe staged share filename");
        }
        return result;
    }

    private static int codePointCount(String value) {
        return value.codePointCount(0, value.length());
    }

    private static String truncateCodePoints(String value, int maxCodePoints) {
        if (maxCodePoints <= 0) {
            return "";
        }
        int count = codePointCount(value);
        if (count <= maxCodePoints) {
            return value;
        }
        return value.substring(0, value.offsetByCodePoints(0, maxCodePoints));
    }

    private static String collisionKey(String name) {
        return Normalizer.normalize(name, Normalizer.Form.NFC).toLowerCase(Locale.ROOT);
    }

    /** Removes one share-staged upload source after its worker reaches a terminal result. */
    public static void cleanupUploadedFile(File appCacheDir, File stagedFile) throws IOException {
        if (appCacheDir == null || stagedFile == null) {
            throw new NullPointerException("cache directory and staged file are required");
        }
        File cacheDirectory = appCacheDir.getCanonicalFile();
        if (!cacheDirectory.isDirectory()) {
            throw new IOException("Share cache is not an existing directory");
        }

        File absoluteFile = stagedFile.getAbsoluteFile();
        File invocationDirectory = absoluteFile.getParentFile();
        if (invocationDirectory == null) {
            throw new IOException("Staged upload source has no invocation directory");
        }
        verifyInvocationDirectory(cacheDirectory, invocationDirectory);
        verifyDirectChild(invocationDirectory, absoluteFile);
        if (absoluteFile.exists()) {
            if (!absoluteFile.isFile() || !absoluteFile.delete()) {
                throw new IOException("Unable to remove the completed staged upload source");
            }
        }

        String[] remaining = invocationDirectory.list();
        if (remaining != null && remaining.length == 0 &&
                invocationDirectory.exists() && !invocationDirectory.delete()) {
            throw new IOException("Unable to remove the empty staged share directory");
        }
    }

    private static void verifyInvocationDirectory(File cacheDirectory, File directory)
            throws IOException {
        File canonicalCache = cacheDirectory.getCanonicalFile();
        File canonicalDirectory = directory.getCanonicalFile();
        File absoluteDirectory = directory.getAbsoluteFile();
        File parent = canonicalDirectory.getParentFile();
        if (!absoluteDirectory.equals(canonicalDirectory) || parent == null ||
                !parent.equals(canonicalCache) ||
                !isOwnedShareDirectoryName(canonicalDirectory.getName()) ||
                canonicalDirectory.equals(canonicalCache)) {
            throw new IOException("Staged share directory escaped the app cache");
        }
    }

    private static boolean isOwnedShareDirectoryName(String name) {
        if (!name.startsWith("share-")) {
            return false;
        }
        String id = name.substring("share-".length());
        try {
            return UUID.fromString(id).toString().equals(id);
        } catch (IllegalArgumentException invalidUuid) {
            return false;
        }
    }

    private static void verifyDirectChild(File parent, File child) throws IOException {
        File canonicalParent = parent.getCanonicalFile();
        File canonicalChild = child.getCanonicalFile();
        File absoluteParent = parent.getAbsoluteFile();
        File absoluteChild = child.getAbsoluteFile();
        File actualParent = canonicalChild.getParentFile();
        if (!absoluteParent.equals(canonicalParent) || !absoluteChild.equals(canonicalChild) ||
                actualParent == null || !actualParent.equals(canonicalParent)) {
            throw new IOException("Staged share path escaped its invocation directory");
        }
    }

    private static void cleanupAfterFailure(File cacheDirectory, File invocationDirectory,
                                            List<File> ownedArtifacts, Throwable original) {
        IOException cleanupFailure = null;
        for (File artifact : ownedArtifacts) {
            try {
                verifyDirectChild(invocationDirectory, artifact);
                if (artifact.exists() && !artifact.delete()) {
                    throw new IOException("Unable to remove a partial staged share file");
                }
            } catch (IOException e) {
                cleanupFailure = appendFailure(cleanupFailure, e);
            }
        }
        try {
            verifyInvocationDirectory(cacheDirectory, invocationDirectory);
            if (invocationDirectory.exists() && !invocationDirectory.delete()) {
                throw new IOException("Unable to remove a failed staged share directory");
            }
        } catch (IOException e) {
            cleanupFailure = appendFailure(cleanupFailure, e);
        }
        if (cleanupFailure != null) {
            original.addSuppressed(cleanupFailure);
        }
    }

    private static IOException appendFailure(IOException current, IOException next) {
        if (current == null) {
            return next;
        }
        current.addSuppressed(next);
        return current;
    }
}
