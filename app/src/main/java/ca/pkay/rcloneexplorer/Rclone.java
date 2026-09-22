package ca.pkay.rcloneexplorer;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.webkit.MimeTypeMap;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.preference.PreferenceManager;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.net.InetAddress;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import ca.pkay.rcloneexplorer.Database.json.Exporter;
import ca.pkay.rcloneexplorer.Database.json.Importer;
import ca.pkay.rcloneexplorer.Database.json.SharedPreferencesBackup;
import ca.pkay.rcloneexplorer.Items.FileItem;
import ca.pkay.rcloneexplorer.Items.FilterEntry;
import ca.pkay.rcloneexplorer.Items.RemoteItem;
import ca.pkay.rcloneexplorer.Items.SyncDirectionObject;
import ca.pkay.rcloneexplorer.rclone.Provider;
import ca.pkay.rcloneexplorer.util.ConfigSecretStore;
import ca.pkay.rcloneexplorer.util.FLog;
import ca.pkay.rcloneexplorer.util.LogRedactor;
import ca.pkay.rcloneexplorer.util.NativeExecutionHandle;
import ca.pkay.rcloneexplorer.util.SyncLog;
import es.dmoral.toasty.Toasty;
import io.github.x0b.safdav.SafAccessProvider;
import io.github.x0b.safdav.SafDAVServer;
import io.github.x0b.safdav.file.SafConstants;

public class Rclone {

    private static final String TAG = "Rclone";
    private static final long MAX_BACKUP_ENTRY_BYTES = 4L * 1024L * 1024L;
    private static final long METADATA_COMMAND_TIMEOUT_MILLIS = 5L * 60L * 1000L;
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
    private volatile String configPassword;
    // RC-38: cache of the parsed `rclone config dump` JSON. Validated against the rclone.conf
    // file's mtime/length on every read so per-instance caches self-invalidate when another
    // Rclone instance (e.g. a config dialog) mutates the config. Volatile for cross-thread visibility.
    private volatile JSONObject cachedRemotesConfig;
    private volatile long cachedConfMtime;
    private volatile long cachedConfLength;

    public Rclone(Context context) {
        this.context = context;
        this.rclone = context.getApplicationInfo().nativeLibraryDir + "/librclone.so";
        this.rcloneConf = context.getFilesDir().getPath() + "/rclone.conf";
        log2File = new Log2File(context);
        configSecretStore = new ConfigSecretStore(context);
        try {
            configPassword = configSecretStore.load();
        } catch (Exception e) {
            // Keep the encrypted config and require explicit recovery if the Keystore key
            // was invalidated. Never clear ciphertext as a generic recovery action.
            configPassword = null;
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

        String password = configPassword;
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

    public void logErrorOutput(Process process) {
        if (process == null) {
            return;
        }

        SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(context);
        boolean isLoggingEnable = sharedPreferences.getBoolean(context.getString(R.string.pref_key_logs), false);
        if (!isLoggingEnable) {
            return;
        }

        StringBuilder stringBuilder = new StringBuilder(100);
        boolean outputTruncated = false;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getErrorStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (stringBuilder.length() < LogRedactor.MAX_DIAGNOSTIC_CHARS) {
                    int remaining = LogRedactor.MAX_DIAGNOSTIC_CHARS - stringBuilder.length();
                    if (line.length() + 1 > remaining) {
                        stringBuilder.append(line, 0, Math.max(0, remaining - 1));
                        outputTruncated = true;
                    } else {
                        stringBuilder.append(line).append("\n");
                    }
                } else {
                    outputTruncated = true;
                }
            }
        } catch (InterruptedIOException iioe) {
            FLog.i(TAG, "logErrorOutput: process died while reading. Log may be incomplete.");
        } catch (IOException e) {
            if("Stream closed".equals(e.getMessage())) {
                FLog.d(TAG, "logErrorOutput: could not read stderr, process stream is already closed");
            } else {
                FLog.e(TAG, "logErrorOutput: ", e);
            }
            return;
        }
        if (outputTruncated) {
            stringBuilder.append("\n***diagnostic-output-truncated***");
        }
        String logOutput = LogRedactor.redact(stringBuilder.toString());
        log2File.log(logOutput);
        SyncLog.error(context, "Rclone operation", logOutput);
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
        String[] env = getRcloneEnv();
        JSONArray results;
        Process process = null;
        try {
            process = getRuntimeProcess(command, env);

            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line);
                }
            }

            process.waitFor();
            // For local/alias remotes, exit(6) is not a fatal error.
            if (process.exitValue() != 0 && (process.exitValue() != 6 || !remote.isRemoteType(RemoteItem.LOCAL, RemoteItem.ALIAS))) {
                logErrorOutput(process);
                return null;
            }

            String outputStr = output.toString();
            results = new JSONArray(outputStr);

        } catch (InterruptedException e) {
            logErrorOutput(process);
            FLog.d(TAG, "getDirectoryContent: Aborted refreshing folder");
            return null;
        } catch (IOException | JSONException e) {
            logErrorOutput(process);
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
                logErrorOutput(process);
                FLog.e(TAG, "getDirectoryContent: Could not decode JSON", e);
                return null;
            }
        }
        return fileItemList;
    }

    public List<RemoteItem> getRemotes() {
        // RC-38: avoid spawning an rclone process (and parsing its JSON) on every UI-thread call.
        // The expensive config-dump result is cached and only re-read after the config is mutated
        // via config()/deleteRemote() or an explicit invalidateRemotesCache(). Pin/favorite state
        // is re-applied from SharedPreferences on every call so it stays fresh.
        JSONObject remotesJSON = getCachedRemotesConfig();
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
        File confFile = new File(rcloneConf);
        long mtime = confFile.lastModified();
        long length = confFile.length();
        synchronized (this) {
            if (cachedRemotesConfig != null && cachedConfMtime == mtime && cachedConfLength == length) {
                return cachedRemotesConfig;
            }
        }
        String[] command = createCommand("config", "dump");
        StringBuilder output = new StringBuilder();
        Process process = null;
        try {
            process = getRuntimeProcess(command, getRcloneEnv());
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line);
                }
            }
            process.waitFor();
            if (process.exitValue() != 0) {
                Toasty.error(context, context.getString(R.string.error_getting_remotes), Toast.LENGTH_SHORT, true).show();
                logErrorOutput(process);
                return null;
            }
            JSONObject parsed = new JSONObject(output.toString());
            synchronized (this) {
                cachedRemotesConfig = parsed;
                cachedConfMtime = mtime;
                cachedConfLength = length;
            }
            return parsed;
        } catch (IOException | InterruptedException | JSONException e) {
            logErrorOutput(process);
            FLog.e(TAG, "getRemotes: error retrieving remotes", e);
            return null;
        }
    }

    /** Drop the cached config dump so the next getRemotes() re-reads it from rclone. */
    public void invalidateRemotesCache() {
        cachedRemotesConfig = null;
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
        return Runtime.getRuntime().exec(command, env);
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
    public Process configCreate(List<String> options) {
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
    public Process configCreateNoInteract(List<String> options) {
        options.add("--obscure");
        options.add("--non-interactive");
        options.add("--no-output");
        return config("create", options);
    }

    @Nullable
    public Process configUpdate(List<String> options) {
        return configCreate(options);
    }
    
    public Process config(String task, List<String> options) {
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
        String[] command = createCommand("config", "dump");
        StringBuilder output = new StringBuilder();
        Process process;
        JSONObject configs = new JSONObject();

        HashMap<String, String> options = new HashMap<>();

        try {
            process = getRuntimeProcess(command, getRcloneEnv());
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line);
                }
            }

            process.waitFor();
            if (process.exitValue() != 0) {
                Toasty.error(context, context.getString(R.string.error_getting_config), Toast.LENGTH_SHORT, true).show();
                logErrorOutput(process);
            }

            configs = new JSONObject(output.toString());
        } catch (IOException | InterruptedException | JSONException e) {
            FLog.e(TAG, "getRemotes: error retrieving remotes", e);
        }

        if (configs == null) {
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

    public Process configInteractive() throws IOException {
        String[] command = createCommand("config");
        String[] environment = getRcloneEnv();
        return getRuntimeProcess(command, environment);
    }

    public void deleteRemote(String remoteName) {
        invalidateRemotesCache();
        String[] command = createCommandWithOptions("config", "delete", remoteName);
        Process process;

        try {
            process = getRuntimeProcess(command, getRcloneEnv());
            process.waitFor();
        } catch (IOException | InterruptedException e) {
            FLog.e(TAG, "deleteRemote: error starting rclone", e);
        }
    }

    public String obscure(String pass) {
        String[] command = createCommand("obscure", pass);

        Process process;
        try {
            process = getRuntimeProcess(command, getRcloneEnv());
            process.waitFor();
            if (process.exitValue() != 0) {
                return null;
            }

            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                return reader.readLine();
            }
        } catch (IOException | InterruptedException e) {
            FLog.e(TAG, "obscure: error starting rclone", e);
            // TODO: guard callers against null result
            return null;
        }
    }

    public Process serve(int protocol, int port, boolean allowRemoteAccess, @Nullable String user,
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
        // rclone's parser accepts them in this position (cf. --log-file below).
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
            File serveLog = new File(context.getExternalFilesDir("logs"), "serve.log");
            params.add("--log-file");
            params.add(serveLog.getAbsolutePath());
        }

        String[] env = getRcloneEnv();
        String[] command = params.toArray(new String[0]);
        try {
            return getRuntimeProcess(command, env);
        } catch (IOException e) {
            FLog.e(TAG, "serve: error starting rclone", e);
            // todo: guard callers against null result
            return null;
        }
    }

    public Process serve(int protocol, int port, boolean allowRemoteAccess, String user, String password, RemoteItem remote, String servePath) {
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
        return NativeExecutionHandle.adopt(
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
    public Process sync(RemoteItem remoteItem, String localPath, String remotePath, int syncDirection) {
        return sync(remoteItem, localPath, remotePath, syncDirection, false, new ArrayList<>(0), false);
    }

    public Process sync(RemoteItem remoteItem, String localPath, String remotePath, int syncDirection, boolean useMD5Sum, ArrayList<FilterEntry> filters, boolean deleteExcluded) {
        return sync(remoteItem, localPath, remotePath, syncDirection, useMD5Sum, filters, deleteExcluded, null);
    }

    public Process sync(RemoteItem remoteItem, String localPath, String remotePath, int syncDirection, boolean useMD5Sum, ArrayList<FilterEntry> filters, boolean deleteExcluded, String transfersOverride) {
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
    public Process sync(RemoteItem remoteItem, String remotePath, RemoteItem remoteItem2, String remotePath2, int syncDirection, boolean useMD5Sum, ArrayList<FilterEntry> filters, boolean deleteExcluded, String transfersOverride) {
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
        return NativeExecutionHandle.adopt(
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
        return NativeExecutionHandle.adopt(
                sync(remoteItem, remotePath, remoteItem2, remotePath2, syncDirection, useMD5Sum,
                        filters, deleteExcluded, transfersOverride),
                "cloud-sync");
    }

    public Process downloadFile(RemoteItem remote, FileItem downloadItem, String downloadPath) {
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

    public Process uploadFile(RemoteItem remote, String uploadPath, String uploadFile) {
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
            return getRuntimeProcess(command, env);
        } catch (IOException e) {
            FLog.e(TAG, "uploadFile: error starting rclone", e);
            return null;
        }

    }

    /** WP05 compatibility adapter for the ephemeral transfer worker. */
    @Nullable
    public NativeExecutionHandle downloadFileOwned(RemoteItem remote, FileItem downloadItem, String downloadPath) {
        return NativeExecutionHandle.adopt(downloadFile(remote, downloadItem, downloadPath), "download");
    }

    /** WP05 compatibility adapter for the ephemeral transfer worker. */
    @Nullable
    public NativeExecutionHandle uploadFileOwned(RemoteItem remote, String uploadPath, String uploadFile) {
        return NativeExecutionHandle.adopt(uploadFile(remote, uploadPath, uploadFile), "upload");
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

    public Process deleteItems(RemoteItem remote, FileItem deleteItem) {
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
            process = getRuntimeProcess(command, env);
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
        String[] env = getRcloneEnv();
        try {
            Process process = getRuntimeProcess(command, env);
            process.waitFor();
            if (process.exitValue() != 0) {
                logErrorOutput(process);
                return false;
            }
        } catch (IOException | InterruptedException e) {
            FLog.e(TAG, "makeDirectory: error running rclone", e);
            return false;
        }
        return true;
    }

    public Process moveTo(RemoteItem remote, FileItem moveItem, String newLocation) {
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
        return NativeExecutionHandle.adopt(moveTo(remote, moveItem, newLocation), "move");
    }

    /** WP05 compatibility adapter for the ephemeral transfer worker. */
    @Nullable
    public NativeExecutionHandle deleteItemsOwned(RemoteItem remote, FileItem deleteItem) {
        return NativeExecutionHandle.adopt(deleteItems(remote, deleteItem), "delete");
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
        String[] env = getRcloneEnv();
        try {
            Process process = getRuntimeProcess(command, env);
            process.waitFor();
            if (process.exitValue() != 0) {
                logErrorOutput(process);
                return false;
            }
        } catch (IOException | InterruptedException e) {
            FLog.e(TAG, "moveTo: error running rclone", e);
            return false;
        }
        return true;
    }

    public InputStream downloadToPipe(String rclonePath) throws IOException {
        String[] command = createCommandWithOptions("cat", rclonePath);
        String[] env = getRcloneEnv();
        final Process process = getRuntimeProcess(command, env);
        new Thread() {
            @Override
            public void run() {
                try {
                    process.waitFor();
                    logErrorOutput(process);
                } catch (InterruptedException e) {
                    FLog.e(TAG, "downloadToPipe: error waiting for process", e);
                }
            }
        }.start();
        return process.getInputStream();
    }

    public OutputStream uploadFromPipe(String rclonePath) throws IOException {
        String[] command = createCommandWithOptions("rcat", rclonePath, "--streaming-upload-cutoff", "500K");
        String[] env = getRcloneEnv();
        final Process process = getRuntimeProcess(command, env);
        new Thread() {
            @Override
            public void run() {
                try {
                    process.waitFor();
                    logErrorOutput(process);
                } catch (InterruptedException e) {
                    FLog.e(TAG, "uploadFromPipe: error waiting for process", e);
                }
            }
        }.start();
        return process.getOutputStream();
    }

    public boolean emptyTrashCan(String remote) {
        String[] command = createCommandWithOptions("cleanup", remote + ":");
        Process process = null;
        String[] env = getRcloneEnv();
        try {
            process = getRuntimeProcess(command, env);
            process.waitFor();
        } catch (IOException | InterruptedException e) {
            FLog.e(TAG, "emptyTrashCan: error running rclone", e);
        }

        return process != null && process.exitValue() == 0;
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

    /** A small text-only command: drain both pipes before reporting its first stdout line. */
    @Nullable
    private String runFirstMetadataLine(String[] command, String[] env, String label) {
        try {
            AtomicReference<String> firstLine = new AtomicReference<>();
            NativeExecutionHandle handle = NativeExecutionHandle.launch(command, env, label);
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

    public Process reconnectRemote(RemoteItem remoteItem) {
        String[] command = createCommand("config", "update", remoteItem.getName());

        try {
            return getRuntimeProcess(command, getRcloneEnv());
        } catch (IOException e) {
            return null;
        }
    }

    public AboutResult aboutRemote(RemoteItem remoteItem) {
        String remoteName = remoteItem.getName() + ':';
        String[] command = createCommand("about", "--json", remoteName);
        StringBuilder output = new StringBuilder();
        AboutResult stats;
        Process process;
        JSONObject aboutJSON;

        try {
            process = getRuntimeProcess(command, getRcloneEnv());
            try(BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))){
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line);
                }
            }
            process.waitFor();
            if (0 != process.exitValue()) {
                FLog.e(TAG, "aboutRemote: rclone error, exit(%d)", process.exitValue());
                FLog.e(TAG, "aboutRemote: ", output);
                logErrorOutput(process);
                return new AboutResult();
            }

            aboutJSON = new JSONObject(output.toString());
        } catch (IOException | InterruptedException | JSONException e) {
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
        StringBuilder output = new StringBuilder();
        Process process;

        try {
            process = getRuntimeProcess(command, getRcloneEnv());
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line);
                }
            }

            process.waitFor();
            if (process.exitValue() != 0) {
                FLog.e(TAG, "configDump: rclone error, exit(%d)", process.exitValue());
                logErrorOutput(process);
                return null;
            }

            return output.toString();
        } catch (IOException | InterruptedException e) {
            FLog.e(TAG, "configDump: unexpected error", e);
            return null;
        }
    }

    public DirectoryProbeResult listDirectories(String remoteName, int maxDepth) {
        String[] command = createCommand("lsd", "--max-depth", String.valueOf(maxDepth), remoteName + ":");
        Process process;

        try {
            process = getRuntimeProcess(command, getRcloneEnv());

            // Capture stderr so callers can classify the error type
            StringBuilder stderrBuilder = new StringBuilder();
            try (BufferedReader errReader = new BufferedReader(new InputStreamReader(process.getErrorStream()))) {
                String line;
                while ((line = errReader.readLine()) != null) {
                    stderrBuilder.append(line).append("\n");
                }
            }

            process.waitFor();
            return new DirectoryProbeResult(process.exitValue(), stderrBuilder.toString());
        } catch (IOException | InterruptedException e) {
            FLog.e(TAG, "listDirectories: error for remote " + remoteName, e);
            return new DirectoryProbeResult(-1, e.getMessage() != null ? e.getMessage() : "process error");
        }
    }

    /**
     * Result of a directory probe (lsd) operation, including both the exit code
     * and any stderr output for error classification.
     */
    public static class DirectoryProbeResult {
        private final int exitCode;
        private final String stderr;

        public DirectoryProbeResult(int exitCode, String stderr) {
            this.exitCode = exitCode;
            this.stderr = stderr != null ? stderr : "";
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

        /**
         * Returns true if the error is a transient network issue (DNS failure,
         * connection refused, timeout) rather than an authentication problem.
         */
        public boolean isNetworkError() {
            if (exitCode == 0) return false;
            String lower = stderr.toLowerCase(Locale.ROOT);
            return lower.contains("dial tcp")
                || lower.contains("connection refused")
                || lower.contains("no such host")
                || lower.contains("i/o timeout")
                || lower.contains("network is unreachable")
                || lower.contains("tls handshake timeout")
                || lower.contains("dns")
                || lower.contains("lookup")
                || lower.contains("no address associated");
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

    public Boolean isConfigEncrypted() {
        if (!isConfigFileCreated()) {
            return false;
        }
        String[] command = createCommand( "--ask-password=false", "listremotes");
        Process process;
        try {
            process = getRuntimeProcess(command, getRcloneEnv());
            process.waitFor();
        } catch (IOException | InterruptedException e) {
            FLog.e(TAG, "Error running rclone %s", e, Arrays.toString(command));
            return false;
        }
        return process.exitValue() != 0;
    }

    public Boolean decryptConfig(String password) {
        String[] command = createCommand("--ask-password=false", "config", "show");
        Process process;

        try {
            process = getRuntimeProcess(command, getRcloneEnv("RCLONE_CONFIG_PASS=" + password));
        } catch (IOException e) {
            FLog.e(TAG, "decryptConfig: error running rclone", e);
            return false;
        }

        Thread stdoutDrain = drain(process.getInputStream());
        Thread stderrDrain = drain(process.getErrorStream());
        stdoutDrain.start();
        stderrDrain.start();
        try {
            process.waitFor();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroy();
            FLog.e(TAG, "decryptConfig: error waiting for rclone", e);
            return false;
        }
        try {
            stdoutDrain.join(1000);
            stderrDrain.join(1000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        if (process.exitValue() != 0) {
            return false;
        }

        try {
            configSecretStore.save(password);
            configPassword = password;
        } catch (Exception e) {
            configPassword = null;
            FLog.e(TAG, "decryptConfig: error persisting Keystore-wrapped password", e);
            return false;
        }
        return true;
    }

    private static Thread drain(final InputStream stream) {
        return new Thread(() -> {
            try (InputStream input = stream) {
                byte[] buffer = new byte[4096];
                while (input.read(buffer) != -1) {
                    // Drain without retaining config plaintext or secret-bearing stderr.
                }
            } catch (IOException ignored) {
                // The process may close the pipe while it exits.
            }
        }, "rclone-config-drain");
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

    public File getFileFromZip(Uri uri, String target, File targetfile) throws IOException {

        // The exact cause of the NPE is unknown, but the effect is the same
        // - the copy process has failed, therefore bubble an IOException
        // for handling at the appropriate layers.
        InputStream inputStream;
        try {
            inputStream = context.getContentResolver().openInputStream(uri);
        } catch(NullPointerException e) {
            throw new IOException(e);
        }
        if (inputStream == null) {
            throw new IOException("Unable to open backup");
        }

        try (ZipInputStream zipInputStream = new ZipInputStream(new BufferedInputStream(inputStream))) {
            ZipEntry zipEntry;
            byte[] buffer = new byte[4096];

            while ((zipEntry = zipInputStream.getNextEntry()) != null) {
                if (zipEntry.getName().equals(target)) {
                    long total = 0;
                    try (FileOutputStream fileOutputStream = new FileOutputStream(targetfile, false)) {
                        int count;
                        while ((count = zipInputStream.read(buffer)) != -1) {
                            total += count;
                            if (total > MAX_BACKUP_ENTRY_BYTES) {
                                targetfile.delete();
                                throw new IOException("Backup entry exceeds the maximum size");
                            }
                            fileOutputStream.write(buffer, 0, count);
                        }
                        fileOutputStream.flush();
                    }
                    zipInputStream.closeEntry();
                    return targetfile;
                }
                zipInputStream.closeEntry();
            }
        }
        return null;
    }

    public String readDatabaseJson(Uri uri) throws Exception {
        return readTextfileFromZip(uri, "rcx.json-tmp", "rcx.json");
    }

    public String readSharedPrefs(Uri uri) throws Exception {
        return readTextfileFromZip(uri, "rcx.prefs-tmp", "rcx.prefs");
    }

    public String readTextfileFromZip(Uri uri, String tempfile, String targetfile) throws Exception {
        File temp = new File(context.getFilesDir().getPath(), tempfile);
        try {
            File extracted = getFileFromZip(uri, targetfile, temp);
            if (extracted == null || !extracted.isFile()) {
                throw new IOException("Backup entry is missing: " + targetfile);
            }

            char[] buffer = new char[4096];
            StringBuilder json = new StringBuilder();
            try (InputStream inputStream = new FileInputStream(extracted);
                 Reader in = new InputStreamReader(inputStream, StandardCharsets.UTF_8)) {
                for (int numRead; (numRead = in.read(buffer, 0, buffer.length)) > 0; ) {
                    if (json.length() + numRead > Importer.MAX_IMPORT_CHARS) {
                        throw new IOException("Backup JSON exceeds the maximum size");
                    }
                    json.append(buffer, 0, numRead);
                }
            }
            return json.toString();
        } finally {
            if (temp.exists()) {
                temp.delete();
            }
        }
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
        File tempFile = new File(context.getFilesDir(), "rclone.conf-import-" + System.nanoTime());
        File extracted;
        try {
            extracted = getFileFromZip(uri, "rclone.conf", tempFile);
        } catch (Exception e) {
            if (tempFile.exists()) {
                tempFile.delete();
            }
            throw e;
        }
        if (extracted == null || !isValidConfig(extracted.getAbsolutePath())) {
            if (tempFile.exists()) {
                tempFile.delete();
            }
            return null;
        }
        return extracted;
    }

    /** Atomically replaces the app config within its private files directory. */
    public boolean commitStagedConfigFile(File stagedFile) throws IOException {
        if (stagedFile == null || !stagedFile.isFile()) {
            throw new IOException("Staged config is missing");
        }
        File configFile = new File(context.getFilesDir(), "rclone.conf");
        if (!stagedFile.getParentFile().equals(configFile.getParentFile())) {
            throw new IOException("Staged config is outside the app files directory");
        }
        if (!stagedFile.renameTo(configFile)) {
            throw new IOException("Unable to commit staged config");
        }
        // A replacement may belong to another account or use another config password.
        // Do not reuse the old passphrase against it; the next unlock is explicit.
        configPassword = null;
        configSecretStore.clear();
        invalidateRemotesCache();
        return true;
    }

    /** Snapshot the current config so a multi-part backup import can roll back safely. */
    @Nullable
    public File snapshotConfigFile() throws IOException {
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
        File configFile = new File(rcloneConf);
        if (snapshot == null) {
            if (configFile.exists() && !configFile.delete()) {
                throw new IOException("Unable to remove imported config during rollback");
            }
        } else {
            if (!snapshot.isFile() || !snapshot.getParentFile().equals(configFile.getParentFile())) {
                throw new IOException("Config snapshot is invalid");
            }
            if (configFile.exists() && !configFile.delete()) {
                throw new IOException("Unable to replace config during rollback");
            }
            if (!snapshot.renameTo(configFile)) {
                throw new IOException("Unable to restore config snapshot");
            }
        }
        invalidateRemotesCache();
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

        File tempFile = new File(context.getFilesDir(), "rclone.conf-import-" + System.nanoTime());
        try (InputStream input = inputStream;
             FileOutputStream output = new FileOutputStream(tempFile, false)) {
            byte[] buffer = new byte[4096];
            long total = 0;
            int offset;
            while ((offset = input.read(buffer)) != -1) {
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
        try {
            Process process = getRuntimeProcess(command, environment);
            process.waitFor();
            int exitCode = process.exitValue();
            logErrorOutput(process);
            return exitCode == 0;
        } catch (IOException | InterruptedException e) {
            return false;
        }
    }

    public void exportConfigFile(Uri uri) throws IOException {
        File configFile = new File(rcloneConf);
        Uri config = Uri.fromFile(configFile);
        InputStream inputStream = context.getContentResolver().openInputStream(config);
        OutputStream outputStream = context.getContentResolver().openOutputStream(uri);

        if (inputStream == null || outputStream == null) {
            return;
        }
        char[] buffer = new char[4096];
        StringBuilder out = new StringBuilder();
        Reader in = new InputStreamReader(inputStream, StandardCharsets.UTF_8);
        for (int numRead; (numRead = in.read(buffer, 0, buffer.length)) > 0; ) {
            out.append(buffer, 0, numRead);
        }

        ZipOutputStream zos = new ZipOutputStream(outputStream);
        try {
            ZipEntry zipEntry = new ZipEntry("rcx.json");
            zos.putNextEntry(zipEntry);
            zos.write(Exporter.create(this.context).getBytes());
            zos.closeEntry();
            zipEntry = new ZipEntry("rcx.prefs");
            zos.putNextEntry(zipEntry);
            zos.write(SharedPreferencesBackup.export(context).getBytes());
            zos.closeEntry();
            zipEntry = new ZipEntry("rclone.conf");
            zos.putNextEntry(zipEntry);
            zos.write(out.toString().getBytes());
            zos.closeEntry();
        }
        catch (Exception e) {
            // unable to write zip
        }
        finally {
            zos.close();
            inputStream.close();
            outputStream.flush();
            outputStream.close();
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

        JSONArray remotesJSON;
        int versionCode = BuildConfig.VERSION_CODE;
        File file = new File(context.getCacheDir(), "rclone.provider."+versionCode);

        if(!file.exists()) {
            String[] command = createCommand("config", "providers");
            StringBuilder output = new StringBuilder();
            Process process;

            try {
                process = getRuntimeProcess(command, getRcloneEnv());
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        output.append(line);
                    }
                }

                process.waitFor();
                if (process.exitValue() != 0) {
                    if(!silent){
                        Toasty.error(context, context.getString(R.string.error_getting_remotes), Toast.LENGTH_SHORT, true).show();
                    }
                    logErrorOutput(process);
                    return new ArrayList<>();
                }

                remotesJSON = new JSONArray(output.toString());
            } catch (IOException | InterruptedException | JSONException e) {
                FLog.e(TAG, "getRemotes: error retrieving remotes", e);
                return new ArrayList<>();
            }

            try {
                FileWriter fw = new FileWriter(file.getAbsoluteFile());
                BufferedWriter bw = new BufferedWriter(fw);
                bw.write(remotesJSON.toString(4));
                bw.close();
            } catch (IOException e) {
                Toasty.error(context, context.getString(R.string.error_getting_remotes), Toast.LENGTH_SHORT, true).show();
                FLog.e(TAG, "Could not save providers to cache!", e);
                return new ArrayList<>();
            }
        } else {
            StringBuilder fileContent = new StringBuilder();
            try {
                FileInputStream inputstream = new FileInputStream(file);
                byte[] buffer = new byte[8128];
                int size;
                while ((size = inputstream.read(buffer)) != -1) {
                    fileContent.append(new String(buffer, 0, size));
                }
            } catch (IOException e) {
                Toasty.error(context, context.getString(R.string.error_getting_remotes), Toast.LENGTH_SHORT, true).show();
                FLog.e(TAG, "Could not read cached providers, but the file exists! Please clear your app cache.", e);
                return new ArrayList<>();
            }

            remotesJSON = new JSONArray(fileContent.toString());
        }

        ArrayList<Provider> providerItems = new ArrayList<>();

        for (int i = 0; i < remotesJSON.length(); i++) {
            providerItems.add(Provider.Companion.newInstance(remotesJSON.getJSONObject(i)));
        }

        return providerItems;
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
