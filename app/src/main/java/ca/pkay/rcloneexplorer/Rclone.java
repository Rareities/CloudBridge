package ca.pkay.rcloneexplorer;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.net.Uri;
import android.os.Build;
import android.os.CancellationSignal;
import android.os.Environment;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructStat;
import android.util.AtomicFile;
import android.webkit.MimeTypeMap;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.preference.PreferenceManager;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.net.InetAddress;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import ca.pkay.rcloneexplorer.Database.json.Exporter;
import ca.pkay.rcloneexplorer.Database.json.Importer;
import ca.pkay.rcloneexplorer.Database.json.SharedPreferencesBackup;
import ca.pkay.rcloneexplorer.Database.ResourceClaimLease;
import ca.pkay.rcloneexplorer.Database.BisyncListingEvidence;
import ca.pkay.rcloneexplorer.Database.BisyncListingValidator;
import ca.pkay.rcloneexplorer.Database.BisyncNativeState;
import ca.pkay.rcloneexplorer.Database.BisyncNativeStateEvidence;
import ca.pkay.rcloneexplorer.Database.BisyncPreviewCommandBuilder;
import ca.pkay.rcloneexplorer.Database.BisyncPreviewCommandRequest;
import ca.pkay.rcloneexplorer.Database.BisyncPreviewIdentity;
import ca.pkay.rcloneexplorer.Database.BisyncPreviewNativeRunResult;
import ca.pkay.rcloneexplorer.Database.BisyncPreviewOperation;
import ca.pkay.rcloneexplorer.Database.BisyncPreviewOperationState;
import ca.pkay.rcloneexplorer.Database.BisyncPreviewParseResult;
import ca.pkay.rcloneexplorer.Database.BisyncPreviewRepository;
import ca.pkay.rcloneexplorer.Database.BisyncPreviewSummaryParser;
import ca.pkay.rcloneexplorer.Database.BisyncPreviewUnavailableReason;
import ca.pkay.rcloneexplorer.Database.BisyncPreflightReason;
import ca.pkay.rcloneexplorer.Database.BisyncRootScanResult;
import ca.pkay.rcloneexplorer.Items.FileItem;
import ca.pkay.rcloneexplorer.Items.FilterEntry;
import ca.pkay.rcloneexplorer.Items.RemoteItem;
import ca.pkay.rcloneexplorer.Items.SyncDirectionObject;
import ca.pkay.rcloneexplorer.rclone.Provider;
import ca.pkay.rcloneexplorer.util.ConfigRevisionPolicy;
import ca.pkay.rcloneexplorer.util.ConfigRevisionStore;
import ca.pkay.rcloneexplorer.util.ConfigFileRestorer;
import ca.pkay.rcloneexplorer.util.ConfigResetRecoveryPolicy;
import ca.pkay.rcloneexplorer.util.ConfigEncryptionProbePolicy;
import ca.pkay.rcloneexplorer.util.BackupArchiveStager;
import ca.pkay.rcloneexplorer.util.ConfigMutationCommandPolicy;
import ca.pkay.rcloneexplorer.util.RemoteDeleteTargetPolicy;
import ca.pkay.rcloneexplorer.util.ConfigSecretStore;
import ca.pkay.rcloneexplorer.util.BoundedTextReader;
import ca.pkay.rcloneexplorer.util.EndpointConflictCoordinator;
import ca.pkay.rcloneexplorer.util.FLog;
import ca.pkay.rcloneexplorer.util.LogRedactor;
import ca.pkay.rcloneexplorer.util.ProviderMetadataPolicy;
import ca.pkay.rcloneexplorer.util.NativeExecutionHandle;
import ca.pkay.rcloneexplorer.util.NativeDiagnosticCommandPolicy;
import ca.pkay.rcloneexplorer.util.StagedUploadSourceCleanup;
import ca.pkay.rcloneexplorer.util.SyncLog;
import ca.pkay.rcloneexplorer.workmanager.SessionProbeFailureClassifier;
import es.dmoral.toasty.Toasty;
import io.github.x0b.safdav.SafAccessProvider;
import io.github.x0b.safdav.SafDAVServer;
import io.github.x0b.safdav.file.SafConstants;

public class Rclone {

    private static final String TAG = "Rclone";
    private static final Object PROVIDER_CACHE_LOCK = new Object();
    private static final long MAX_BACKUP_ENTRY_BYTES = BackupArchiveStager.MAX_ENTRY_BYTES;
    private static final long METADATA_COMMAND_TIMEOUT_MILLIS = 5L * 60L * 1000L;
    private static final long LISTING_TIMEOUT_MILLIS = 15L * 60L * 1000L;
    private static final int MAX_LISTING_JSON_CHARS = 16 * 1024 * 1024;
    private static final int MAX_CONFIG_JSON_CHARS = 4 * 1024 * 1024;
    private static final int MAX_ABOUT_JSON_CHARS = 256 * 1024;
    private static final long BISYNC_PREVIEW_TIMEOUT_MILLIS = 10L * 60L * 1000L;
    public static final int SYNC_DIRECTION_LOCAL_TO_REMOTE = 1;
    public static final int SYNC_DIRECTION_REMOTE_TO_LOCAL = 2;
    public static final int SERVE_PROTOCOL_HTTP = 1;
    public static final int SERVE_PROTOCOL_WEBDAV = 2;
    public static final int SERVE_PROTOCOL_FTP = 3;
    public static final int SERVE_PROTOCOL_DLNA = 4;

    public static final String RCLONE_CONFIG_NAME_KEY = "rclone_remote_name";
    private static volatile Boolean isCompatible;
    private static SafDAVServer safDAVServer;
    private Context context;
    private String rclone;
    private String rcloneConf;
    private Log2File log2File;
    private final ConfigSecretStore configSecretStore;
    private final EndpointConflictCoordinator endpointConflictCoordinator;
    private volatile IOException configFileRecoveryFailure;
    private final Map<Process, ResourceClaimLease> pendingExecutionClaims =
            Collections.synchronizedMap(new IdentityHashMap<Process, ResourceClaimLease>());
    private final Map<Process, Boolean> pendingConfigRevisionMutations =
            Collections.synchronizedMap(new IdentityHashMap<Process, Boolean>());
    private volatile ConfigSecretStore.BoundSecret configSecret;
    // RC-38: cache of the parsed `rclone config dump` JSON. Validated against the rclone.conf
    // file's mtime/length on every read so per-instance caches self-invalidate when another
    // Rclone instance (e.g. a config dialog) mutates the config. Volatile for cross-thread visibility.
    private volatile JSONObject cachedRemotesConfig;
    private volatile long cachedConfMtime;
    private volatile long cachedConfLength;
    private volatile String cachedConfDigest;

    public Rclone(Context context) {
        this.context = context;
        this.rclone = context.getApplicationInfo().nativeLibraryDir + "/librclone.so";
        this.rcloneConf = context.getFilesDir().getPath() + "/rclone.conf";
        log2File = new Log2File(context);
        configSecretStore = new ConfigSecretStore(context);
        endpointConflictCoordinator = new EndpointConflictCoordinator(context);
        try {
            ConfigFileRestorer.recover(new File(rcloneConf));
        } catch (IOException recoveryFailure) {
            configFileRecoveryFailure = recoveryFailure;
            FLog.e(TAG, "Unable to recover the rclone config after an interrupted file replacement", recoveryFailure);
        }
        try {
            configSecret = configFileRecoveryFailure == null ? configSecretStore.load() : null;
        } catch (Exception e) {
            // Keep the encrypted config and require explicit recovery if the Keystore key
            // was invalidated. Never clear ciphertext as a generic recovery action.
            configSecret = null;
            FLog.w(TAG, "Unable to unlock stored rclone config password; explicit recovery is required");
        }
    }

    private String[] createCommand(ArrayList<String> args) {
        String[] command = new String[args.size()];
        for (int i = 0; i < args.size(); i++) {
            command[i]= args.get(i);
        }
        return command;
    }
    private String[] createCommand(String ...args) {
        boolean loggingEnabled = PreferenceManager
                .getDefaultSharedPreferences(context)
                .getBoolean(context.getString(R.string.pref_key_logs), false);
        ArrayList<String> command = new ArrayList<>();

        command.add(rclone);
        command.add("--config");
        command.add(rcloneConf);

        if(loggingEnabled) {
            command.add("-vvv");
        }

        command.addAll(Arrays.asList(args));
        return createCommand(command);
    }

    private String[] createCommandWithOptions(String ...args) {
        ArrayList<String> arguments = new ArrayList<String>(Arrays.asList(args));
        return createCommandWithOptions(arguments);
    }

    private String[] createCommandWithOptions(ArrayList<String> args) {
        boolean loggingEnabled = PreferenceManager
                .getDefaultSharedPreferences(context)
                .getBoolean(context.getString(R.string.pref_key_logs), false);
        ArrayList<String> command = new ArrayList<>();

        String cachePath = context.getCacheDir().getAbsolutePath();

        command.add(rclone);
        command.add("--cache-chunk-path");
        command.add(cachePath);
        command.add("--cache-db-path");
        command.add(cachePath);

        /*

        This fixed some bug. I dont know which one, but it breaks transfer of big files where
        the checksum needs to be calculated.
        This was probably due to some timeout for connecting misconfigured remotes.

        command.add("--low-level-retries");
        command.add("2");

        command.add("--timeout");
        command.add("5s");
        command.add("--contimeout");
        command.add("5s");
        */

        command.add("--config");
        command.add(rcloneConf);

        if(loggingEnabled) {
            command.add("-vvv");
        }

        command.addAll(args);
        return createCommand(command);
    }

    private String getTransfers() {
        return getTransfers(null);
    }

    private String getTransfers(String override) {
        if (override != null && !override.isEmpty()) {
            return override;
        }
        return PreferenceManager
                .getDefaultSharedPreferences(context)
                .getString(context.getString(R.string.pref_key_transfers), "4");
    }

    public String[] getRcloneEnv(String... overwriteOptions) {
        ArrayList<String> environmentValues = new ArrayList<>();
        SharedPreferences pref = PreferenceManager.getDefaultSharedPreferences(context);

        boolean proxyEnabled = pref.getBoolean(context.getString(R.string.pref_key_use_proxy), false);
        if(proxyEnabled) {
            String noProxy = pref.getString(context.getString(R.string.pref_key_no_proxy_hosts), "localhost");
            String protocol = pref.getString(context.getString(R.string.pref_key_proxy_protocol), "http");
            String host = pref.getString(context.getString(R.string.pref_key_proxy_host), "localhost");
            String user = pref.getString(context.getString(R.string.pref_key_proxy_username), "");
            String pass = pref.getString(context.getString(R.string.pref_key_proxy_password), "");
            int port = pref.getInt(context.getString(R.string.pref_key_proxy_port), 8080);
            String auth = "";
            if(!(user + pass).isEmpty()) {
                auth = user+":"+pass+"@";
            }
            String url = protocol + "://" + auth + host + ":" + port;
            // per https://golang.org/pkg/net/http/#ProxyFromEnvironment
            environmentValues.add("http_proxy=" + url);
            environmentValues.add("https_proxy=" + url);
            environmentValues.add("no_proxy=" + noProxy);
        }

        // if TMPDIR is not set, golang uses /data/local/tmp which is only
        // only accessible for the shell user
        String tmpDir = context.getCacheDir().getAbsolutePath();
        environmentValues.add("TMPDIR=" + tmpDir);

        // ignore chtimes errors
        // ref: https://github.com/rclone/rclone/issues/2446
        environmentValues.add("RCLONE_LOCAL_NO_SET_MODTIME=true");

        // The pre-built linux rclone binaries do not know how to find the Android certificate store.
        // We set SSL_CERT_DIR to Android's native certificate store path.
        environmentValues.add("SSL_CERT_DIR=/system/etc/security/cacerts");

        environmentValues.add("RCLONE_DNS_SERVERS=" + getDnsServers());

        String password = currentConfigPassword();
        if (password != null && !password.isEmpty()) {
            environmentValues.add("RCLONE_CONFIG_PASS=" + password);
        }

        // Allow the caller to overwrite any option for special cases
        Iterator<String> envVarIter = environmentValues.iterator();
        while(envVarIter.hasNext()){
            String envVar = envVarIter.next();
            String optionName = envVar.substring(0, envVar.indexOf('='));
            for(String overwrite : overwriteOptions){
                if(overwrite.startsWith(optionName)) {
                    envVarIter.remove();
                    environmentValues.add(overwrite);
                }
            }
        }
        return environmentValues.toArray(new String[0]);
    }

    /** Reloads a saved password when another app component rotates its durable generation. */
    private String currentConfigPassword() {
        try {
            ConfigSecretStore.BoundSecret secret = configSecret;
            if (secret == null || !configSecretStore.isCurrent(secret)) {
                secret = configSecretStore.load();
                configSecret = secret;
            }
            return secret != null && configSecretStore.isCurrent(secret)
                    ? secret.password() : null;
        } catch (Exception e) {
            configSecret = null;
            FLog.w(TAG, "Unable to refresh saved config password; explicit recovery is required");
            return null;
        }
    }

    private String getDnsServers() {
        String fallback = "8.8.8.8:53,8.8.4.4:53";
        try {
            ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return fallback;
            Network network = cm.getActiveNetwork();
            if (network == null) return fallback;
            LinkProperties lp = cm.getLinkProperties(network);
            if (lp == null) return fallback;
            StringBuilder sb = new StringBuilder();
            for (InetAddress dns : lp.getDnsServers()) {
                if (sb.length() > 0) sb.append(",");
                sb.append(dns.getHostAddress()).append(":53");
            }
            return sb.length() > 0 ? sb.toString() : fallback;
        } catch (Exception e) {
            FLog.e(TAG, "Failed to detect DNS servers, using fallback", e);
            return fallback;
        }
    }

    private static final class DiagnosticCapture implements NativeExecutionHandle.LineSink {
        private final StringBuilder output = new StringBuilder(256);
        private boolean truncated;

        @Override
        public void onLine(String line) {
            int remaining = LogRedactor.MAX_DIAGNOSTIC_CHARS - output.length();
            if (remaining <= 0) {
                truncated = true;
            } else if (line.length() + 1 > remaining) {
                output.append(line, 0, Math.max(0, remaining - 1));
                truncated = true;
            } else {
                output.append(line).append('\n');
            }
        }

        private String getRedactedOutput() {
            String captured = output.toString();
            if (truncated) {
                captured += "\n***diagnostic-output-truncated***";
            }
            return LogRedactor.redact(captured);
        }
    }

    private void logNativeDiagnostics(DiagnosticCapture diagnostics) {
        if (diagnostics == null) {
            return;
        }
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(context);
        if (!preferences.getBoolean(context.getString(R.string.pref_key_logs), false)) {
            return;
        }
        String output = diagnostics.getRedactedOutput();
        if (!output.trim().isEmpty()) {
            log2File.log(output);
            SyncLog.error(context, "Rclone operation", output);
        }
    }

    @Nullable
    public List<FileItem> getDirectoryContent(RemoteItem remote, String path, boolean startAtRoot) {
        String remoteAndPath = remote.getName() + ":";
        if (startAtRoot) {
            remoteAndPath += "/";
        }
        if (remote.isRemoteType(RemoteItem.LOCAL) && (!remote.isCrypt() && !remote.isAlias() && !remote.isCache())) {
            remoteAndPath += getLocalRemotePathPrefix(remote, context) + "/";
        }
        if (path.compareTo("//" + remote.getName()) != 0) {
            remoteAndPath += path;
        }
        // if SAFW, start emulation server
        if(remote.isRemoteType(RemoteItem.SAFW) && path.equals("//" + remote.getName()) && safDAVServer == null){
            try {
                safDAVServer = SafAccessProvider.getServer(context);
            } catch (IOException e) {
                // TODO: Provide port checking / alt port functionality
                FLog.e(TAG, "Cannot connect to SAF DAV emulation server");
                return null;
            }
        }

        String[] command;
        if (remote.isRemoteType(RemoteItem.LOCAL) || remote.isPathAlias()) {
            // ignore .android_secure errors
            // ref: https://github.com/rclone/rclone/issues/3179
            command = createCommandWithOptions("--ignore-errors", "lsjson", remoteAndPath);
        } else {
            command = createCommandWithOptions("lsjson", remoteAndPath);
        }
        JSONArray results;
        try {
            CapturedText result = runBoundedTextCommand(command, getRcloneEnv(), "lsjson",
                    MAX_LISTING_JSON_CHARS, LISTING_TIMEOUT_MILLIS);
            // For local/alias remotes, exit(6) is not a fatal error.
            boolean allowedExitSix = result.outcome.getState() == NativeExecutionHandle.TerminalState.FAILED
                    && Integer.valueOf(6).equals(result.outcome.getExitCode())
                    && remote.isRemoteType(RemoteItem.LOCAL, RemoteItem.ALIAS);
            if (result.exceededLimit || result.outcome.isOutputTruncated()
                    || (!result.outcome.isSuccess() && !allowedExitSix)) {
                FLog.e(TAG, "getDirectoryContent: listing ended with state %s",
                        result.outcome.getState());
                return null;
            }
            results = new JSONArray(result.text);
        } catch (IOException | JSONException e) {
            FLog.e(TAG, "getDirectoryContent: Could not get folder content", e);
            return null;
        }

        List<FileItem> fileItemList = new ArrayList<>();
        for (int i = 0; i < results.length(); i++) {
            try {
                JSONObject jsonObject = results.getJSONObject(i);
                String filePath = (path.compareTo("//" + remote.getName()) == 0) ? "" : path + "/";
                filePath += jsonObject.getString("Path");
                String fileName = jsonObject.getString("Name");
                long fileSize = jsonObject.getLong("Size");
                String fileModTime = jsonObject.getString("ModTime");
                boolean fileIsDir = jsonObject.getBoolean("IsDir");
                String mimeType = jsonObject.getString("MimeType");

                if (remote.isCrypt()) {
                    String extension = fileName.substring(fileName.lastIndexOf(".") + 1);
                    String type = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension);
                    if (type != null) {
                        mimeType = type;
                    }
                }

                FileItem fileItem = new FileItem(remote, filePath, fileName, fileSize, fileModTime, mimeType, fileIsDir, startAtRoot);
                fileItemList.add(fileItem);
            } catch (JSONException e) {
                FLog.e(TAG, "getDirectoryContent: Could not decode JSON", e);
                return null;
            }
        }
        return fileItemList;
    }

    /**
     * Performs a strict, read-only Bisync root stat followed by a complete filtered traversal.
     * Unlike browser listings this path never allows exit code 6, --ignore-errors, or partial
     * JSON. Call from a cancellable background owner, not the main thread.
     */
    @NonNull
    public BisyncRootScanResult scanBisyncRoot(@NonNull RemoteItem remote, @NonNull String path,
                                                @NonNull List<FilterEntry> filters) {
        return scanBisyncRoot(remote, path, filters, null);
    }

    /** Variant that transfers cancellation to each active native listing process. */
    @NonNull
    public BisyncRootScanResult scanBisyncRoot(@NonNull RemoteItem remote, @NonNull String path,
                                                @NonNull List<FilterEntry> filters,
                                                @Nullable CancellationSignal cancellationSignal) {
        final String remoteSection;
        try {
            if (path.indexOf('\u0000') >= 0 || !isSafeBisyncRelativePath(remote, path)) {
                return incompleteBisyncScan(false);
            }
            remoteSection = buildReadOnlyBisyncSection(remote, path);
            if (remoteSection == null) return incompleteBisyncScan(false);
        } catch (RuntimeException e) {
            return incompleteBisyncScan(false);
        }
        return scanBisyncSection(remoteSection, filters, cancellationSignal);
    }

    /** Read-only scan for an absolute local directory; never creates the target or its parent. */
    @NonNull
    public BisyncRootScanResult scanBisyncPath(@NonNull String path, @NonNull List<FilterEntry> filters,
                                                @Nullable CancellationSignal cancellationSignal) {
        if (path.indexOf('\u0000') >= 0 || !new File(path).isAbsolute() || !isSafeAbsoluteBisyncPath(path)) {
            return incompleteBisyncScan(false);
        }
        try {
            return scanBisyncSection(new File(path).getCanonicalPath(), filters, cancellationSignal);
        } catch (IOException | SecurityException e) {
            return incompleteBisyncScan(false);
        }
    }

    private BisyncRootScanResult scanBisyncSection(@NonNull String remoteSection,
                                                    @NonNull List<FilterEntry> filters,
                                                    @Nullable CancellationSignal cancellationSignal) {
        if (cancellationSignal != null && cancellationSignal.isCanceled()) {
            return cancelledBisyncScan(false);
        }

        CapturedText stat;
        try {
            stat = runBoundedTextCommandCancellable(
                    createCommandWithOptions("lsjson", "--stat", remoteSection),
                    getRcloneEnv(), "bisync-root-stat", MAX_LISTING_JSON_CHARS,
                    BISYNC_SCAN_TIMEOUT_MILLIS, cancellationSignal);
        } catch (IOException e) {
            return incompleteBisyncScan(false);
        }
        if (cancellationSignal != null && cancellationSignal.isCanceled()) {
            return cancelledBisyncScan(false);
        }
        boolean statSucceeded = stat.outcome.isSuccess() && stat.outcome.isConfirmed();
        BisyncListingEvidence root = BisyncListingValidator.parseRootStat(
                stat.text, statSucceeded, stat.exceededLimit || stat.outcome.isOutputTruncated());
        if (!root.getComplete() || !root.getRootIsDirectory()) {
            return new BisyncRootScanResult(root, statSucceeded);
        }

        ArrayList<String> arguments = new ArrayList<>();
        arguments.add("lsjson");
        arguments.add("--recursive");
        arguments.add(remoteSection);
        if (filters.size() > MAX_BISYNC_FILTER_RULES) {
            return incompleteBisyncScan(true);
        }
        for (FilterEntry filter : filters) {
            if (filter == null || filter.filter == null || filter.filter.indexOf('\u0000') >= 0
                    || (filter.filterType != FilterEntry.FILTER_INCLUDE
                    && filter.filterType != FilterEntry.FILTER_EXCLUDE)) {
                return incompleteBisyncScan(true);
            }
            arguments.add("--filter");
            arguments.add((filter.filterType == FilterEntry.FILTER_INCLUDE ? "+ " : "- ") + filter.filter);
        }
        if (cancellationSignal != null && cancellationSignal.isCanceled()) {
            return cancelledBisyncScan(true);
        }

        try {
            CapturedText listing = runBoundedTextCommandCancellable(
                    createCommandWithOptions(arguments), getRcloneEnv(), "bisync-root-list",
                    MAX_LISTING_JSON_CHARS, BISYNC_SCAN_TIMEOUT_MILLIS, cancellationSignal);
            if (cancellationSignal != null && cancellationSignal.isCanceled()) {
                return cancelledBisyncScan(true);
            }
            boolean listingSucceeded = listing.outcome.isSuccess() && listing.outcome.isConfirmed();
            BisyncListingEvidence evidence = BisyncListingValidator.parseRecursiveList(
                    listing.text, listingSucceeded,
                    listing.exceededLimit || listing.outcome.isOutputTruncated());
            return new BisyncRootScanResult(evidence, true);
        } catch (IOException e) {
            return incompleteBisyncScan(true);
        }
    }

    private static final long BISYNC_SCAN_TIMEOUT_MILLIS = 10L * 60L * 1000L;
    private static final int MAX_BISYNC_FILTER_RULES = 4096;
    private static final long RCLONE_MODTIME_NOT_SUPPORTED_NANOS = 3_153_600_000_000_000_000L;

    @Nullable
    private String buildReadOnlyBisyncSection(@NonNull RemoteItem remote, @NonNull String path) {
        if (!remote.isRemoteType(RemoteItem.LOCAL)) {
            return buildRemoteSection(remote, path, context);
        }
        File base;
        if (Build.VERSION.SDK_INT > Build.VERSION_CODES.Q) {
            base = context.getExternalFilesDir(null);
            if (base == null) {
                base = new File(context.getFilesDir(), "fallback-local");
            }
        } else {
            base = Environment.getExternalStorageDirectory();
        }
        // Unlike the browser's compatibility path, preflight must never create a missing root.
        if (!base.isDirectory()) return null;
        String root = base.getAbsolutePath();
        if (("//" + remote.getName()).equals(path)) return remote.getName() + ":" + root + "/";
        return remote.getName() + ":" + root + "/" + path;
    }

    private static boolean isSafeBisyncRelativePath(@NonNull RemoteItem remote, @NonNull String path) {
        if (("//" + remote.getName()).equals(path)) return true;
        String portable = path.replace('\\', '/');
        for (String segment : portable.split("/")) {
            if (".".equals(segment) || "..".equals(segment)) return false;
        }
        return true;
    }

    private static boolean isSafeAbsoluteBisyncPath(@NonNull String path) {
        String portable = path.replace('\\', '/');
        for (String segment : portable.split("/")) {
            if (".".equals(segment) || "..".equals(segment)) return false;
        }
        return true;
    }

    private static BisyncRootScanResult incompleteBisyncScan(boolean statProbeSucceeded) {
        return new BisyncRootScanResult(new BisyncListingEvidence(
                false, false, 0, BisyncPreflightReason.LISTING_INCOMPLETE), statProbeSucceeded);
    }

    private static BisyncRootScanResult cancelledBisyncScan(boolean statProbeSucceeded) {
        return new BisyncRootScanResult(new BisyncListingEvidence(
                false, false, 0, BisyncPreflightReason.PROBE_CANCELLED), statProbeSucceeded);
    }

    /**
     * Inspects the profile's established native Bisync work directory without creating it,
     * migrating listings, or recovering an interrupted run.
     */
    @NonNull
    public BisyncNativeStateEvidence inspectBisyncNativeState(
            @NonNull String profileId,
            @NonNull String profileFingerprint,
            @Nullable String localPath,
            @NonNull RemoteItem remote,
            @Nullable String remotePath,
            @NonNull String compareOptions,
            @Nullable CancellationSignal cancellationSignal) {
        if (!profileFingerprint.matches("[0-9a-fA-F]{64}") || localPath == null
                || localPath.indexOf('\u0000') >= 0 || !new File(localPath).isAbsolute()
                || !isSafeAbsoluteBisyncPath(localPath)
                || !("size".equals(compareOptions) || "size,modtime".equals(compareOptions))) {
            return unknownBisyncNativeState("INSPECTION_INPUT_INVALID");
        }

        final File workDir;
        try {
            UUID profileUuid = UUID.fromString(profileId);
            if (!profileUuid.toString().equalsIgnoreCase(profileId)) {
                return unknownBisyncNativeState("PROFILE_ID_INVALID");
            }
            File profileRoot = new File(new File(context.getFilesDir(), "bisync"), "profiles").getCanonicalFile();
            File expectedWorkDir = new File(profileRoot, profileUuid.toString());
            workDir = expectedWorkDir.getCanonicalFile();
            if (!workDir.equals(expectedWorkDir.getAbsoluteFile()) || !workDir.isDirectory()) {
                // A missing work directory is not proof that no state exists in a legacy/default location.
                return unknownBisyncNativeState("PROFILE_WORKDIR_NOT_ESTABLISHED");
            }
        } catch (IOException | IllegalArgumentException | SecurityException e) {
            return unknownBisyncNativeState("PROFILE_WORKDIR_UNAVAILABLE");
        }

        if (cancellationSignal != null && cancellationSignal.isCanceled()) {
            return unknownBisyncNativeState("PROBE_CANCELLED");
        }
        final String remoteSection;
        try {
            if (remotePath == null || remotePath.indexOf('\u0000') >= 0
                    || !isSafeBisyncRelativePath(remote, remotePath)) {
                return unknownBisyncNativeState("REMOTE_PATH_UNRESOLVED");
            }
            remoteSection = buildReadOnlyBisyncSection(remote, remotePath);
            if (remoteSection == null) return unknownBisyncNativeState("REMOTE_PATH_UNRESOLVED");
        } catch (RuntimeException e) {
            return unknownBisyncNativeState("REMOTE_PATH_UNRESOLVED");
        }

        ArrayList<String> arguments = new ArrayList<>(Arrays.asList(
                "bisync", localPath, remoteSection, "--inspect-state", "--workdir",
                workDir.getAbsolutePath(), "--compare", compareOptions));
        try {
            CapturedText result = runBoundedTextCommandCancellable(
                    createCommandWithOptions(arguments), getRcloneEnv(), "bisync-state-inspection",
                    64 * 1024, METADATA_COMMAND_TIMEOUT_MILLIS, cancellationSignal);
            if (cancellationSignal != null && cancellationSignal.isCanceled()) {
                return unknownBisyncNativeState("PROBE_CANCELLED");
            }
            if (!result.outcome.isSuccess() || !result.outcome.isConfirmed() || result.exceededLimit
                    || result.outcome.isOutputTruncated()) {
                return unknownBisyncNativeState("INSPECTION_PROCESS_FAILED");
            }
            JSONObject response = new JSONObject(result.text);
            if (response.optInt("version", -1) != 1) {
                return unknownBisyncNativeState("INSPECTION_VERSION_UNSUPPORTED");
            }
            return BisyncNativeStateEvidence.fromWire(
                    response.optString("status", null), response.optString("reason", null),
                    response.optBoolean("recoveryListingsValid", false));
        } catch (IOException | JSONException | SecurityException e) {
            return unknownBisyncNativeState("INSPECTION_OUTPUT_INVALID");
        }
    }

    /**
     * Runs one dry-run preview through the same endpoint-claim and owned-handle boundary as other
     * native work. The result is path-free; raw stdout is parsed in memory and never logged.
     * A scratch directory is created exclusively for this preview and retained if process exit
     * cannot be confirmed, so a later recovery path can inspect the uncertainty safely.
     */
    @NonNull
    public BisyncPreviewNativeRunResult runBisyncPreview(
            @NonNull String previewId,
            @NonNull String ownerToken,
            long ownerGeneration,
            @NonNull BisyncPreviewIdentity identity,
            @NonNull String localPath,
            @NonNull RemoteItem remote,
            @NonNull String remotePath,
            @NonNull List<FilterEntry> filters,
            boolean deleteExcluded,
            boolean checksumRequested,
            @Nullable CancellationSignal cancellationSignal) {
        File workDirectory = null;
        boolean processStarted = false;
        boolean processStoppedConfirmed = false;
        try {
            if (!isCurrentPreviewOwner(previewId, ownerToken, ownerGeneration, identity)) {
                return unavailablePreview(BisyncPreviewUnavailableReason.REQUEST_REJECTED, false, true, true);
            }
            String configuredEngine = "rclone:" + BuildConfig.RCLONE_ENGINE_VERSION + "@" +
                    BuildConfig.RCLONE_ENGINE_REF;
            if (!configuredEngine.equals(identity.getEngineRef())) {
                return unavailablePreview(BisyncPreviewUnavailableReason.ENGINE_UNSUPPORTED, false, true, true);
            }
            if (cancellationSignal != null && cancellationSignal.isCanceled()) {
                return unavailablePreview(BisyncPreviewUnavailableReason.PROCESS_FAILED, false, true, true);
            }
            if (localPath.indexOf('\u0000') >= 0 || !new File(localPath).isAbsolute()
                    || !isSafeAbsoluteBisyncPath(localPath)
                    || remotePath.indexOf('\u0000') >= 0 || !isSafeBisyncRelativePath(remote, remotePath)) {
                return unavailablePreview(BisyncPreviewUnavailableReason.REQUEST_REJECTED, false, true, true);
            }
            final String canonicalLocalPath;
            final String remoteSection;
            try {
                canonicalLocalPath = new File(localPath).getCanonicalPath();
                remoteSection = buildReadOnlyBisyncSection(remote, remotePath);
            } catch (IOException | RuntimeException failure) {
                return unavailablePreview(BisyncPreviewUnavailableReason.REQUEST_REJECTED, false, true, true);
            }
            if (remoteSection == null) {
                return unavailablePreview(BisyncPreviewUnavailableReason.REQUEST_REJECTED, false, true, true);
            }

            File previewRoot = ensureBisyncPreviewRoot();
            if (pathsOverlap(new File(canonicalLocalPath), previewRoot)) {
                return unavailablePreview(BisyncPreviewUnavailableReason.REQUEST_REJECTED, false, true, true);
            }
            if (remote.isRemoteType(RemoteItem.LOCAL)) {
                int separator = remoteSection.indexOf(':');
                if (separator < 0 || pathsOverlap(new File(remoteSection.substring(separator + 1)), previewRoot)) {
                    return unavailablePreview(BisyncPreviewUnavailableReason.REQUEST_REJECTED, false, true, true);
                }
            }
            File requestedWorkDirectory = new File(previewRoot, previewId);
            File acceptedStateDirectory = null;
            if (identity.getNativeState() == BisyncNativeState.COMPATIBLE) {
                acceptedStateDirectory = establishedProfileWorkDirectory(identity.getProfileId());
                if (acceptedStateDirectory == null || !acceptedStateDirectory.isDirectory()) {
                    return unavailablePreview(BisyncPreviewUnavailableReason.REQUEST_REJECTED, false, true, true);
                }
            }

            BisyncPreviewCommandRequest request = new BisyncPreviewCommandRequest(
                    identity,
                    previewId,
                    canonicalLocalPath,
                    remoteSection,
                    requestedWorkDirectory.getAbsolutePath(),
                    filters,
                    deleteExcluded,
                    acceptedStateDirectory == null ? null : acceptedStateDirectory.getAbsolutePath(),
                    checksumRequested);
            final List<String> arguments;
            try {
                arguments = BisyncPreviewCommandBuilder.build(request);
            } catch (IllegalArgumentException failure) {
                String message = failure.getMessage();
                boolean unsupportedEngine = message != null &&
                        (message.contains("native engine") || message.contains("engine pin") ||
                                message.contains("cannot clone"));
                BisyncPreviewUnavailableReason reason = unsupportedEngine
                        ? BisyncPreviewUnavailableReason.ENGINE_UNSUPPORTED
                        : BisyncPreviewUnavailableReason.REQUEST_REJECTED;
                return unavailablePreview(reason, false, true, true);
            }

            workDirectory = createUniqueBisyncPreviewWorkDirectory(previewRoot, previewId);
            if (cancellationSignal != null && cancellationSignal.isCanceled()) {
                boolean cleaned = deleteOwnedPreviewTree(workDirectory, previewRoot);
                return unavailablePreview(cleaned
                                ? BisyncPreviewUnavailableReason.PROCESS_FAILED
                                : BisyncPreviewUnavailableReason.SCRATCH_CLEANUP_FAILED,
                        false, true, cleaned);
            }
            if (!isCurrentPreviewOwner(previewId, ownerToken, ownerGeneration, identity)) {
                boolean cleaned = deleteOwnedPreviewTree(workDirectory, previewRoot);
                return unavailablePreview(cleaned
                                ? BisyncPreviewUnavailableReason.REQUEST_REJECTED
                                : BisyncPreviewUnavailableReason.SCRATCH_CLEANUP_FAILED,
                        false, true, cleaned);
            }
            ArrayList<String> nativeArguments = new ArrayList<>(arguments);
            // The endpoint sections are generated by the same canonical mapper used by preflight.
            CapturedText captured = runBoundedTextCommandCancellable(
                    createCommandWithOptions(nativeArguments), getRcloneEnv(), "bisync-preview",
                    BisyncPreviewSummaryParser.MAX_OUTPUT_CHARS,
                    BISYNC_PREVIEW_TIMEOUT_MILLIS, cancellationSignal);
            processStarted = true;
            boolean stopped = captured.outcome.isConfirmed();
            processStoppedConfirmed = stopped;
            boolean succeeded = stopped && captured.outcome.isSuccess();
            BisyncPreviewParseResult parsed = BisyncPreviewSummaryParser.parse(
                    captured.text, succeeded,
                    captured.exceededLimit || captured.outcome.isOutputTruncated());
            boolean cleaned = false;
            if (stopped) cleaned = deleteOwnedPreviewTree(workDirectory, previewRoot);
            if (stopped && !cleaned) {
                parsed = new BisyncPreviewParseResult.Unavailable(
                        BisyncPreviewUnavailableReason.SCRATCH_CLEANUP_FAILED);
            }
            return new BisyncPreviewNativeRunResult(parsed, true, stopped, cleaned);
        } catch (IOException | RuntimeException failure) {
            boolean cleaned = workDirectory == null || deleteOwnedPreviewTree(workDirectory,
                    new File(context.getCacheDir(), "bisync-preview"));
            BisyncPreviewUnavailableReason reason = failure.getMessage() != null &&
                    (failure.getMessage().contains("preview") || failure.getMessage().contains("engine"))
                    ? BisyncPreviewUnavailableReason.REQUEST_REJECTED
                    : BisyncPreviewUnavailableReason.PROCESS_FAILED;
            if (!cleaned) reason = BisyncPreviewUnavailableReason.SCRATCH_CLEANUP_FAILED;
            return unavailablePreview(reason, processStarted,
                    processStarted ? processStoppedConfirmed : true, cleaned);
        }
    }

    @NonNull
    private static BisyncPreviewNativeRunResult unavailablePreview(
            BisyncPreviewUnavailableReason reason,
            boolean processStarted,
            boolean processStoppedConfirmed,
            boolean scratchCleaned) {
        return new BisyncPreviewNativeRunResult(
                new BisyncPreviewParseResult.Unavailable(reason),
                processStarted, processStoppedConfirmed, scratchCleaned);
    }

    private boolean isCurrentPreviewOwner(String previewId, String ownerToken, long ownerGeneration,
                                         BisyncPreviewIdentity identity) {
        try {
            BisyncPreviewOperation owner = new BisyncPreviewRepository(context).get(previewId);
            return owner != null && owner.getState() == BisyncPreviewOperationState.RUNNING &&
                    owner.getOwnerToken().equals(ownerToken) &&
                    owner.getOwnerGeneration() == ownerGeneration &&
                    owner.getIdentity().equals(identity);
        } catch (RuntimeException unavailable) {
            return false;
        }
    }

    private File ensureBisyncPreviewRoot() throws IOException {
        File cache = context.getCacheDir().getCanonicalFile();
        File root = new File(cache, "bisync-preview");
        if (!root.exists() && !root.mkdir()) throw new IOException("Preview scratch unavailable");
        File canonicalRoot = root.getCanonicalFile();
        if (!canonicalRoot.isDirectory() || !canonicalRoot.equals(root.getAbsoluteFile())) {
            throw new IOException("Preview scratch root is not private and contained");
        }
        return canonicalRoot;
    }

    private static boolean pathsOverlap(File left, File right) throws IOException {
        String leftPath = left.getCanonicalPath();
        String rightPath = right.getCanonicalPath();
        return isSameOrDescendantPath(leftPath, rightPath) || isSameOrDescendantPath(rightPath, leftPath);
    }

    private static boolean isSameOrDescendantPath(String possibleParent, String child) {
        if (possibleParent.equals(child)) return true;
        String parentWithSeparator = possibleParent.endsWith(File.separator)
                ? possibleParent : possibleParent + File.separator;
        return child.startsWith(parentWithSeparator);
    }

    private File createUniqueBisyncPreviewWorkDirectory(File previewRoot, String previewId) throws IOException {
        UUID uuid;
        try {
            uuid = UUID.fromString(previewId);
        } catch (IllegalArgumentException invalid) {
            throw new IOException("Preview ID is invalid");
        }
        if (!uuid.toString().equals(previewId)) throw new IOException("Preview ID is not canonical");
        File destination = new File(previewRoot, previewId);
        if (!destination.mkdir()) throw new IOException("Preview work directory already exists or cannot be created");
        File canonical = destination.getCanonicalFile();
        if (!previewRoot.equals(canonical.getParentFile())) {
            destination.delete();
            throw new IOException("Preview work directory escaped its private root");
        }
        return canonical;
    }

    @Nullable
    private File establishedProfileWorkDirectory(String profileId) {
        try {
            UUID uuid = UUID.fromString(profileId);
            if (!uuid.toString().equals(profileId)) return null;
            File profileRoot = new File(new File(context.getFilesDir(), "bisync"), "profiles").getCanonicalFile();
            File expected = new File(profileRoot, profileId);
            File canonical = expected.getCanonicalFile();
            return canonical.equals(expected.getAbsoluteFile()) && canonical.isDirectory() ? canonical : null;
        } catch (IOException | IllegalArgumentException | SecurityException failure) {
            return null;
        }
    }

    /** Deletes only the unique preview directory, never following symlinks or leaving its root. */
    private boolean deleteOwnedPreviewTree(File target, File previewRoot) {
        try {
            File canonicalRoot = previewRoot.getCanonicalFile();
            File canonicalTarget = target.getCanonicalFile();
            if (!canonicalRoot.equals(canonicalTarget.getParentFile())) return false;
            return deletePreviewNode(target, canonicalRoot);
        } catch (IOException | SecurityException failure) {
            return false;
        }
    }

    private boolean deletePreviewNode(File node, File canonicalRoot) {
        final StructStat stat;
        try {
            stat = Os.lstat(node.getAbsolutePath());
        } catch (ErrnoException missing) {
            return missing.errno == OsConstants.ENOENT;
        }
        if (OsConstants.S_ISLNK(stat.st_mode)) return node.delete();
        try {
            File canonical = node.getCanonicalFile();
            String rootPath = canonicalRoot.getAbsolutePath() + File.separator;
            if (!canonicalRoot.equals(canonical) && !canonical.getAbsolutePath().startsWith(rootPath)) return false;
        } catch (IOException | SecurityException failure) {
            return false;
        }
        if (OsConstants.S_ISDIR(stat.st_mode)) {
            File[] children = node.listFiles();
            if (children == null) return false;
            for (File child : children) {
                if (!deletePreviewNode(child, canonicalRoot)) return false;
            }
        }
        return node.delete();
    }

    @NonNull
    private static BisyncNativeStateEvidence unknownBisyncNativeState(@NonNull String reason) {
        return new BisyncNativeStateEvidence(BisyncNativeState.UNKNOWN, reason, false);
    }

    /** Returns a hash-only identity for providers with a known stable, non-token account locator. */
    @Nullable
    public String getBisyncRemoteAccountFingerprint(@NonNull RemoteItem remote) {
        if (remote.isRemoteType(RemoteItem.LOCAL) || remote.isCrypt() || remote.isAlias()
                || remote.isCache() || remote.isPathAlias()) return null;
        JSONObject remotes = getCachedRemotesConfig();
        JSONObject config = remotes == null ? null : remotes.optJSONObject(remote.getName());
        if (config == null) return null;
        String type = config.optString("type", "").trim().toLowerCase(Locale.ROOT);
        final String identityKey;
        switch (type) {
            case "protondrive": identityKey = "username"; break;
            case "internxt": identityKey = "email"; break;
            case "drime": identityKey = "workspace_id"; break;
            default: return null;
        }
        String stableIdentity = config.optString(identityKey, "").trim();
        if (stableIdentity.isEmpty() || stableIdentity.equalsIgnoreCase("null")) return null;
        String stableRoot = "";
        if (type.equals("drime")) {
            stableRoot = config.optString("root_folder_id", "").trim();
            if (stableRoot.isEmpty()) return null;
        }
        return sha256Identity("bisync-account-v1", type, stableIdentity, stableRoot);
    }

    /** Reads rclone's native FS precision. Unknown or malformed capability output fails closed. */
    @Nullable
    public Boolean getBisyncModTimeCapability(@NonNull RemoteItem remote, @NonNull String path,
                                               @Nullable CancellationSignal cancellationSignal) {
        final String remoteSection;
        try {
            if (path.indexOf('\u0000') >= 0 || !isSafeBisyncRelativePath(remote, path)) return null;
            remoteSection = buildReadOnlyBisyncSection(remote, path);
            if (remoteSection == null) return null;
        } catch (RuntimeException e) {
            return null;
        }
        try {
            CapturedText result = runBoundedTextCommandCancellable(
                    createCommandWithOptions("backend", "features", remoteSection), getRcloneEnv(),
                    "bisync-capabilities", 256 * 1024, METADATA_COMMAND_TIMEOUT_MILLIS,
                    cancellationSignal);
            if (!result.outcome.isSuccess() || !result.outcome.isConfirmed()
                    || result.exceededLimit || result.outcome.isOutputTruncated()) return null;
            JSONObject info = new JSONObject(result.text);
            Object precisionValue = info.opt("Precision");
            if (!(precisionValue instanceof Number)) return null;
            long precision = ((Number) precisionValue).longValue();
            return precision >= 0 && precision < RCLONE_MODTIME_NOT_SUPPORTED_NANOS;
        } catch (IOException | JSONException e) {
            return null;
        }
    }

    /** Transient-only config snapshot token; callers compare before/after a preflight and discard it. */
    @Nullable
    public String getBisyncConfigSnapshotFingerprint() {
        try {
            ensureConfigFileRecovered();
        } catch (IOException recoveryFailure) {
            FLog.e(TAG, "Unable to fingerprint an unrecovered rclone config", recoveryFailure);
            return null;
        }
        return EndpointConflictCoordinator.fingerprintFile(new File(rcloneConf));
    }

    private static String sha256Identity(String... values) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (String value : values) {
                byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
                digest.update(Integer.toString(bytes.length).getBytes(StandardCharsets.US_ASCII));
                digest.update((byte) ':');
                digest.update(bytes);
                digest.update((byte) '|');
            }
            byte[] bytes = digest.digest();
            StringBuilder encoded = new StringBuilder(bytes.length * 2);
            for (byte value : bytes) encoded.append(String.format(Locale.ROOT, "%02x", value & 0xff));
            return encoded.toString();
        } catch (Exception e) {
            return null;
        }
    }

    public List<RemoteItem> getRemotes() {
        // RC-38: avoid spawning an rclone process (and parsing its JSON) on every UI-thread call.
        // The expensive config-dump result is cached and only re-read after the config is mutated
        // via config()/deleteRemote() or an explicit invalidateRemotesCache(). Pin/favorite state
        // is re-applied from SharedPreferences on every call so it stays fresh.
        String revisionBeforeRead = ConfigRevisionStore.current(context);
        JSONObject remotesJSON = getCachedRemotesConfig();
        String revisionAfterRead = ConfigRevisionStore.current(context);
        String configRevision = revisionBeforeRead != null
                && revisionBeforeRead.equals(revisionAfterRead) ? revisionAfterRead : null;
        if (remotesJSON == null) {
            return new ArrayList<>();
        }

        SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(context);
        Set<String> pinnedRemotes = sharedPreferences.getStringSet(context.getString(R.string.shared_preferences_pinned_remotes), new HashSet<>());
        Set<String> favoriteRemotes = sharedPreferences.getStringSet(context.getString(R.string.shared_preferences_drawer_pinned_remotes), new HashSet<>());

        List<RemoteItem> remoteItemList = new ArrayList<>();
        Iterator<String> iterator = remotesJSON.keys();
        while (iterator.hasNext()) {
            String key = iterator.next();
            try {
                JSONObject remoteJSON = new JSONObject(remotesJSON.get(key).toString());
                String type = remoteJSON.optString("type");
                if (type.trim().isEmpty()) {
                    Toasty.error(context, context.getResources().getString(R.string.error_retrieving_remote, key), Toast.LENGTH_SHORT, true).show();
                    continue;
                }
                if(type.equals("webdav")){
                    String url = remoteJSON.optString("url");
                    if(url.startsWith(SafConstants.SAF_REMOTE_URL)){
                        type = SafConstants.SAF_REMOTE_NAME;
                    }
                }

                RemoteItem newRemote = new RemoteItem(key, type);
                if (type.equals("crypt") || type.equals("alias") || type.equals("cache")) {
                    newRemote = getRemoteType(remotesJSON, newRemote, key, 8);
                    if (newRemote == null) {
                        Toasty.error(context, context.getResources().getString(R.string.error_retrieving_remote, key), Toast.LENGTH_SHORT, true).show();
                        continue;
                    }
                }

                newRemote.setConfigRevision(configRevision);

                if (pinnedRemotes.contains(newRemote.getName())) {
                    newRemote.pin(true);
                }

                if (favoriteRemotes.contains(newRemote.getName())) {
                    newRemote.setDrawerPinned(true);
                }

                remoteItemList.add(newRemote);
            } catch (JSONException e) {
                FLog.e(TAG, "getRemotes: error decoding remotes", e);
                return new ArrayList<>();
            }
        }

        return remoteItemList;
    }

    /** Returns the cached rclone config dump, fetching and caching it on first use. */
    private JSONObject getCachedRemotesConfig() {
        try {
            ensureConfigFileRecovered();
        } catch (IOException recoveryFailure) {
            FLog.e(TAG, "Rclone config is unavailable until interrupted replacement recovery succeeds", recoveryFailure);
            return null;
        }
        File confFile = new File(rcloneConf);
        long mtime = confFile.lastModified();
        long length = confFile.length();
        String digest = EndpointConflictCoordinator.fingerprintFile(confFile);
        if (digest == null) return null;
        synchronized (this) {
            if (cachedRemotesConfig != null && cachedConfMtime == mtime
                    && cachedConfLength == length && digest.equals(cachedConfDigest)) {
                return cachedRemotesConfig;
            }
        }
        String[] command = createCommand("config", "dump");
        try {
            CapturedText result = runBoundedTextCommand(command, getRcloneEnv(), "config-dump",
                    MAX_CONFIG_JSON_CHARS, METADATA_COMMAND_TIMEOUT_MILLIS);
            if (!result.outcome.isSuccess() || result.exceededLimit
                    || result.outcome.isOutputTruncated()) {
                Toasty.error(context, context.getString(R.string.error_getting_remotes), Toast.LENGTH_SHORT, true).show();
                return null;
            }
            JSONObject parsed = new JSONObject(result.text);
            String currentDigest = EndpointConflictCoordinator.fingerprintFile(confFile);
            if (currentDigest == null || !digest.equals(currentDigest)
                    || confFile.lastModified() != mtime || confFile.length() != length) {
                return null;
            }
            synchronized (this) {
                cachedRemotesConfig = parsed;
                cachedConfMtime = mtime;
                cachedConfLength = length;
                cachedConfDigest = digest;
            }
            return parsed;
        } catch (IOException | JSONException e) {
            FLog.e(TAG, "getRemotes: error retrieving remotes", e);
            return null;
        }
    }

    /** Drop the cached config dump so the next getRemotes() re-reads it from rclone. */
    public void invalidateRemotesCache() {
        cachedRemotesConfig = null;
        cachedConfDigest = null;
    }

    /** Only use a config dump whose exact file contents still match the cached snapshot. */
    @Nullable
    private RemotesClaimSnapshot currentRemotesConfigForClaims() {
        try {
            ensureConfigFileRecovered();
        } catch (IOException recoveryFailure) {
            FLog.e(TAG, "Unable to recover rclone config before native endpoint validation", recoveryFailure);
            return null;
        }
        File confFile = new File(rcloneConf);
        long mtime = confFile.lastModified();
        long length = confFile.length();
        String digest = EndpointConflictCoordinator.fingerprintFile(confFile);
        if (digest == null || confFile.lastModified() != mtime || confFile.length() != length) {
            return null;
        }
        synchronized (this) {
            return cachedRemotesConfig != null && cachedConfMtime == mtime
                    && cachedConfLength == length && digest.equals(cachedConfDigest)
                    ? new RemotesClaimSnapshot(cachedRemotesConfig, mtime, length, digest) : null;
        }
    }

    private static final class RemotesClaimSnapshot {
        final JSONObject remotes;
        final long mtime;
        final long length;
        final String digest;

        RemotesClaimSnapshot(JSONObject remotes, long mtime, long length, String digest) {
            this.remotes = remotes;
            this.mtime = mtime;
            this.length = length;
            this.digest = digest;
        }

        boolean stillMatches(File configFile) {
            return configFile.lastModified() == mtime && configFile.length() == length
                    && digest.equals(EndpointConflictCoordinator.fingerprintFile(configFile));
        }
    }

    public RemoteItem getRemoteItemFromName(String remoteName) {
        List<RemoteItem> remoteItemList = getRemotes();
        for (RemoteItem remoteItem : remoteItemList) {
            if (remoteItem.getName().equals(remoteName)) {
                return remoteItem;
            }
        }
        return null;
    }


    private Process getRuntimeProcess(String[] command) throws IOException {
        return getRuntimeProcess(command, new String[0]);
    }

    private Process getRuntimeProcess(String[] command, String[] env) throws IOException {
        return getRuntimeProcess(command, env, null);
    }

    /** Starts native work, optionally fencing it to the config generation seen while browsing. */
    private Process getRuntimeProcess(String[] command, String[] env,
                                      @Nullable String expectedConfigRevision) throws IOException {
        return getRuntimeProcess(command, env, expectedConfigRevision, null);
    }

    private Process getRuntimeProcess(String[] command, String[] env,
                                      @Nullable String expectedConfigRevision,
                                      @Nullable StagedUploadSourceCleanup stagedSourceCleanup)
            throws IOException {
        ensureConfigFileRecovered();
        endpointConflictCoordinator.requireReadOnlyBisyncCommand(command);
        RemotesClaimSnapshot configSnapshot = currentRemotesConfigForClaims();
        ResourceClaimLease claim = endpointConflictCoordinator.acquireForCommand(
                command, configSnapshot == null ? null : configSnapshot.remotes, "native-process");
        if (configSnapshot != null && !configSnapshot.stillMatches(new File(rcloneConf))) {
            closeResourceClaim(claim);
            throw new IOException("Rclone config changed during endpoint validation; retry the operation");
        }
        if (expectedConfigRevision != null
                && !ConfigRevisionStore.matches(context, expectedConfigRevision)) {
            closeResourceClaim(claim);
            throw new IOException("Remote configuration changed; reopen the remote before deleting");
        }
        boolean configMutation = ConfigMutationCommandPolicy.isMutationCommand(command);
        if (configMutation && !ConfigRevisionStore.beginMutation(context)) {
            closeResourceClaim(claim);
            throw new IOException("Unable to establish a durable config-mutation barrier");
        }
        Process process = null;
        boolean launchAttempted = false;
        try {
            if (stagedSourceCleanup != null) {
                stagedSourceCleanup.markLaunchAttempted();
            }
            launchAttempted = true;
            process = Runtime.getRuntime().exec(command, env);
            pendingExecutionClaims.put(process, claim);
            if (configMutation) {
                pendingConfigRevisionMutations.put(process, Boolean.TRUE);
            }
            return process;
        } catch (IOException | RuntimeException | Error failure) {
            if (process == null && launchAttempted) {
                // Android can create the OS child before Process construction finishes; a
                // Runtime.exec exception does not prove that no process escaped. Keep staged
                // bytes and endpoint/config-mutation barriers fail-closed until recovery evidence
                // exists instead of inferring safety from the exception type.
                FLog.e(TAG, "Native launch returned no process handle; preserving ownership");
                throw failure;
            }
            boolean claimOwnershipResolved = false;
            if (process != null) {
                // The process is not returned to the legacy Process adapter, so remove any
                // partial bookkeeping before transferring the claim to the failure reaper.
                pendingExecutionClaims.remove(process);
                pendingConfigRevisionMutations.remove(process);
                NativeExecutionHandle failedLaunch = NativeExecutionHandle.adopt(
                        process, "launch-bookkeeping-failed");
                if (failedLaunch != null) {
                    AutoCloseable ownedClaim = claimWithConfigRevisionFinalizer(claim, configMutation);
                    boolean claimAttached = failedLaunch.attachResource(ownedClaim);
                    if (stagedSourceCleanup != null) {
                        stagedSourceCleanup.attachTo(failedLaunch);
                    }
                    failedLaunch.cancelAndAwait(null, null);
                    if (claimAttached) {
                        claimOwnershipResolved = true;
                    } else if (failedLaunch.isExitConfirmed()) {
                        closeOwnedClaim(ownedClaim);
                        claimOwnershipResolved = true;
                    }
                }
            }
            if (!claimOwnershipResolved) {
                if (configMutation) {
                    finishConfigRevisionMutation();
                }
                closeResourceClaim(claim);
            }
            throw failure;
        }
    }

    /** Starts a direct native handle with its durable endpoint claim acquired before launch. */
    private NativeExecutionHandle launchClaimed(String[] command, String[] env, String label)
            throws IOException {
        ensureConfigFileRecovered();
        endpointConflictCoordinator.requireReadOnlyBisyncCommand(command);
        RemotesClaimSnapshot configSnapshot = currentRemotesConfigForClaims();
        ResourceClaimLease claim = endpointConflictCoordinator.acquireForCommand(
                command, configSnapshot == null ? null : configSnapshot.remotes, label);
        if (configSnapshot != null && !configSnapshot.stillMatches(new File(rcloneConf))) {
            closeResourceClaim(claim);
            throw new IOException("Rclone config changed during endpoint validation; retry the operation");
        }
        boolean configMutation = ConfigMutationCommandPolicy.isMutationCommand(command);
        if (configMutation && !ConfigRevisionStore.beginMutation(context)) {
            closeResourceClaim(claim);
            throw new IOException("Unable to establish a durable config-mutation barrier");
        }
        // Environment construction may precede acquisition of the global claim. If a config
        // replacement revoked the cached password in that interval, do not launch with it.
        if (!"decrypt-config".equals(label)) {
            ConfigSecretStore.BoundSecret cachedSecret = configSecret;
            if (cachedSecret != null && !configSecretStore.isCurrent(cachedSecret)) {
                env = withoutConfigPassword(env, cachedSecret.password());
            }
        }
        AutoCloseable ownedClaim = claimWithConfigRevisionFinalizer(claim, configMutation);
        // A failed Runtime.exec may have crossed the OS child-creation boundary without
        // returning a Process. Keep the durable endpoint claim/config barrier fail-closed;
        // launchOwned attaches the finalizer before exposing any successfully returned handle.
        return NativeExecutionHandle.launchOwned(command, env, label, ownedClaim);
    }

    private static String[] withoutConfigPassword(String[] environment, String revokedPassword) {
        if (environment == null || revokedPassword == null) {
            return environment;
        }
        String revokedValue = "RCLONE_CONFIG_PASS=" + revokedPassword;
        ArrayList<String> filtered = new ArrayList<>(environment.length);
        for (String entry : environment) {
            if (!revokedValue.equals(entry)) {
                filtered.add(entry);
            }
        }
        return filtered.toArray(new String[0]);
    }

    /** Adopts a legacy private launcher and transfers its pre-launch durable claim to the handle. */
    @Nullable
    private NativeExecutionHandle adoptClaimed(@Nullable Process process, String label) {
        return adoptClaimed(process, label, null);
    }

    @Nullable
    private NativeExecutionHandle adoptClaimed(@Nullable Process process, String label,
            @Nullable StagedUploadSourceCleanup stagedSourceCleanup) {
        if (process == null) {
            if (stagedSourceCleanup != null) {
                stagedSourceCleanup.cleanupAfter(null);
            }
            return null;
        }
        ResourceClaimLease claim = pendingExecutionClaims.remove(process);
        if (claim == null) {
            NativeExecutionHandle unclaimed = NativeExecutionHandle.adopt(process, label);
            ResourceClaimLease quarantine = endpointConflictCoordinator
                    .quarantineUnclaimedProcess("unclaimed-" + label);
            boolean attached = unclaimed.attachResource(quarantine);
            if (stagedSourceCleanup != null) {
                stagedSourceCleanup.attachTo(unclaimed);
            }
            NativeExecutionHandle.Outcome stopped = unclaimed.cancelAndAwait(null, null);
            if (!attached && stopped.isConfirmed()) {
                closeResourceClaim(quarantine);
            } else if (!stopped.isConfirmed()) {
                FLog.e(TAG, "Unclaimed native process could not be confirmed stopped; global quarantine remains active");
            }
            throw new IllegalStateException("Native process was launched without an endpoint claim");
        }
        boolean configMutation = Boolean.TRUE.equals(pendingConfigRevisionMutations.remove(process));
        NativeExecutionHandle execution = NativeExecutionHandle.adopt(process, label);
        if (execution == null) {
            closeResourceClaim(claim);
            return null;
        }
        AutoCloseable ownedClaim = claimWithConfigRevisionFinalizer(claim, configMutation);
        if (!execution.attachResource(ownedClaim)) {
            if (stagedSourceCleanup != null) {
                stagedSourceCleanup.attachTo(execution);
                execution.cancelAndAwait(null, null);
            } else {
                execution.cancel();
            }
            throw new IllegalStateException("Unable to attach native endpoint ownership");
        }
        if (stagedSourceCleanup != null && !stagedSourceCleanup.attachTo(execution)) {
            execution.cancelAndAwait(null, null);
            throw new IllegalStateException("Unable to attach staged upload cleanup");
        }
        return execution;
    }

    private void closeOwnedClaim(AutoCloseable claim) {
        try {
            claim.close();
        } catch (Exception failure) {
            FLog.e(TAG, "Unable to release a confirmed native endpoint claim", failure);
        }
    }

    private void closeResourceClaim(@Nullable ResourceClaimLease claim) {
        if (claim == null) return;
        try {
            claim.close();
        } catch (RuntimeException failure) {
            FLog.e(TAG, "Unable to release a confirmed native endpoint claim", failure);
        }
    }

    /**
     * Invalidates queued remote targets and removes the active config only while no native
     * operation owns an endpoint. Used by the explicit app-reset flow before its data wipe.
     */
    public boolean deleteConfigForReset() {
        try (ResourceClaimLease ignored = endpointConflictCoordinator.acquireGlobal("app-reset-config")) {
            ensureConfigFileRecovered();
            if (!ConfigRevisionStore.beginMutation(context)) {
                return false;
            }
            File configFile = new File(rcloneConf);
            ConfigSecretStore.InvalidationToken secretInvalidation;
            try {
                secretInvalidation = configSecretStore.invalidateForConfigReplacement();
            } catch (ConfigSecretStore.InvalidationException failure) {
                boolean recovered = restoreResetInvalidationIfSafe(configFile, failure.token());
                if (!recovered) {
                    FLog.e(TAG, "Could not recover config-secret state after reset invalidation failed");
                }
                return false;
            }
            try {
                if (!ConfigFileRestorer.delete(configFile)) {
                    if (!restoreResetInvalidationIfSafe(configFile, secretInvalidation)) {
                        FLog.e(TAG, "Config reset state is uncertain; revision barrier remains pending");
                    }
                    return false;
                }
                configSecretStore.clear();
                configSecret = null;
                invalidateRemotesCache();
                return finishConfigRevisionMutation();
            } catch (IOException | RuntimeException failure) {
                if (secretInvalidation != null && configFile.exists()
                        && !restoreResetInvalidationIfSafe(configFile, secretInvalidation)) {
                    FLog.e(TAG, "Config reset rollback is uncertain; revision barrier remains pending");
                }
                throw failure;
            }
        } catch (IOException | RuntimeException failure) {
            FLog.e(TAG, "Unable to safely remove rclone config during app reset");
            return false;
        }
    }

    private boolean restoreResetInvalidationIfSafe(
            File configFile, @Nullable ConfigSecretStore.InvalidationToken token) {
        return ConfigResetRecoveryPolicy.restoreIfConfigUnchanged(
                configFile.exists(),
                new File(configFile.getPath() + ".bak").exists(),
                token != null,
                () -> configSecretStore.restoreInvalidation(token),
                this::finishConfigRevisionMutation);
    }

    private AutoCloseable claimWithConfigRevisionFinalizer(
            @Nullable ResourceClaimLease claim, boolean configMutation) {
        return () -> {
            if (configMutation) {
                finishConfigRevisionMutation();
            }
            closeResourceClaim(claim);
        };
    }

    private boolean finishConfigRevisionMutation() {
        boolean finalized = ConfigRevisionStore.finishMutation(context);
        if (!finalized) {
            // The durable pending marker keeps every old queued target invalid across process death.
            FLog.e(TAG, "Config revision could not be finalized; destructive targets remain disabled");
        }
        return finalized;
    }

    private void ensureConfigFileRecovered() throws IOException {
        try {
            ConfigFileRestorer.recover(new File(rcloneConf));
            configFileRecoveryFailure = null;
        } catch (IOException recoveryFailure) {
            configFileRecoveryFailure = recoveryFailure;
            throw new IOException("Rclone config recovery is incomplete", recoveryFailure);
        }
    }

    /** Holds the global configuration claim across a multi-store backup import or rollback. */
    public ConfigTransaction beginConfigTransaction(String operation) throws IOException {
        ResourceClaimLease claim = endpointConflictCoordinator.acquireGlobal(operation);
        try {
            return new ConfigTransaction(claim, configSecretStore.snapshot());
        } catch (RuntimeException failure) {
            claim.close();
            throw failure;
        }
    }

    public final class ConfigTransaction implements AutoCloseable {
        private final ResourceClaimLease claim;
        private ConfigSecretStore.Snapshot secretSnapshot;
        private final AtomicBoolean closed = new AtomicBoolean(false);
        private ConfigSecretStore.InvalidationToken secretRestoreInvalidation;
        private ConfigSecretStore.InvalidationToken configReplacementSecretInvalidation;
        private boolean configReplaced;
        private boolean configSnapshotRestored;

        private ConfigTransaction(ResourceClaimLease claim, ConfigSecretStore.Snapshot secretSnapshot) {
            this.claim = claim;
            this.secretSnapshot = secretSnapshot;
        }

        public File snapshotConfigFile() throws IOException {
            ensureOpen();
            return snapshotConfigFileInternal();
        }

        public boolean commitStagedConfigFile(File stagedFile) throws IOException {
            ensureOpen();
            return commitStagedConfigFileInternal(stagedFile, this);
        }

        public boolean configWasReplaced() {
            ensureOpen();
            return configReplaced;
        }

        public void restoreConfigSnapshot(@Nullable File snapshot) throws IOException {
            ensureOpen();
            restoreConfigSnapshotInternal(snapshot, this);
        }

        public boolean configSnapshotWasRestored() {
            ensureOpen();
            return configSnapshotRestored;
        }

        /** Restores the encrypted pre-import password only if no newer password was saved. */
        public void restoreConfigSecretSnapshot() throws IOException {
            ensureOpen();
            if (!configSnapshotRestored || secretRestoreInvalidation == null
                    || !secretRestoreInvalidation.follows(configReplacementSecretInvalidation)
                    || !configSecretStore.restoreSnapshot(
                            secretSnapshot, secretRestoreInvalidation.expectedGeneration())) {
                throw new IOException("Saved config password changed during import rollback");
            }
            configSecret = null;
            if (!finishConfigRevisionMutation()) {
                throw new IOException("Config and password were restored, but the revision barrier remains pending");
            }
        }

        private void ensureOpen() {
            if (closed.get()) throw new IllegalStateException("Config transaction is closed");
        }

        @Override
        public void close() {
            if (closed.compareAndSet(false, true)) claim.close();
        }
    }

    @Nullable
    private RemoteItem getRemoteType(JSONObject remotesJSON, RemoteItem remoteItem, String remoteName, int maxDepth) {
        Iterator<String> iterator = remotesJSON.keys();

        while (iterator.hasNext()) {
            String key = iterator.next();

            if (!key.equals(remoteName)) {
                continue;
            }

            try {
                JSONObject remoteJSON = new JSONObject(remotesJSON.get(key).toString());
                String type = remoteJSON.optString("type");
                if (type.trim().isEmpty()) {
                    return null;
                }

                boolean recurse = true;
                switch (type) {
                    case "crypt":
                        remoteItem.setIsCrypt(true);
                        break;
                    case "alias":
                        remoteItem.setIsAlias(true);
                        break;
                    case "cache":
                        remoteItem.setIsCache(true);
                        break;
                    default:
                        recurse = false;
                }

                if (recurse && maxDepth > 0) {
                    String remote = remoteJSON.optString("remote");
                    if (remote.trim().isEmpty() || (!remote.contains(":") && !remote.startsWith("/"))) {
                        return null;
                    }

                    if (remote.startsWith("/")) { // local remote
                        remoteItem.setType("local");
                        remoteItem.setIsPathAlias(true);
                        return remoteItem;
                    } else {
                        int index = remote.indexOf(":");
                        remote = remote.substring(0, index);
                        return getRemoteType(remotesJSON, remoteItem, remote, --maxDepth);
                    }
                }
                remoteItem.setType(type);
                return remoteItem;
            } catch (JSONException e) {
                FLog.e(TAG, "getRemoteType: error decoding remote type", e);
            }
        }

        return null;
    }

    @Nullable
    private Process configCreate(List<String> options) {
        // https://rclone.org/commands/rclone_config_create/
        // See the NB-comment why we need to pass --obscure.
        // Otherwise long passwords fail.
        options.add("--obscure");
        return config("create" , options);
    }

    /**
     * Like configCreate but passes --non-interactive and --no-output so the backend's Config()
     * function is invoked but exits immediately returning no JSON questions. Only the
     * key/value pairs are saved to rclone.conf. Use this when a separate `config reconnect`
     * step will handle the interactive auth.
     */
    private Process configCreateNoInteract(List<String> options) {
        options.add("--obscure");
        options.add("--non-interactive");
        options.add("--no-output");
        return config("create", options);
    }

    @Nullable
    private Process configUpdate(List<String> options) {
        return configCreate(options);
    }
    
    private Process config(String task, List<String> options) {
        invalidateRemotesCache();
        String[] command = createCommand("config", task);
        String[] opt = options.toArray(new String[0]);
        String[] commandWithOptions = new String[command.length + options.size()];

        System.arraycopy(command, 0, commandWithOptions, 0, command.length);

        System.arraycopy(opt, 0, commandWithOptions, command.length, opt.length);

        String[] env = getRcloneEnv();
        try {
            return getRuntimeProcess(commandWithOptions, env);
        } catch (IOException e) {
            FLog.e(TAG, "configCreate: error starting rclone", e);
            return null;
        }
    }

    @Nullable
    public HashMap<String, String> getConfig(String name) {
        HashMap<String, String> options = new HashMap<>();
        String output = configDump();
        if (output == null) {
            Toasty.error(context, context.getString(R.string.error_getting_config), Toast.LENGTH_SHORT, true).show();
            return options;
        }
        JSONObject configs;
        try {
            configs = new JSONObject(output);
        } catch (JSONException e) {
            // Config JSON includes credentials; never include parser text or the source in logs.
            FLog.e(TAG, "getConfig: config dump was not valid JSON");
            return options;
        }

        JSONObject selectedConfig = configs.optJSONObject(name);
        if (selectedConfig == null) {
            return options;
        }
        Iterator<String> keys = selectedConfig.keys();

        while(keys.hasNext()) {
            String key = keys.next();
            options.put(key,  selectedConfig.optString(key));
        }

        options.put(RCLONE_CONFIG_NAME_KEY,  name);
        return options;
        
    }

    public NativeExecutionHandle configInteractiveOwned() throws IOException {
        String[] command = createCommand("config");
        String[] environment = getRcloneEnv();
        return launchClaimed(command, environment, "config-interactive");
    }

    public void deleteRemote(String remoteName) {
        invalidateRemotesCache();
        String[] command = createCommandWithOptions("config", "delete", remoteName);
        runCommandSuccessfully(command, getRcloneEnv(), "config-delete", METADATA_COMMAND_TIMEOUT_MILLIS);
    }

    public String obscure(String pass) {
        String[] command = createCommand("obscure", pass);
        return runFirstMetadataLine(command, getRcloneEnv(), "obscure");
    }

    private Process serve(int protocol, int port, boolean allowRemoteAccess, @Nullable String user,
                         @Nullable String password, @NonNull RemoteItem remote, @Nullable String servePath,
                         @Nullable String baseUrl) {
        String remoteName = remote.getName();
        String localRemotePath = (remote.isRemoteType(RemoteItem.LOCAL)) ? getLocalRemotePathPrefix(remote, context)  + "/" : "";
        String path = (servePath.compareTo("//" + remoteName) == 0) ? remoteName + ":" + localRemotePath : remoteName + ":" + localRemotePath + servePath;
        String address;
        String commandProtocol;

        switch (protocol) {
            case SERVE_PROTOCOL_HTTP:
                commandProtocol = "http";
                break;
            case SERVE_PROTOCOL_FTP:
                commandProtocol = "ftp";
                break;
            case SERVE_PROTOCOL_DLNA:
                commandProtocol = "dlna";
                break;
            default:
                commandProtocol = "webdav";
        }

        if (allowRemoteAccess && (user == null || user.length() == 0 || password == null || password.length() == 0)) {
            FLog.w(TAG, "serve: remote access requested without credentials, binding to localhost");
            address = "127.0.0.1:" + String.valueOf(port);
        } else if (allowRemoteAccess) {
            address = ":" + String.valueOf(port);
        } else {
            address = "127.0.0.1:" + String.valueOf(port);
        }

        ArrayList<String> params = new ArrayList<>(Arrays.asList(
                createCommandWithOptions("serve", commandProtocol, "--addr", address, path)));

        // Transfer throughput tuning. These are rclone global / VFS flags; the existing
        // serve command already appends subcommand flags (e.g. --user) after the path and
        // rclone's parser accepts them in this position.
        params.add("--transfers");
        params.add(getTransfers());
        params.add("--buffer-size");
        params.add("16M");
        params.add("--multi-thread-streams");
        params.add("4");
        params.add("--vfs-read-chunk-size");
        params.add("4M");
        params.add("--vfs-read-chunk-size-limit");
        params.add("off");
        params.add("--dir-cache-time");
        params.add("5m");

        if(null != user && user.length() > 0) {
            params.add("--user");
            params.add(user);
        }

        if(null != password && password.length() > 0) {
            params.add("--pass");
            params.add(password);
        }

        if(null != baseUrl && baseUrl.length() > 0) {
            params.add("--baseurl");
            params.add(baseUrl);
        }

        SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(context);
        boolean isLoggingEnabled = sharedPreferences.getBoolean(context.getString(R.string.pref_key_logs), false);
        if (isLoggingEnabled) {
            SyncLog.info(context, "Rclone daemon diagnostics",
                    NativeDiagnosticCommandPolicy.DISABLED_NOTICE);
        }

        String[] env = getRcloneEnv();
        String[] command = NativeDiagnosticCommandPolicy.withoutNativeDiagnostics(
                params.toArray(new String[0]));
        try {
            return getRuntimeProcess(command, env);
        } catch (IOException e) {
            FLog.e(TAG, "serve: error starting rclone", e);
            // todo: guard callers against null result
            return null;
        }
    }

    private Process serve(int protocol, int port, boolean allowRemoteAccess, String user, String password, RemoteItem remote, String servePath) {
        return serve(protocol, port, allowRemoteAccess, user, password, remote, servePath, null);
    }

    /** WP05 owner boundary for a long-lived rclone serve process. */
    @Nullable
    public NativeExecutionHandle serveOwned(int protocol, int port, boolean allowRemoteAccess,
                                            @Nullable String user, @Nullable String password,
                                            @NonNull RemoteItem remote, @Nullable String servePath) {
        return serveOwned(protocol, port, allowRemoteAccess, user, password, remote, servePath, null);
    }

    /** WP05 owner boundary variant retaining the optional serve base URL used by thumbnails. */
    @Nullable
    public NativeExecutionHandle serveOwned(int protocol, int port, boolean allowRemoteAccess,
                                            @Nullable String user, @Nullable String password,
                                            @NonNull RemoteItem remote, @Nullable String servePath,
                                            @Nullable String baseUrl) {
        return adoptClaimed(
                serve(protocol, port, allowRemoteAccess, user, password, remote, servePath, baseUrl),
                "serve");
    }

    /**
     * This is only kept for legacy purposes. It was used before md5-checksum was introduced.
     * @param remoteItem
     * @param localPath
     * @param remotePath
     * @param syncDirection
     * @return
     */
    @Deprecated
    private Process sync(RemoteItem remoteItem, String localPath, String remotePath, int syncDirection) {
        return sync(remoteItem, localPath, remotePath, syncDirection, false, new ArrayList<>(0), false);
    }

    private Process sync(RemoteItem remoteItem, String localPath, String remotePath, int syncDirection, boolean useMD5Sum, ArrayList<FilterEntry> filters, boolean deleteExcluded) {
        return sync(remoteItem, localPath, remotePath, syncDirection, useMD5Sum, filters, deleteExcluded, null);
    }

    private Process sync(RemoteItem remoteItem, String localPath, String remotePath, int syncDirection, boolean useMD5Sum, ArrayList<FilterEntry> filters, boolean deleteExcluded, String transfersOverride) {
        // Cloud-to-cloud directions require a second remote; route to the dedicated overload.
        if (syncDirection == SyncDirectionObject.SYNC_REMOTE_TO_REMOTE
                || syncDirection == SyncDirectionObject.COPY_REMOTE_TO_REMOTE) {
            return null;
        }
        return syncLocalRemote(remoteItem, localPath, remotePath, syncDirection, useMD5Sum, filters, deleteExcluded, transfersOverride);
    }

    /**
     * Cloud-to-cloud sync/copy between two remotes. The primary {@code remoteItem/remotePath} pair is
     * the source; {@code remoteItem2/remotePath2} is the destination. rclone performs a server-side
     * copy when the backend pair supports it, otherwise data streams through this device's rclone
     * process.
     */
    private Process sync(RemoteItem remoteItem, String remotePath, RemoteItem remoteItem2, String remotePath2, int syncDirection, boolean useMD5Sum, ArrayList<FilterEntry> filters, boolean deleteExcluded, String transfersOverride) {
        if (syncDirection != SyncDirectionObject.SYNC_REMOTE_TO_REMOTE
                && syncDirection != SyncDirectionObject.COPY_REMOTE_TO_REMOTE) {
            return null;
        }
        String[] command;
        String srcSection = buildRemoteSection(remoteItem, remotePath, context);
        String dstSection = buildRemoteSection(remoteItem2, remotePath2, context);
        String op = (syncDirection == SyncDirectionObject.SYNC_REMOTE_TO_REMOTE) ? "sync" : "copy";

        ArrayList<String> defaultParameter = new ArrayList<>(Arrays.asList("--transfers", getTransfers(transfersOverride), "--stats=1s", "--stats-log-level", "NOTICE", "--use-json-log"));
        if (useMD5Sum) {
            defaultParameter.add("--checksum");
        }
        if (deleteExcluded) {
            defaultParameter.add("--delete-excluded");
        }
        for (FilterEntry filter : filters) {
            defaultParameter.add("--filter");
            defaultParameter.add((filter.filterType == FilterEntry.FILTER_INCLUDE ? "+ " : "- ") + filter.filter);
        }

        ArrayList<String> directionParameter = new ArrayList<>();
        Collections.addAll(directionParameter, op, srcSection, dstSection);
        directionParameter.addAll(defaultParameter);
        command = createCommandWithOptions(directionParameter);

        String[] env = getRcloneEnv();
        try {
            return getRuntimeProcess(command, env);
        } catch (IOException e) {
            FLog.e(TAG, "sync: error starting rclone", e);
            return null;
        }
    }

    private Process syncLocalRemote(RemoteItem remoteItem, String localPath, String remotePath, int syncDirection, boolean useMD5Sum, ArrayList<FilterEntry> filters, boolean deleteExcluded, String transfersOverride) {
        String[] command;
        String remoteSection = buildRemoteSection(remoteItem, remotePath, context);

        ArrayList<String> defaultParameter = new ArrayList<>(Arrays.asList("--transfers", getTransfers(transfersOverride), "--stats=1s", "--stats-log-level", "NOTICE", "--use-json-log"));
        ArrayList<String> directionParameter = new ArrayList<>();

        if(useMD5Sum){
            defaultParameter.add("--checksum");
        }
        if(deleteExcluded){
            defaultParameter.add("--delete-excluded");
        }

        for (FilterEntry filter : filters) {
            defaultParameter.add("--filter");
            defaultParameter.add((filter.filterType == FilterEntry.FILTER_INCLUDE ? "+ " : "- ") + filter.filter);
        }

        if (syncDirection == SyncDirectionObject.SYNC_LOCAL_TO_REMOTE) {
            Collections.addAll(directionParameter, "sync", localPath, remoteSection);
            directionParameter.addAll(defaultParameter);
            command = createCommandWithOptions(directionParameter);
        } else if (syncDirection == SyncDirectionObject.SYNC_REMOTE_TO_LOCAL) {
            Collections.addAll(directionParameter, "sync", remoteSection, localPath);
            directionParameter.addAll(defaultParameter);
            command = createCommandWithOptions(directionParameter);
        } else if (syncDirection == SyncDirectionObject.COPY_LOCAL_TO_REMOTE) {
            Collections.addAll(directionParameter, "copy", localPath, remoteSection);
            directionParameter.addAll(defaultParameter);
            command = createCommandWithOptions(directionParameter);
        }else if (syncDirection == SyncDirectionObject.COPY_REMOTE_TO_LOCAL) {
            Collections.addAll(directionParameter, "copy", remoteSection, localPath);
            directionParameter.addAll(defaultParameter);
            command = createCommandWithOptions(directionParameter);
        }else {
            return null;
        }

        String[] env = getRcloneEnv();
        try {
            return getRuntimeProcess(command, env);
        } catch (IOException e) {
            FLog.e(TAG, "sync: error starting rclone", e);
            return null;
        }
    }

    /**
     * WP05 compatibility adapter for the durable sync worker. The legacy command builders still
     * expose Process for older UI callers, but the worker receives the single owner boundary as
     * soon as the native process is launched.
     */
    @Nullable
    public NativeExecutionHandle syncOwned(RemoteItem remoteItem, String localPath, String remotePath,
                                           int syncDirection, boolean useMD5Sum,
                                           ArrayList<FilterEntry> filters, boolean deleteExcluded,
                                           String transfersOverride) {
        return adoptClaimed(
                sync(remoteItem, localPath, remotePath, syncDirection, useMD5Sum, filters,
                        deleteExcluded, transfersOverride),
                "sync");
    }

    /** WP05 compatibility adapter for cloud-to-cloud sync/copy operations. */
    @Nullable
    public NativeExecutionHandle syncOwned(RemoteItem remoteItem, String remotePath,
                                           RemoteItem remoteItem2, String remotePath2,
                                           int syncDirection, boolean useMD5Sum,
                                           ArrayList<FilterEntry> filters, boolean deleteExcluded,
                                           String transfersOverride) {
        return adoptClaimed(
                sync(remoteItem, remotePath, remoteItem2, remotePath2, syncDirection, useMD5Sum,
                        filters, deleteExcluded, transfersOverride),
                "cloud-sync");
    }

    private Process downloadFile(RemoteItem remote, FileItem downloadItem, String downloadPath) {
        String[] command;
        String remoteFilePath;
        String localFilePath;

        remoteFilePath = remote.getName() + ":";
        if (remote.isRemoteType(RemoteItem.LOCAL) && (!remote.isAlias() && !remote.isCrypt() && !remote.isCache())) {
            remoteFilePath += getLocalRemotePathPrefix(remote, context)  + "/";
        }
        remoteFilePath += downloadItem.getPath();

        if (downloadItem.isDir()) {
            localFilePath = downloadPath + "/" + downloadItem.getName();
        } else {
            localFilePath = downloadPath;
        }

        localFilePath = encodePath(localFilePath);

        command = createCommandWithOptions("copy", remoteFilePath, localFilePath, "--transfers", getTransfers(), "--stats=1s", "--stats-log-level", "NOTICE", "--use-json-log");

        String[] env = getRcloneEnv();
        try {
            return getRuntimeProcess(command, env);
        } catch (IOException e) {
            FLog.e(TAG, "downloadFile: error starting rclone", e);
            return null;
        }
    }

    private Process uploadFile(RemoteItem remote, String uploadPath, String uploadFile) {
        return uploadFile(remote, uploadPath, uploadFile, null);
    }

    private Process uploadFile(RemoteItem remote, String uploadPath, String uploadFile,
                               @Nullable StagedUploadSourceCleanup stagedSourceCleanup) {
        String remoteName = remote.getName();
        String path;
        String[] command;
        String localRemotePath;

        if (remote.isRemoteType(RemoteItem.LOCAL) && (!remote.isAlias() && !remote.isCrypt() && !remote.isCache())) {
            localRemotePath = getLocalRemotePathPrefix(remote, context) + "/";
        } else {
            localRemotePath = "";
        }

        File file = new File(uploadFile);
        if (file.isDirectory()) {
            int index = uploadFile.lastIndexOf('/');
            String dirName = uploadFile.substring(index + 1);
            path = (uploadPath.compareTo("//" + remoteName) == 0) ? remoteName + ":" + localRemotePath + dirName : remoteName + ":" + localRemotePath + uploadPath + "/" + dirName;
        } else {
            path = (uploadPath.compareTo("//" + remoteName) == 0) ? remoteName + ":" + localRemotePath : remoteName + ":" + localRemotePath + uploadPath;
        }

        command = createCommandWithOptions("copy", uploadFile, path, "--transfers", getTransfers(), "--stats=1s", "--stats-log-level", "NOTICE", "--use-json-log");

        String[] env = getRcloneEnv();
        try {
            return getRuntimeProcess(command, env, null, stagedSourceCleanup);
        } catch (IOException e) {
            FLog.e(TAG, "uploadFile: error starting rclone", e);
            return null;
        }

    }

    /** WP05 compatibility adapter for the ephemeral transfer worker. */
    @Nullable
    public NativeExecutionHandle downloadFileOwned(RemoteItem remote, FileItem downloadItem, String downloadPath) {
        return adoptClaimed(downloadFile(remote, downloadItem, downloadPath), "download");
    }

    /** WP05 compatibility adapter for the ephemeral transfer worker. */
    @Nullable
    public NativeExecutionHandle uploadFileOwned(RemoteItem remote, String uploadPath, String uploadFile) {
        return adoptClaimed(uploadFile(remote, uploadPath, uploadFile), "upload");
    }

    /** WP02 adapter attaches staged-source cleanup before cancellation or waiting can race it. */
    @Nullable
    public NativeExecutionHandle uploadFileOwned(RemoteItem remote, String uploadPath,
            String uploadFile, @Nullable StagedUploadSourceCleanup stagedSourceCleanup) {
        return adoptClaimed(uploadFile(remote, uploadPath, uploadFile, stagedSourceCleanup),
                "upload", stagedSourceCleanup);
    }

    // Can't pass \u0000 as cmd arg - encode like rclone with U+2400
    // Ref: Appcenter #22305285
    // TODO Appcenter #170195533 - rclone serve
    @NonNull
    private String encodePath(String localFilePath) {
        if (localFilePath.indexOf('\u0000') < 0) {
            return localFilePath;
        }
        StringBuilder localPathBuilder = new StringBuilder(localFilePath.length());
        for (char c : localFilePath.toCharArray()) {
            if (c == '\u0000') {
                localPathBuilder.append('\u2400');
            } else {
                localPathBuilder.append(c);
            }

        }
        return localPathBuilder.toString();
    }

    private Process deleteItems(RemoteItem remote, FileItem deleteItem, String expectedConfigRevision) {
        if (!ConfigRevisionPolicy.isValidRevision(expectedConfigRevision)) {
            return null;
        }
        if (remote == null || deleteItem == null
                || !RemoteDeleteTargetPolicy.isSafeTarget(deleteItem.getPath(), deleteItem.getName())) {
            FLog.w(TAG, "Refusing delete launch for an invalid target snapshot");
            return null;
        }
        String[] command;
        String filePath;
        Process process = null;
        String localRemotePath;

        if (remote.isRemoteType(RemoteItem.LOCAL) && (!remote.isAlias() && !remote.isCrypt() && !remote.isCache())) {
            localRemotePath = getLocalRemotePathPrefix(remote, context) + "/";
        } else {
            localRemotePath = "";
        }

        filePath = remote.getName() + ":" + localRemotePath + deleteItem.getPath();
        if (deleteItem.isDir()) {
            command = createCommandWithOptions("purge", filePath);
        } else {
            command = createCommandWithOptions("deletefile", filePath);
        }

        String[] env = getRcloneEnv();
        try {
            process = getRuntimeProcess(command, env, expectedConfigRevision);
        } catch (IOException e) {
            FLog.e(TAG, "deleteItems: error starting rclone", e);
        }
        return process;
    }

    public Boolean makeDirectory(RemoteItem remote, String path) {
        String localRemotePath;

        if (remote.isRemoteType(RemoteItem.LOCAL) && (!remote.isAlias() && !remote.isCrypt() && !remote.isCache())) {
            localRemotePath = getLocalRemotePathPrefix(remote, context) + "/";
        } else {
            localRemotePath = "";
        }

        String newDir = remote.getName() + ":" + localRemotePath + path;
        String[] command = createCommandWithOptions("mkdir", newDir);
        return runCommandSuccessfully(command, getRcloneEnv(), "mkdir", METADATA_COMMAND_TIMEOUT_MILLIS);
    }

    private Process moveTo(RemoteItem remote, FileItem moveItem, String newLocation) {
        String remoteName = remote.getName();
        String[] command;
        String oldFilePath;
        String newFilePath;
        Process process = null;
        String localRemotePath;

        if (remote.isRemoteType(RemoteItem.LOCAL) && (!remote.isAlias() && !remote.isCrypt() && !remote.isCache())) {
            localRemotePath = getLocalRemotePathPrefix(remote, context) + "/";
        } else {
            localRemotePath = "";
        }

        oldFilePath = remoteName + ":" + localRemotePath + moveItem.getPath();
        newFilePath = (newLocation.compareTo("//" + remoteName) == 0) ? remoteName + ":" + localRemotePath + moveItem.getName() : remoteName + ":" + localRemotePath + newLocation + "/" + moveItem.getName();
        command = createCommandWithOptions("moveto", oldFilePath, newFilePath);
        String[] env = getRcloneEnv();
        try {
            process = getRuntimeProcess(command, env);
        } catch (IOException e) {
            FLog.e(TAG, "moveTo: error starting rclone", e);
        }

        return process;
    }

    /** WP05 compatibility adapter for the ephemeral transfer worker. */
    @Nullable
    public NativeExecutionHandle moveToOwned(RemoteItem remote, FileItem moveItem, String newLocation) {
        return adoptClaimed(moveTo(remote, moveItem, newLocation), "move");
    }

    /** WP05 compatibility adapter for the ephemeral transfer worker. */
    @Nullable
    public NativeExecutionHandle deleteItemsOwned(RemoteItem remote, FileItem deleteItem) {
        return deleteItemsOwned(remote, deleteItem, null);
    }

    /** Deletes only if the remote config still matches the generation captured before browsing. */
    @Nullable
    public NativeExecutionHandle deleteItemsOwned(
            RemoteItem remote, FileItem deleteItem, @Nullable String expectedConfigRevision) {
        if (remote == null || deleteItem == null
                || !ConfigRevisionPolicy.isValidRevision(expectedConfigRevision)) {
            FLog.w(TAG, "Refusing delete launch without a valid remote config revision");
            return null;
        }
        return adoptClaimed(deleteItems(remote, deleteItem, expectedConfigRevision), "delete");
    }

    public Boolean moveTo(RemoteItem remote, String oldFile, String newFile) {
        String remoteName = remote.getName();
        String localRemotePath;

        if (remote.isRemoteType(RemoteItem.LOCAL) && (!remote.isAlias() && !remote.isCrypt() && !remote.isCache())) {
            localRemotePath = getLocalRemotePathPrefix(remote, context) + "/";
        } else {
            localRemotePath = "";
        }

        String oldFilePath = remoteName + ":" + localRemotePath + oldFile;
        String newFilePath = remoteName + ":" + localRemotePath + newFile;
        String[] command = createCommandWithOptions("moveto", oldFilePath, newFilePath);
        return runCommandSuccessfully(command, getRcloneEnv(), "moveto", METADATA_COMMAND_TIMEOUT_MILLIS);
    }

    public InputStream downloadToPipe(String rclonePath) throws IOException {
        return downloadToPipe(rclonePath, null);
    }

    public InputStream downloadToPipe(String rclonePath, @Nullable CancellationSignal cancellationSignal) throws IOException {
        if (cancellationSignal != null && cancellationSignal.isCanceled()) {
            throw new IOException("Transfer cancelled before native launch");
        }
        String[] command = createCommandWithOptions("cat", rclonePath);
        NativeExecutionHandle execution = launchClaimed(command, getRcloneEnv(), "download-to-pipe");
        registerPipeCancellation(execution, cancellationSignal);
        DiagnosticCapture diagnostics = new DiagnosticCapture();
        InputStream output;
        try {
            output = execution.openOutputPipe(diagnostics);
        } catch (RuntimeException failure) {
            clearPipeCancellation(cancellationSignal);
            execution.cancel();
            throw failure;
        }
        awaitPipeCompletion(execution, "downloadToPipe", diagnostics, cancellationSignal);
        return output;
    }

    public OutputStream uploadFromPipe(String rclonePath) throws IOException {
        return uploadFromPipe(rclonePath, null);
    }

    public OutputStream uploadFromPipe(String rclonePath, @Nullable CancellationSignal cancellationSignal) throws IOException {
        if (cancellationSignal != null && cancellationSignal.isCanceled()) {
            throw new IOException("Transfer cancelled before native launch");
        }
        String[] command = createCommandWithOptions("rcat", rclonePath, "--streaming-upload-cutoff", "500K");
        NativeExecutionHandle execution = launchClaimed(command, getRcloneEnv(), "upload-from-pipe");
        registerPipeCancellation(execution, cancellationSignal);
        DiagnosticCapture diagnostics = new DiagnosticCapture();
        OutputStream input;
        try {
            input = execution.openInputPipe(diagnostics);
        } catch (RuntimeException failure) {
            clearPipeCancellation(cancellationSignal);
            execution.cancel();
            throw failure;
        }
        awaitPipeCompletion(execution, "uploadFromPipe", diagnostics, cancellationSignal);
        return input;
    }

    private static void registerPipeCancellation(NativeExecutionHandle execution,
                                                @Nullable CancellationSignal cancellationSignal) {
        if (cancellationSignal != null) {
            cancellationSignal.setOnCancelListener(execution::cancel);
        }
    }

    private static void clearPipeCancellation(@Nullable CancellationSignal cancellationSignal) {
        if (cancellationSignal != null) {
            cancellationSignal.setOnCancelListener(null);
        }
    }

    private void awaitPipeCompletion(NativeExecutionHandle execution, String label,
                                     DiagnosticCapture diagnostics,
                                     @Nullable CancellationSignal cancellationSignal) {
        Thread waiter = new Thread(() -> {
            try {
                NativeExecutionHandle.Outcome outcome = execution.await(NativeExecutionHandle.NO_TIMEOUT, null, null);
                if (!outcome.isSuccess()) {
                    FLog.w(TAG, "%s ended with native state %s", label, outcome.getState());
                    logNativeDiagnostics(diagnostics);
                }
            } finally {
                clearPipeCancellation(cancellationSignal);
            }
        }, "cloudbridge-" + label);
        waiter.setDaemon(true);
        waiter.start();
    }

    public boolean emptyTrashCan(String remote) {
        String[] command = createCommandWithOptions("cleanup", remote + ":");
        return runCommandSuccessfully(command, getRcloneEnv(), "cleanup", METADATA_COMMAND_TIMEOUT_MILLIS);
    }

    public String link(RemoteItem remote, String filePath) {
        String linkPath = remote.getName() + ":";
        linkPath += (remote.isRemoteType(RemoteItem.LOCAL)) ? getLocalRemotePathPrefix(remote, context) + "/" : "";
        if (!filePath.equals("//" + remote.getName())) {
            linkPath += filePath;
        }
        String[] command = createCommandWithOptions("link", linkPath);
        return runFirstMetadataLine(command, getRcloneEnv(), "link");
    }

    public String calculateMD5(RemoteItem remote, FileItem fileItem) {
        String localRemotePath;

        if (remote.isRemoteType(RemoteItem.LOCAL) && (!remote.isAlias() && !remote.isCrypt() && !remote.isCache())) {
            localRemotePath = getLocalRemotePathPrefix(remote, context) + "/";
        } else {
            localRemotePath = "";
        }

        String remoteAndPath = remote.getName() + ":" + localRemotePath + fileItem.getName();
        String[] command = createCommandWithOptions("md5sum", remoteAndPath);
        return parseHashLine(runFirstMetadataLine(command, getRcloneEnv(), "md5sum"));
    }

    public String calculateSHA1(RemoteItem remote, FileItem fileItem) {
        String localRemotePath;

        if (remote.isRemoteType(RemoteItem.LOCAL) && (!remote.isAlias() && !remote.isCrypt() && !remote.isCache())) {
            localRemotePath = getLocalRemotePathPrefix(remote, context) + "/";
        } else {
            localRemotePath = "";
        }

        String remoteAndPath = remote.getName() + ":" + localRemotePath + fileItem.getName();
        String[] command = createCommandWithOptions("sha1sum", remoteAndPath);
        return parseHashLine(runFirstMetadataLine(command, getRcloneEnv(), "sha1sum"));
    }

    private String parseHashLine(@Nullable String line) {
        if (line == null || line.trim().isEmpty()) {
            return context.getString(R.string.hash_error);
        }
        String[] split = line.split("\\s+");
        return split[0].trim().isEmpty()
                ? context.getString(R.string.hash_unsupported) : split[0];
    }

    private static final class CapturedText {
        final String text;
        final NativeExecutionHandle.Outcome outcome;
        final boolean exceededLimit;

        CapturedText(String text, NativeExecutionHandle.Outcome outcome, boolean exceededLimit) {
            this.text = text;
            this.outcome = outcome;
            this.exceededLimit = exceededLimit;
        }
    }

    /** WP05 owned entry point for config commands, including their output drains and reap. */
    @Nullable
    public NativeExecutionHandle configOwned(String task, List<String> options) {
        return adoptClaimed(config(task, new ArrayList<>(options)), "config-" + task);
    }

    @Nullable
    public NativeExecutionHandle configCreateOwned(List<String> options) {
        return adoptClaimed(configCreate(new ArrayList<>(options)), "config-create");
    }

    @Nullable
    public NativeExecutionHandle configCreateNoInteractOwned(List<String> options) {
        return adoptClaimed(configCreateNoInteract(new ArrayList<>(options)), "config-create-noninteractive");
    }

    @Nullable
    public NativeExecutionHandle configUpdateOwned(List<String> options) {
        return adoptClaimed(configUpdate(new ArrayList<>(options)), "config-update");
    }

    /** Drain both pipes while retaining at most maxChars of stdout, including line separators. */
    private CapturedText runBoundedTextCommand(String[] command, String[] env, String label,
                                               int maxChars, long timeoutMillis) throws IOException {
        return runBoundedTextCommand(command, env, label, maxChars, timeoutMillis, null);
    }

    private CapturedText runBoundedTextCommand(String[] command, String[] env, String label,
                                               int maxChars, long timeoutMillis,
                                               @Nullable NativeExecutionHandle.LineSink stderrSink) throws IOException {
        return runBoundedTextCommand(command, env, label, maxChars, timeoutMillis, stderrSink, null);
    }

    private CapturedText runBoundedTextCommandCancellable(String[] command, String[] env, String label,
                                                         int maxChars, long timeoutMillis,
                                                         @Nullable CancellationSignal cancellationSignal)
            throws IOException {
        return runBoundedTextCommand(command, env, label, maxChars, timeoutMillis, null, cancellationSignal);
    }

    private CapturedText runBoundedTextCommand(String[] command, String[] env, String label,
                                               int maxChars, long timeoutMillis,
                                               @Nullable NativeExecutionHandle.LineSink stderrSink,
                                               @Nullable CancellationSignal cancellationSignal) throws IOException {
        StringBuilder output = new StringBuilder(Math.min(maxChars, 4096));
        AtomicBoolean exceededLimit = new AtomicBoolean(false);
        NativeExecutionHandle handle = launchClaimed(command, env, label);
        if (cancellationSignal != null) {
            cancellationSignal.setOnCancelListener(handle::cancel);
            if (cancellationSignal.isCanceled()) handle.cancel();
        }
        NativeExecutionHandle.Outcome outcome;
        try {
            outcome = handle.await(timeoutMillis, line -> {
                if (exceededLimit.get()) {
                    return;
                }
                if (line.length() + 1 > maxChars - output.length()) {
                    exceededLimit.set(true);
                    return;
                }
                output.append(line).append('\n');
            }, stderrSink);
        } finally {
            if (cancellationSignal != null) cancellationSignal.setOnCancelListener(null);
        }
        boolean tooLarge = exceededLimit.get();
        String captured = outcome.isConfirmed() && !outcome.isOutputTruncated() && !tooLarge
                ? output.toString() : "";
        return new CapturedText(captured, outcome, tooLarge);
    }

    private boolean runCommandSuccessfully(String[] command, String[] env, String label, long timeoutMillis) {
        try {
            NativeExecutionHandle handle = launchClaimed(command, env, label);
            DiagnosticCapture diagnostics = new DiagnosticCapture();
            NativeExecutionHandle.Outcome outcome = handle.await(timeoutMillis, null, diagnostics);
            if (!outcome.isSuccess()) {
                FLog.e(TAG, "%s: native command ended with state %s", label, outcome.getState());
                logNativeDiagnostics(diagnostics);
            }
            return outcome.isSuccess();
        } catch (IOException e) {
            FLog.e(TAG, label + ": native command failed to start", e);
            return false;
        }
    }

    /** A small text-only command: drain both pipes before reporting its first stdout line. */
    @Nullable
    private String runFirstMetadataLine(String[] command, String[] env, String label) {
        try {
            AtomicReference<String> firstLine = new AtomicReference<>();
            NativeExecutionHandle handle = launchClaimed(command, env, label);
            NativeExecutionHandle.Outcome outcome = handle.await(
                    METADATA_COMMAND_TIMEOUT_MILLIS,
                    line -> firstLine.compareAndSet(null, line), null);
            if (!outcome.isSuccess() || outcome.isOutputTruncated()) {
                FLog.e(TAG, "%s: native command ended with state %s", label, outcome.getState());
                return null;
            }
            return firstLine.get();
        } catch (IOException e) {
            FLog.e(TAG, label + ": native command failed to start", e);
            return null;
        }
    }

    public String getRcloneVersion() {
        String[] command = createCommand("--version");
        String firstLine = runFirstMetadataLine(command, getRcloneEnv(), "version");
        if (firstLine == null) {
            return "-1";
        }
        String[] version = firstLine.split("\\s+");
        if (version.length < 2) {
            return "-1";
        }
        return version[1];
    }

    private Process reconnectRemote(RemoteItem remoteItem) {
        String[] command = createCommand("config", "update", remoteItem.getName());

        try {
            return getRuntimeProcess(command, getRcloneEnv());
        } catch (IOException e) {
            return null;
        }
    }

    /** WP05 owned entry point for OAuth reconnect, whose prompts are handled by InteractiveRunner. */
    @Nullable
    public NativeExecutionHandle reconnectRemoteOwned(RemoteItem remoteItem) {
        return adoptClaimed(reconnectRemote(remoteItem), "config-reconnect");
    }

    public AboutResult aboutRemote(RemoteItem remoteItem) {
        String remoteName = remoteItem.getName() + ':';
        String[] command = createCommand("about", "--json", remoteName);
        AboutResult stats;
        JSONObject aboutJSON;

        try {
            CapturedText result = runBoundedTextCommand(command, getRcloneEnv(), "about",
                    MAX_ABOUT_JSON_CHARS, METADATA_COMMAND_TIMEOUT_MILLIS);
            if (!result.outcome.isSuccess() || result.exceededLimit
                    || result.outcome.isOutputTruncated()) {
                FLog.e(TAG, "aboutRemote: native command ended with state %s",
                        result.outcome.getState());
                return new AboutResult();
            }
            aboutJSON = new JSONObject(result.text);
        } catch (IOException | JSONException e) {
            FLog.e(TAG, "aboutRemote: unexpected error", e);
            return new AboutResult();
        }

        try {
            stats = new AboutResult(
                    aboutJSON.opt("used") != null ? aboutJSON.getLong("used") : -1,
                    aboutJSON.opt("total") != null ? aboutJSON.getLong("total") : -1,
                    aboutJSON.opt("free") != null ? aboutJSON.getLong("free") : -1,
                    aboutJSON.opt("trashed") != null ? aboutJSON.getLong("trashed") : -1
            );
        } catch (JSONException e) {
            FLog.e(TAG, "aboutRemote: JSON format error ", e);
            return new AboutResult();
        }

        return stats;
    }

    public String configDump() {
        String[] command = createCommand("config", "dump");
        try {
            CapturedText result = runBoundedTextCommand(command, getRcloneEnv(), "config-dump",
                    MAX_CONFIG_JSON_CHARS, METADATA_COMMAND_TIMEOUT_MILLIS);
            if (!result.outcome.isSuccess() || result.exceededLimit
                    || result.outcome.isOutputTruncated()) {
                FLog.e(TAG, "configDump: native command ended with state %s",
                        result.outcome.getState());
                return null;
            }
            return result.text;
        } catch (IOException e) {
            FLog.e(TAG, "configDump: unexpected error", e);
            return null;
        }
    }

    public DirectoryProbeResult listDirectories(String remoteName, int maxDepth) {
        String[] command = createCommand("lsd", "--max-depth", String.valueOf(maxDepth), remoteName + ":");
        try {
            AtomicBoolean networkError = new AtomicBoolean(false);
            AtomicBoolean authenticationError = new AtomicBoolean(false);
            AtomicBoolean rateLimited = new AtomicBoolean(false);
            AtomicBoolean integrityError = new AtomicBoolean(false);
            NativeExecutionHandle handle = launchClaimed(command, getRcloneEnv(), "lsd");
            NativeExecutionHandle.Outcome outcome = handle.await(METADATA_COMMAND_TIMEOUT_MILLIS,
                    null, line -> {
                        switch (DirectoryProbeResult.classifyFailureLine(line)) {
                            case NETWORK: networkError.set(true); break;
                            case AUTHENTICATION: authenticationError.set(true); break;
                            case RATE_LIMITED: rateLimited.set(true); break;
                            case INTEGRITY: integrityError.set(true); break;
                            default: break;
                        }
                    });
            int exitCode = outcome.getExitCode() == null ? -1 : outcome.getExitCode();
            if ((!outcome.isSuccess() && exitCode == 0) || outcome.isOutputTruncated()) {
                exitCode = -1;
            }
            // Do not expose raw, potentially secret-bearing stderr to the guardian worker.
            String category = exitCode == 0 ? ""
                    : networkError.get() || outcome.getState() == NativeExecutionHandle.TerminalState.TIMED_OUT
                    ? "network is unreachable"
                    : authenticationError.get() ? "authentication required"
                    : rateLimited.get() ? "provider rate limited"
                    : integrityError.get() ? "integrity check failed"
                    : "rclone probe failed";
            return new DirectoryProbeResult(exitCode, category);
        } catch (IOException e) {
            FLog.e(TAG, "listDirectories: native command failed to start", e);
            return new DirectoryProbeResult(-1, "rclone probe failed");
        }
    }

    /**
     * Result of a directory probe (lsd) operation. The stderr field contains only a
     * sanitized category, never raw provider output or credentials.
     */
    public static class DirectoryProbeResult {
        public enum FailureCategory {
            NONE,
            NETWORK,
            AUTHENTICATION,
            RATE_LIMITED,
            INTEGRITY,
            OTHER
        }

        private final int exitCode;
        private final String stderr;
        private final FailureCategory failureCategory;

        public DirectoryProbeResult(int exitCode, String stderr) {
            this.exitCode = exitCode;
            this.failureCategory = categoryFromSanitizedLabel(exitCode, stderr);
            this.stderr = labelFor(this.failureCategory);
        }

        public int getExitCode() {
            return exitCode;
        }

        public String getStderr() {
            return stderr;
        }

        public boolean isSuccess() {
            return exitCode == 0;
        }

        public FailureCategory getFailureCategory() {
            return failureCategory;
        }

        /**
         * Returns true if the error is a transient network issue (DNS failure,
         * connection refused, timeout) rather than an authentication problem.
         */
        public boolean isNetworkError() {
            return failureCategory == FailureCategory.NETWORK;
        }

        public boolean isAuthenticationError() {
            return failureCategory == FailureCategory.AUTHENTICATION;
        }

        public boolean isRateLimited() {
            return failureCategory == FailureCategory.RATE_LIMITED;
        }

        public boolean isIntegrityError() {
            return failureCategory == FailureCategory.INTEGRITY;
        }

        static FailureCategory classifyFailureLine(String text) {
            switch (SessionProbeFailureClassifier.classifyLine(text)) {
                case NETWORK: return FailureCategory.NETWORK;
                case AUTHENTICATION: return FailureCategory.AUTHENTICATION;
                case RATE_LIMITED: return FailureCategory.RATE_LIMITED;
                case INTEGRITY: return FailureCategory.INTEGRITY;
                default: return FailureCategory.OTHER;
            }
        }

        private static FailureCategory categoryFromSanitizedLabel(int exitCode, String label) {
            if (exitCode == 0) return FailureCategory.NONE;
            if ("network is unreachable".equals(label)) return FailureCategory.NETWORK;
            if ("authentication required".equals(label)) return FailureCategory.AUTHENTICATION;
            if ("provider rate limited".equals(label)) return FailureCategory.RATE_LIMITED;
            if ("integrity check failed".equals(label)) return FailureCategory.INTEGRITY;
            return FailureCategory.OTHER;
        }

        private static String labelFor(FailureCategory category) {
            switch (category) {
                case NONE: return "";
                case NETWORK: return "network is unreachable";
                case AUTHENTICATION: return "authentication required";
                case RATE_LIMITED: return "provider rate limited";
                case INTEGRITY: return "integrity check failed";
                default: return "rclone probe failed";
            }
        }

    }

    public class AboutResult {
        private final long used;
        private final long total;
        private final long free;
        private final long trashed;
        private boolean failed;

        public AboutResult(long used, long total, long free, long trashed) {
            this.used = used;
            this.total = total;
            this.free = free;
            this.trashed = trashed;
            this.failed = false;
        }

        public AboutResult () {
            this(-1, -1, -1,  -1);
            this.failed = true;
        }

        public long getUsed() {
            return used;
        }

        public long getTotal() {
            return total;
        }

        public long getFree() {
            return free;
        }

        public long getTrashed() {
            return trashed;
        }

        public boolean hasFailed(){
            return failed;
        }
    }

    public ConfigEncryptionProbePolicy.Status getConfigEncryptionStatus() {
        return probeConfigEncryption(isConfigFileCreated());
    }

    /** Compatibility adapter for callers that need a conservative yes/no password gate. */
    public Boolean isConfigEncrypted() {
        boolean configFileExists = isConfigFileCreated();
        return ConfigEncryptionProbePolicy.shouldTreatAsEncrypted(configFileExists,
                probeConfigEncryption(configFileExists));
    }

    private ConfigEncryptionProbePolicy.Status probeConfigEncryption(boolean configFileExists) {
        if (!configFileExists) return ConfigEncryptionProbePolicy.Status.UNKNOWN;
        String[] command = createCommand( "--ask-password=false", "listremotes");
        AtomicBoolean passwordFailure = new AtomicBoolean(false);
        try {
            CapturedText result = runBoundedTextCommand(command, getRcloneEnv(), "list-remotes",
                    MAX_CONFIG_JSON_CHARS, METADATA_COMMAND_TIMEOUT_MILLIS,
                    line -> {
                        if (ConfigEncryptionProbePolicy.isRecognizedPasswordFailureLine(line)) {
                            passwordFailure.set(true);
                        }
                    });
            return ConfigEncryptionProbePolicy.classify(result.outcome.getState(),
                    result.outcome.getExitCode(), passwordFailure.get());
        } catch (IOException e) {
            FLog.e(TAG, "Unable to inspect config encryption state", e);
            return ConfigEncryptionProbePolicy.Status.UNKNOWN;
        }
    }

    public Boolean decryptConfig(String password) {
        String expectedGeneration = configSecretStore.currentGeneration();
        String[] command = createCommand("--ask-password=false", "config", "show");
        NativeExecutionHandle handle;
        try {
            handle = launchClaimed(command,
                    getRcloneEnv("RCLONE_CONFIG_PASS=" + password), "decrypt-config");
        } catch (IOException e) {
            FLog.e(TAG, "decryptConfig: error running rclone", e);
            return false;
        }
        // Do not persist or log the plaintext config/show output.
        if (!handle.await(METADATA_COMMAND_TIMEOUT_MILLIS, null, null).isSuccess()) {
            return false;
        }

        try {
            configSecret = configSecretStore.save(password, expectedGeneration);
        } catch (Exception e) {
            configSecret = null;
            FLog.e(TAG, "decryptConfig: error persisting Keystore-wrapped password", e);
            return false;
        }
        return true;
    }

    public boolean isConfigFileCreated() {
        String appsFileDir = context.getFilesDir().getPath();
        String configFile = appsFileDir + "/rclone.conf";
        File file = new File(configFile);
        return file.exists();
    }

    // on all devices, look under ./Android/data/ca.pkay.rcloneexplorer/files/rclone.conf
    public Uri searchExternalConfig(){
        File[] extDir = context.getExternalFilesDirs(null);
        for(File dir : extDir){
            File file = new File(dir + "/rclone.conf");
            if(file.exists() && isValidConfig(file.getAbsolutePath())){
                return Uri.fromFile(file);
            }
        }
        return null;
    }

    /** Opens the user-provided URI once, copies it to private storage, and validates the full ZIP. */
    public BackupArchiveStager.StagedArchive stageBackupArchive(Uri uri) throws IOException {
        return BackupArchiveStager.stage(() -> {
            try {
                InputStream input = context.getContentResolver().openInputStream(uri);
                if (input == null) {
                    throw new IOException("Unable to open backup");
                }
                return input;
            } catch (NullPointerException unavailableUri) {
                throw new IOException("Unable to open backup", unavailableUri);
            }
        }, context.getFilesDir());
    }

    /** Compatibility wrapper; multi-component imports should share one StagedArchive handle. */
    public File getFileFromZip(Uri uri, String target, File targetfile) throws IOException {
        try (BackupArchiveStager.StagedArchive archive = stageBackupArchive(uri)) {
            return getFileFromZip(archive, target, targetfile);
        }
    }

    private File getFileFromZip(BackupArchiveStager.StagedArchive archive,
                                String target, File targetfile) throws IOException {
        if (archive == null || target == null || targetfile == null) {
            throw new IOException("Backup entry target is unavailable");
        }
        if (!archive.hasEntry(target)) {
            return null;
        }
        byte[] contents = archive.readEntry(target);
        boolean complete = false;
        try {
            try (FileOutputStream output = new FileOutputStream(targetfile, false)) {
                output.write(contents);
                output.flush();
            }
            complete = true;
            return targetfile;
        } finally {
            if (!complete && targetfile.exists() && !targetfile.delete()) {
                FLog.w(TAG, "Unable to remove incomplete extracted backup entry");
            }
        }
    }

    public String readDatabaseJson(Uri uri) throws Exception {
        try (BackupArchiveStager.StagedArchive archive = stageBackupArchive(uri)) {
            return readDatabaseJson(archive);
        }
    }

    public String readDatabaseJson(BackupArchiveStager.StagedArchive archive) throws Exception {
        return readTextfileFromZip(archive, BackupArchiveStager.DATABASE_ENTRY);
    }

    public String readSharedPrefs(Uri uri) throws Exception {
        try (BackupArchiveStager.StagedArchive archive = stageBackupArchive(uri)) {
            return readSharedPrefs(archive);
        }
    }

    public String readSharedPrefs(BackupArchiveStager.StagedArchive archive) throws Exception {
        return readTextfileFromZip(archive, BackupArchiveStager.PREFERENCES_ENTRY);
    }

    /** Compatibility URI wrapper retained for callers outside the ZIP-import flow. */
    public String readTextfileFromZip(Uri uri, String tempfile, String targetfile) throws Exception {
        try (BackupArchiveStager.StagedArchive archive = stageBackupArchive(uri)) {
            return readTextfileFromZip(archive, targetfile);
        }
    }

    private String readTextfileFromZip(BackupArchiveStager.StagedArchive archive,
                                       String targetfile) throws IOException {
        String json = new String(archive.readEntry(targetfile), StandardCharsets.UTF_8);
        if (json.length() > Importer.MAX_IMPORT_CHARS) {
            throw new IOException("Backup JSON exceeds the maximum size");
        }
        return json;
    }

    public boolean copyConfigFileFromZip(Uri uri) throws Exception {
        File tempFile = stageConfigFileFromZip(uri);
        if (tempFile == null) {
            return false;
        }
        try {
            return commitStagedConfigFile(tempFile);
        } finally {
            if (tempFile.exists()) {
                tempFile.delete();
            }
        }
    }

    /** Extract and validate a config without replacing the current known-good config. */
    public File stageConfigFileFromZip(Uri uri) throws Exception {
        try (BackupArchiveStager.StagedArchive archive = stageBackupArchive(uri)) {
            return stageConfigFileFromZip(archive);
        }
    }

    public File stageConfigFileFromZip(BackupArchiveStager.StagedArchive archive) throws Exception {
        File tempFile = File.createTempFile("rclone.conf-import-", ".tmp", context.getFilesDir());
        boolean valid = false;
        try {
            File extracted = getFileFromZip(archive, BackupArchiveStager.CONFIG_ENTRY, tempFile);
            if (extracted == null || !isValidConfig(extracted.getAbsolutePath())) {
                return null;
            }
            valid = true;
            return extracted;
        } finally {
            if (!valid && tempFile.exists() && !tempFile.delete()) {
                FLog.w(TAG, "Unable to remove invalid staged backup config");
            }
        }
    }

    /** Atomically replaces the app config within its private files directory. */
    public boolean commitStagedConfigFile(File stagedFile) throws IOException {
        try (ResourceClaimLease ignored = endpointConflictCoordinator.acquireGlobal("config-import")) {
            return commitStagedConfigFileInternal(stagedFile);
        }
    }

    private boolean commitStagedConfigFileInternal(File stagedFile) throws IOException {
        return commitStagedConfigFileInternal(stagedFile, null);
    }

    private boolean commitStagedConfigFileInternal(
            File stagedFile, @Nullable ConfigTransaction transaction) throws IOException {
        if (stagedFile == null || !stagedFile.isFile()) {
            throw new IOException("Staged config is missing");
        }
        File configFile = new File(context.getFilesDir(), "rclone.conf");
        if (!stagedFile.getParentFile().equals(configFile.getParentFile())) {
            throw new IOException("Staged config is outside the app files directory");
        }
        ensureConfigFileRecovered();
        if (!ConfigRevisionStore.beginMutation(context)) {
            throw new IOException("Unable to establish a durable config-mutation barrier");
        }
        ConfigSecretStore.InvalidationToken secretInvalidation = null;
        boolean configReplaced = false;
        try {
            // Revoke saved and in-memory secrets before publishing a different config. A failed
            // preference commit leaves the active config untouched and aborts this replacement.
            // Keep the encrypted preimage so an in-process rollback can re-enable it safely.
            try {
                secretInvalidation = configSecretStore.invalidateForConfigReplacement();
            } catch (ConfigSecretStore.InvalidationException failedInvalidation) {
                secretInvalidation = failedInvalidation.token();
                if (transaction != null) {
                    transaction.secretSnapshot = secretInvalidation.snapshot();
                    transaction.configReplacementSecretInvalidation = secretInvalidation;
                }
                throw failedInvalidation;
            }
            if (transaction != null) {
                transaction.secretSnapshot = secretInvalidation.snapshot();
                transaction.configReplacementSecretInvalidation = secretInvalidation;
            }
            ConfigFileRestorer.restore(stagedFile, configFile, MAX_BACKUP_ENTRY_BYTES);
            configReplaced = true;
            if (transaction != null) {
                transaction.configReplaced = true;
            }
            if (stagedFile.exists() && !stagedFile.delete()) {
                throw new IOException("Config was replaced but its staged file could not be removed");
            }
            // A replacement may belong to another account or use another config password.
            // Do not reuse the old passphrase against it; the next unlock is explicit.
            configSecret = null;
            invalidateRemotesCache();
        } catch (IOException | RuntimeException failure) {
            boolean priorConfigProven = failure instanceof ConfigSecretStore.InvalidationException
                    || (failure instanceof ConfigFileRestorer.ReplacementException
                    && ((ConfigFileRestorer.ReplacementException) failure).isPreviousStateRestored());
            boolean priorSecretProven = secretInvalidation == null
                    || (!configReplaced && priorConfigProven
                    && configSecretStore.restoreInvalidation(secretInvalidation));
            if (!configReplaced && priorConfigProven && priorSecretProven) {
                finishConfigRevisionMutation();
            } else {
                FLog.e(TAG, "Config replacement state is uncertain; keeping destructive-operation barrier pending",
                        failure);
            }
            throw failure;
        }
        if (!finishConfigRevisionMutation()) {
            throw new IOException("Config changed, but its revision could not be finalized");
        }
        return true;
    }

    /** Snapshot the current config so a multi-part backup import can roll back safely. */
    @Nullable
    public File snapshotConfigFile() throws IOException {
        try (ResourceClaimLease ignored = endpointConflictCoordinator.acquireGlobal("config-snapshot")) {
            return snapshotConfigFileInternal();
        }
    }

    @Nullable
    private File snapshotConfigFileInternal() throws IOException {
        ensureConfigFileRecovered();
        File configFile = new File(rcloneConf);
        if (!configFile.isFile()) {
            return null;
        }
        File snapshot = File.createTempFile("rclone.conf-before-import-", ".bak", context.getFilesDir());
        try {
            copyFile(configFile, snapshot);
            return snapshot;
        } catch (IOException e) {
            snapshot.delete();
            throw e;
        }
    }

    /** Restore a snapshot created by {@link #snapshotConfigFile()}. */
    public void restoreConfigSnapshot(@Nullable File snapshot) throws IOException {
        try (ResourceClaimLease ignored = endpointConflictCoordinator.acquireGlobal("config-restore")) {
            restoreConfigSnapshotInternal(snapshot, null);
        }
    }

    private ConfigSecretStore.InvalidationToken restoreConfigSnapshotInternal(
            @Nullable File snapshot, @Nullable ConfigTransaction transaction) throws IOException {
        File configFile = new File(rcloneConf);
        if (!ConfigRevisionStore.beginMutation(context)) {
            throw new IOException("Unable to establish a durable config-mutation barrier");
        }
        ConfigSecretStore.InvalidationToken secretInvalidation = null;
        try {
            ensureConfigFileRecovered();
            if (snapshot != null && (!snapshot.isFile()
                    || !snapshot.getParentFile().equals(configFile.getParentFile()))) {
                throw new IOException("Config snapshot is invalid");
            }
            // Snapshot restore is also a config replacement. Revoke cached credentials before
            // deleting or renaming either side; the restored config can be unlocked explicitly.
            try {
                secretInvalidation = configSecretStore.invalidateForConfigReplacement();
            } catch (ConfigSecretStore.InvalidationException failedInvalidation) {
                secretInvalidation = failedInvalidation.token();
                if (transaction != null) {
                    transaction.secretRestoreInvalidation = secretInvalidation;
                }
                throw failedInvalidation;
            }
            if (transaction != null) {
                // Preserve the token even if subsequent file/revision work fails.
                transaction.secretRestoreInvalidation = secretInvalidation;
            }
            configSecret = null;
            if (snapshot == null) {
                if (!ConfigFileRestorer.delete(configFile)) {
                    throw new IOException("Unable to remove imported config during rollback");
                }
            } else {
                ConfigFileRestorer.restore(snapshot, configFile, MAX_BACKUP_ENTRY_BYTES);
            }
            invalidateRemotesCache();
            if (transaction != null) transaction.configSnapshotRestored = true;
        } catch (IOException | RuntimeException failure) {
            if (failure instanceof ConfigSecretStore.InvalidationException
                    && secretInvalidation != null
                    && !configSecretStore.restoreInvalidation(secretInvalidation)) {
                FLog.e(TAG, "Could not restore config-secret generation after rollback invalidation failed");
            }
            // The database/preferences may already be rolling back to match this config.
            // Keep mutation_pending set until recovery proves the prior config was restored.
            FLog.e(TAG, "Config rollback is incomplete; preserving the destructive-operation barrier",
                    failure);
            throw failure;
        }
        if (transaction == null && !finishConfigRevisionMutation()) {
            throw new IOException("Config changed, but its revision could not be finalized");
        }
        return secretInvalidation;
    }

    private static void copyFile(File source, File destination) throws IOException {
        try (InputStream input = new FileInputStream(source);
             OutputStream output = new FileOutputStream(destination, false)) {
            byte[] buffer = new byte[8192];
            long total = 0;
            int count;
            while ((count = input.read(buffer)) != -1) {
                total += count;
                if (total > MAX_BACKUP_ENTRY_BYTES) {
                    throw new IOException("Config exceeds the maximum size");
                }
                output.write(buffer, 0, count);
            }
            output.flush();
        }
    }


    /***
     * This function replaces the config by replacing rclone.conf.
     * First a rclone.conf-tmp is created, which is then verified to be working.
     * Then the rclone.conf is beeing replaced by the temp file.
     * @param uri Uri to the new rclone file.
     * @return True if rclone.conf has been replaced, false if not.
     * @throws IOException
     */
    public boolean copyConfigFile(Uri uri) throws IOException {
        InputStream inputStream;
        // The exact cause of the NPE is unknown, but the effect is the same
        // - the copy process has failed, therefore bubble an IOException
        // for handling at the appropriate layers.
        try {
            inputStream = context.getContentResolver().openInputStream(uri);
        } catch(NullPointerException e) {
            throw new IOException(e);
        }
        if (inputStream == null) {
            throw new IOException("Unable to open config");
        }

        try (InputStream input = inputStream) {
            return copyConfigFile(input);
        }
    }

    /**
     * Stages and validates the supplied config bytes without reopening their source.
     * The caller retains ownership of and must close {@code inputStream}.
     */
    public boolean copyConfigFile(InputStream inputStream) throws IOException {
        if (inputStream == null) {
            throw new IOException("Unable to open config");
        }

        File tempFile = new File(context.getFilesDir(), "rclone.conf-import-" + System.nanoTime());
        try (FileOutputStream output = new FileOutputStream(tempFile, false)) {
            byte[] buffer = new byte[4096];
            long total = 0;
            int offset;
            while ((offset = inputStream.read(buffer)) != -1) {
                total += offset;
                if (total > MAX_BACKUP_ENTRY_BYTES) {
                    throw new IOException("Config exceeds the maximum size");
                }
                output.write(buffer, 0, offset);
            }
            output.flush();
        } catch (IOException e) {
            tempFile.delete();
            throw e;
        }

        try {
            if (!isValidConfig(tempFile.getAbsolutePath())) {
                return false;
            }
            return commitStagedConfigFile(tempFile);
        } finally {
            if (tempFile.exists()) {
                tempFile.delete();
            }
        }
    }

    public boolean isValidConfig(String path) {
        if (isValidConfig(path, getRcloneEnv())) {
            return true;
        }
        // A newly imported plaintext config must remain usable even if the previous
        // config was encrypted and its passphrase is still cached in this process.
        return isValidConfig(path, new String[0]);
    }

    private boolean isValidConfig(String path, String[] environment) {
        String[] command = {rclone, "-vvv", "--ask-password=false", "--config", path, "listremotes"};
        return runCommandSuccessfully(command, environment, "validate-config",
                METADATA_COMMAND_TIMEOUT_MILLIS);
    }

    public void exportConfigFile(Uri uri) throws IOException {
        try (ResourceClaimLease ignored = endpointConflictCoordinator.acquireGlobal("config-export")) {
            File configFile = new File(rcloneConf);
            if (!configFile.isFile() || configFile.length() > MAX_BACKUP_ENTRY_BYTES) {
                throw new IOException("Config is missing or exceeds the export size limit");
            }
            Uri config = Uri.fromFile(configFile);
            InputStream inputStream = context.getContentResolver().openInputStream(config);
            OutputStream outputStream = context.getContentResolver().openOutputStream(uri);
            if (inputStream == null || outputStream == null) {
                if (inputStream != null) inputStream.close();
                if (outputStream != null) outputStream.close();
                throw new IOException("Unable to open configuration export streams");
            }
            try (InputStream input = inputStream; ZipOutputStream zos = new ZipOutputStream(outputStream)) {
                final byte[] databaseBackup;
                final byte[] preferencesBackup;
                try {
                    databaseBackup = Exporter.create(this.context).getBytes(StandardCharsets.UTF_8);
                    preferencesBackup = SharedPreferencesBackup.export(context).getBytes(StandardCharsets.UTF_8);
                } catch (JSONException serializationFailure) {
                    throw new IOException("Unable to serialize configuration backup", serializationFailure);
                }
                ZipEntry zipEntry = new ZipEntry("rcx.json");
                zos.putNextEntry(zipEntry);
                zos.write(databaseBackup);
                zos.closeEntry();
                zipEntry = new ZipEntry("rcx.prefs");
                zos.putNextEntry(zipEntry);
                zos.write(preferencesBackup);
                zos.closeEntry();
                zipEntry = new ZipEntry("rclone.conf");
                zos.putNextEntry(zipEntry);
                byte[] buffer = new byte[8192];
                long total = 0L;
                for (int count; (count = input.read(buffer)) != -1; ) {
                    total += count;
                    if (total > MAX_BACKUP_ENTRY_BYTES) {
                        throw new IOException("Config exceeds the export size limit");
                    }
                    zos.write(buffer, 0, count);
                }
                zos.closeEntry();
            }
        }
    }

    public boolean isCompatible() {
        if (isCompatible != null) {
            return isCompatible;
        }
        synchronized (Rclone.class) {
            if (isCompatible == null) {
                isCompatible = checkCompatibility();
            }
        }
        return isCompatible;
    }

    private boolean checkCompatibility() {
        String nativelibraryDir = context.getApplicationInfo().nativeLibraryDir;
        File nativeRcloneBinary = new File(nativelibraryDir, "librclone.so");
        if (!nativeRcloneBinary.exists()) {
            return false;
        }
        if ("-1".equals(getRcloneVersion())) {
            return false;
        }
        return true;
    }

    /**
     * Prefixes local remotes with a base path on the primary external storage.
     * @param item
     * @param context
     * @return
     */
    /**
     * Builds the canonical {@code remoteName:[localPrefix]path} argument rclone expects for a
     * remote endpoint. Centralizes the {@code "//"+name} root-sentinel handling and the LOCAL-remote
     * storage prefix that were duplicated across call sites. Used for both source and destination
     * of cloud-to-cloud operations.
     */
    public static String buildRemoteSection(RemoteItem remoteItem, String remotePath, Context context) {
        String remoteName = remoteItem.getName();
        String localRemotePath = (remoteItem.isRemoteType(RemoteItem.LOCAL))
                ? getLocalRemotePathPrefix(remoteItem, context) + "/" : "";
        if (("//" + remoteName).equals(remotePath)) {
            return remoteName + ":" + localRemotePath;
        }
        return remoteName + ":" + localRemotePath + remotePath;
    }

    public static String getLocalRemotePathPrefix(RemoteItem item, Context context) {
        if (item.isPathAlias()) {
            return "";
        }
        // lower version boundary check if legacy external storage = false
        if(Build.VERSION.SDK_INT > Build.VERSION_CODES.Q) {
            File extDir = context.getExternalFilesDir(null);
            if(null != extDir) {
                return extDir.getAbsolutePath();
            } else {
                File internalDir = context.getFilesDir();
                File fallbackLocal = new File(internalDir, "fallback-local");
                if (!fallbackLocal.exists() && !fallbackLocal.mkdir()) {
                    throw new IllegalStateException();
                }
                return fallbackLocal.getAbsolutePath();
            }
        } else {
            return Environment.getExternalStorageDirectory().getAbsolutePath();
        }
    }

    public ArrayList<Provider> getProviders() throws JSONException {
        return getProviders(false);
    }
    public ArrayList<Provider> getProviders(boolean silent) throws JSONException {

        int versionCode = BuildConfig.VERSION_CODE;
        File file = new File(context.getCacheDir(), "rclone.provider."+versionCode);
        JSONArray remotesJSON = readProviderCache(file);

        if (remotesJSON == null) {
            String[] command = createCommand("config", "providers");
            DiagnosticCapture diagnostics = new DiagnosticCapture();

            try {
                CapturedText result = runBoundedTextCommand(command, getRcloneEnv(), "config-providers",
                        MAX_CONFIG_JSON_CHARS, METADATA_COMMAND_TIMEOUT_MILLIS, diagnostics);
                if (!result.outcome.isSuccess() || result.exceededLimit || result.outcome.isOutputTruncated()) {
                    logNativeDiagnostics(diagnostics);
                    if(!silent){
                        Toasty.error(context, context.getString(R.string.error_getting_remotes), Toast.LENGTH_SHORT, true).show();
                    }
                    return new ArrayList<>();
                }

                remotesJSON = new JSONArray(result.text);
                validateProviderMetadata(remotesJSON);
                writeProviderCache(file, remotesJSON);
            } catch (IOException | JSONException e) {
                FLog.e(TAG, "Unable to retrieve bounded rclone provider data");
                return new ArrayList<>();
            }
        }

        ArrayList<Provider> providerItems = new ArrayList<>();

        for (int i = 0; i < remotesJSON.length(); i++) {
            providerItems.add(Provider.Companion.newInstance(remotesJSON.getJSONObject(i)));
        }

        return providerItems;
    }

    @Nullable
    private static JSONArray readProviderCache(File file) {
        synchronized (PROVIDER_CACHE_LOCK) {
            AtomicFile atomicFile = new AtomicFile(file);
            try (InputStream input = atomicFile.openRead();
                 Reader reader = new InputStreamReader(input, StandardCharsets.UTF_8)) {
                JSONArray providers = new JSONArray(
                        BoundedTextReader.read(reader, MAX_CONFIG_JSON_CHARS));
                validateProviderMetadata(providers);
                return providers;
            } catch (FileNotFoundException noCache) {
                return null;
            } catch (IOException | JSONException invalidCache) {
                FLog.e(TAG, "Cached provider metadata is invalid; refreshing from rclone");
                return null;
            }
        }
    }

    private static void validateProviderMetadata(JSONArray providers) throws JSONException {
        for (int i = 0; i < providers.length(); i++) {
            JSONObject provider = providers.optJSONObject(i);
            if (provider == null ||
                    !ProviderMetadataPolicy.hasValidProviderName(provider.opt("Name"))) {
                throw new JSONException("Provider metadata contains an invalid provider name");
            }

            Object rawOptions = provider.opt("Options");
            if (rawOptions == null || rawOptions == JSONObject.NULL) continue;
            if (!(rawOptions instanceof JSONArray)) {
                throw new JSONException("Provider metadata contains invalid options");
            }
            JSONArray options = (JSONArray) rawOptions;
            for (int optionIndex = 0; optionIndex < options.length(); optionIndex++) {
                JSONObject option = options.optJSONObject(optionIndex);
                if (option == null ||
                        !ProviderMetadataPolicy.hasValidProviderName(option.opt("Name"))) {
                    throw new JSONException("Provider metadata contains an invalid option");
                }

                Object rawExamples = option.opt("Examples");
                if (rawExamples == null || rawExamples == JSONObject.NULL) continue;
                if (!(rawExamples instanceof JSONArray)) {
                    throw new JSONException("Provider metadata contains invalid examples");
                }
                JSONArray examples = (JSONArray) rawExamples;
                for (int exampleIndex = 0; exampleIndex < examples.length(); exampleIndex++) {
                    if (examples.optJSONObject(exampleIndex) == null) {
                        throw new JSONException("Provider metadata contains an invalid example");
                    }
                }
            }
        }
    }

    private static void writeProviderCache(File file, JSONArray providers) {
        String json = providers.toString();
        if (json.length() > MAX_CONFIG_JSON_CHARS) {
            FLog.e(TAG, "Provider metadata exceeds the cache size limit; skipping cache write");
            return;
        }

        synchronized (PROVIDER_CACHE_LOCK) {
            AtomicFile atomicFile = new AtomicFile(file);
            FileOutputStream output = null;
            try {
                output = atomicFile.startWrite();
                output.write(json.getBytes(StandardCharsets.UTF_8));
                atomicFile.finishWrite(output);
            } catch (IOException writeFailure) {
                if (output != null) atomicFile.failWrite(output);
                FLog.e(TAG, "Unable to persist provider metadata cache", writeFailure);
            }
        }
    }

    public Provider getProvider(String name) throws JSONException {
        for (Provider provider : getProviders()) {
            if(provider.getName().equals(name)){
                return provider;
            }
        }
        return null;
    }

}
