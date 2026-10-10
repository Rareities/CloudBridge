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
import java.net.InetAddress;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import ca.pkay.rcloneexplorer.Database.json.Exporter;
import ca.pkay.rcloneexplorer.Database.json.SharedPreferencesBackup;
import ca.pkay.rcloneexplorer.Items.FileItem;
import ca.pkay.rcloneexplorer.Items.FilterEntry;
import ca.pkay.rcloneexplorer.Items.RemoteItem;
import ca.pkay.rcloneexplorer.Items.SyncDirectionObject;
import ca.pkay.rcloneexplorer.Items.Task;
import ca.pkay.rcloneexplorer.bisync.BisyncSession;
import ca.pkay.rcloneexplorer.util.ConfigSecretStore;
import ca.pkay.rcloneexplorer.util.ConfigRestoreJournal;
import ca.pkay.rcloneexplorer.rclone.Provider;
import ca.pkay.rcloneexplorer.util.FLog;
import ca.pkay.rcloneexplorer.util.SyncLog;
import es.dmoral.toasty.Toasty;
import io.github.x0b.safdav.SafAccessProvider;
import io.github.x0b.safdav.SafDAVServer;
import io.github.x0b.safdav.file.SafConstants;

public class Rclone {

    private static final String TAG = "Rclone";
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
    // RC-38: cache of the parsed `rclone config dump` JSON. Validated against the rclone.conf
    // file's mtime/length on every read so per-instance caches self-invalidate when another
    // Rclone instance (e.g. a config dialog) mutates the config. Volatile for cross-thread visibility.
    private volatile JSONObject cachedRemotesConfig;
    private volatile long cachedConfMtime;
    private volatile long cachedConfLength;

    public Rclone(Context context) {
        this.context = context;
        try {
            ConfigRestoreJournal.recover(context);
        } catch (IOException e) {
            throw new IllegalStateException("Configuration restore needs recovery", e);
        }
        this.rclone = context.getApplicationInfo().nativeLibraryDir + "/librclone.so";
        this.rcloneConf = context.getFilesDir().getPath() + "/rclone.conf";
        log2File = new Log2File(context);
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
            command.add("--log-level=NOTICE");
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
            command.add("--log-level=NOTICE");
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

        // Allow the caller to overwrite any option for special cases
        for (String overwrite : overwriteOptions) {
            int equals = overwrite.indexOf('=');
            if (equals <= 0) throw new IllegalArgumentException("Invalid environment option");
            String prefix = overwrite.substring(0, equals + 1);
            Iterator<String> iter = environmentValues.iterator();
            while (iter.hasNext()) if (iter.next().startsWith(prefix)) iter.remove();
            environmentValues.add(overwrite);
        }
        return environmentValues.toArray(new String[0]);
    }

    private String getDnsServers() {
        String fallback = "";
        try {
            ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return fallback;
            Network network = cm.getActiveNetwork();
            if (network == null) return fallback;
            LinkProperties lp = cm.getLinkProperties(network);
            // Keep Android's encrypted Private DNS resolution instead of issuing raw DNS queries.
            if (lp != null && android.os.Build.VERSION.SDK_INT >= 28 && lp.isPrivateDnsActive()) return "";
            if (lp == null) return fallback;
            StringBuilder sb = new StringBuilder();
            for (InetAddress dns : lp.getDnsServers()) {
                if (sb.length() > 0) sb.append(",");
                String address = dns.getHostAddress();
                if (address == null) continue;
                if (address.indexOf(':') >= 0) address = "[" + address + "]";
                sb.append(address).append(":53");
            }
            return sb.length() > 0 ? sb.toString() : fallback;
        } catch (Exception e) {
            FLog.e(TAG, "Failed to detect DNS servers; using native resolver", e);
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
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getErrorStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                stringBuilder.append(line).append("\n");
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
        String logOutput = stringBuilder.toString();
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
            process = getRuntimeProcess(command);
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
        java.io.Closeable admission = ca.pkay.rcloneexplorer.util.OperationAdmission.processLease(
                new File(context.getFilesDir(), "bisync-admission.lock"));
        try {
            ArrayList<String> values = new ArrayList<>(Arrays.asList(env));
            boolean supplied = false;
            for (String value : values) if (value.startsWith("RCLONE_CONFIG_PASS=")) supplied = true;
            if (!supplied) {
                String password = ConfigSecretStore.password(context);
                if (password != null) values.add("RCLONE_CONFIG_PASS=" + password);
            }
            values.add("RCLONE_ASK_PASSWORD=false");
            return ca.pkay.rcloneexplorer.util.AdmittedProcess.start(command,
                    values.toArray(new String[0]), admission);
        } catch (IOException | RuntimeException e) {
            try { admission.close(); } catch (IOException close) { e.addSuppressed(close); }
            throw e;
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
            process = getRuntimeProcess(command);
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
            process = getRuntimeProcess(command);
            process.waitFor();
        } catch (IOException | InterruptedException e) {
            FLog.e(TAG, "deleteRemote: error starting rclone", e);
        }
    }

    public String obscure(String pass) {
        String[] command = createCommand("obscure", pass);

        Process process;
        try {
            process = getRuntimeProcess(command);
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

        // Native raw log files bypass redaction/retention. Drain diagnostics without persisting
        // arbitrary backend messages; application lifecycle diagnostics use private bounded logs.
        String[] env = getRcloneEnv();
        String[] command = params.toArray(new String[0]);
        try {
            Process serving = getRuntimeProcess(command, env);
            drainServingPipe(serving.getInputStream(), "rclone-serve-stdout");
            drainServingPipe(serving.getErrorStream(), "rclone-serve-stderr");
            log2File.log("Serving process started");
            return serving;
        } catch (IOException e) {
            FLog.e(TAG, "serve: error starting rclone", e);
            // todo: guard callers against null result
            return null;
        }
    }

    private void drainServingPipe(InputStream stream, String name) {
        Thread reader = new Thread(() -> {
            try (InputStream in = stream) {
                byte[] bytes = new byte[4096];
                while (in.read(bytes) != -1) { /* Raw backend diagnostics are not retained. */ }
            } catch (IOException ignored) { }
        }, name);
        reader.setDaemon(true);
        reader.start();
    }

    public Process serve(int protocol, int port, boolean allowRemoteAccess, String user, String password, RemoteItem remote, String servePath) {
        return serve(protocol, port, allowRemoteAccess, user, password, remote, servePath, null);
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

    public Process bisync(Task task, ArrayList<FilterEntry> filters) throws IOException {
        if (!filters.isEmpty()) throw new IOException("Bisync filter migration is not supported");
        preflightBisync(task);
        ArrayList<String> args = BisyncSession.arguments(context, task, false);
        Collections.addAll(args, "--stats=1s", "--stats-log-level", "NOTICE", "--use-json-log");
        return getRuntimeProcess(createCommandWithOptions(args), getRcloneEnv());
    }

    public Process bisyncPreview(Task task) throws IOException {
        preflightBisync(task);
        return getRuntimeProcess(createCommandWithOptions(BisyncSession.arguments(context, task, true)), getRcloneEnv());
    }

    private void preflightBisync(Task task) throws IOException {
        File local = new File(task.getLocalPath());
        if (!local.isDirectory() || !local.canRead()) throw new IOException("Local bisync root is unavailable");
        String[] roots = {local.getCanonicalPath(), task.getRemoteId() + ":" + task.getRemotePath()};
        for (String root : roots) {
            Captured result = capture(createCommand("lsjson", root, "--recursive", "--hash-type", "SHA-1",
                    "--no-modtime", "--no-mimetype", "--protondrive-original-file-size=true",
                    "--protondrive-enable-caching=false"), getRcloneEnv(), 16 * 1024 * 1024, 300000);
            if (result.exit != 0) throw new IOException("Bisync root listing failed; no changes were started");
            try {
                JSONArray files = new JSONArray(result.text);
                for (int i = 0; i < files.length(); i++) {
                    JSONObject file = files.getJSONObject(i);
                    if (!file.getBoolean("IsDir")) {
                        JSONObject hashes = file.optJSONObject("Hashes");
                        String hash = hashes == null ? "" : hashes.optString("SHA-1");
                        if (file.getLong("Size") < 0 || !hash.matches("(?i)[0-9a-f]{40}")) {
                            throw new IOException("Bisync requires verified plaintext size and SHA-1 for every file");
                        }
                    }
                }
            } catch (JSONException e) { throw new IOException("Bisync listing was incomplete or invalid", e); }
        }
    }

    private static final class Captured {
        final int exit; final String text;
        Captured(int exit, String text) { this.exit = exit; this.text = text; }
    }

    /** Bounded read-only helpers drain both pipes and cannot wait indefinitely. */
    private Captured capture(String[] command, String[] env, int limit, long timeout) throws IOException {
        return capture(command, env, limit, timeout, null);
    }

    private Captured capture(String[] command, String[] env, int limit, long timeout, byte[] stdin) throws IOException {
        Process process = getRuntimeProcess(command, env);
        Thread stderr = new Thread(() -> {
            try (InputStream in = process.getErrorStream()) {
                byte[] bytes = new byte[4096]; while (in.read(bytes) != -1) { /* no raw diagnostics */ }
            } catch (IOException ignored) { }
        }, "rclone-helper-stderr");
        stderr.setDaemon(true);
        stderr.start();
        java.util.concurrent.atomic.AtomicBoolean expired = new java.util.concurrent.atomic.AtomicBoolean();
        java.util.Timer timer = new java.util.Timer("rclone-helper-deadline", true);
        timer.schedule(new java.util.TimerTask() {
            @Override public void run() { expired.set(true); process.destroy(); }
        }, timeout);
        try (InputStream in = process.getInputStream(); java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream()) {
            try (OutputStream input = process.getOutputStream()) {
                if (stdin != null) input.write(stdin);
            }
            byte[] bytes = new byte[8192]; int n;
            while ((n = in.read(bytes)) != -1) {
                if (out.size() + n > limit) throw new IOException("Rclone helper output exceeds safe limit");
                out.write(bytes, 0, n);
            }
            int exit = process.waitFor();
            if (expired.get()) throw new IOException("Rclone helper timed out");
            return new Captured(exit, new String(out.toByteArray(), StandardCharsets.UTF_8));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt(); throw new IOException("Rclone helper interrupted", e);
        } finally {
            timer.cancel(); process.destroy();
            try { process.getErrorStream().close(); } catch (IOException ignored) { }
            try { process.getOutputStream().close(); } catch (IOException ignored) { }
            try { stderr.join(1000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
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

    /**
     * Batch-deletes plain files listed in {@code filesFrom} (one root-relative path per line) with
     * a single rclone process, so a multi-select delete occupies one worker instead of hundreds.
     */
    public Process deleteFilesFrom(RemoteItem remote, File filesFrom) {
        // Plain-local remotes root at the SAF-mapped prefix; wrapped remotes (crypt/alias/cache)
        // must NOT get that prefix — buildFullRemotePath applies the same exclusion as the
        // per-file delete paths, and an empty path yields exactly the remote root.
        String root = buildFullRemotePath(remote, "");
        String[] command = createCommandWithOptions("delete", root,
                "--files-from", filesFrom.getAbsolutePath(),
                "--transfers", getTransfers(),
                "--stats=1s", "--stats-log-level", "NOTICE", "--use-json-log",
                "--contimeout", "30s", "--timeout", "2m");

        String[] env = getRcloneEnv();
        try {
            return getRuntimeProcess(command, env);
        } catch (IOException e) {
            FLog.e(TAG, "deleteFilesFrom: error starting rclone", e);
            return null;
        }
    }

    /** Recursively deletes a single directory on the remote. */
    public Process purgeItem(RemoteItem remote, String path) {
        String[] command = createCommandWithOptions("purge", buildFullRemotePath(remote, path),
                "--stats=1s", "--stats-log-level", "NOTICE", "--use-json-log",
                "--contimeout", "30s", "--timeout", "2m");

        String[] env = getRcloneEnv();
        try {
            return getRuntimeProcess(command, env);
        } catch (IOException e) {
            FLog.e(TAG, "purgeItem: error starting rclone", e);
            return null;
        }
    }

    /** Deletes a single plain file on the remote (fallback for names unusable in --files-from). */
    public Process deleteItems(RemoteItem remote, String path) {
        String[] command = createCommandWithOptions("deletefile", buildFullRemotePath(remote, path),
                "--stats=1s", "--stats-log-level", "NOTICE", "--use-json-log",
                "--contimeout", "30s", "--timeout", "2m");

        String[] env = getRcloneEnv();
        try {
            return getRuntimeProcess(command, env);
        } catch (IOException e) {
            FLog.e(TAG, "deleteItems: error starting rclone", e);
            return null;
        }
    }

    private String buildFullRemotePath(RemoteItem remote, String path) {
        String localRemotePath;
        if (remote.isRemoteType(RemoteItem.LOCAL) && (!remote.isAlias() && !remote.isCrypt() && !remote.isCache())) {
            localRemotePath = getLocalRemotePathPrefix(remote, context) + "/";
        } else {
            localRemotePath = "";
        }
        return remote.getName() + ":" + localRemotePath + path;
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
        Process process = null;
        String[] env = getRcloneEnv();

        try {
            process = getRuntimeProcess(command, env);
            process.waitFor();
            if (process.exitValue() != 0) {
                logErrorOutput(process);
                return null;
            }

            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                return reader.readLine();
            }

        } catch (IOException | InterruptedException e) {
            FLog.e(TAG, "link: error running rclone", e);
            if (process != null) {
                logErrorOutput(process);
            }
        }
        return null;
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
        String[] env = getRcloneEnv();
        Process process;
        try {
            process = getRuntimeProcess(command, env);
            process.waitFor();
            if (process.exitValue() != 0) {
                return context.getString(R.string.hash_error);
            }

            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line = reader.readLine();
                if (line == null || line.trim().isEmpty()) {
                    return context.getString(R.string.hash_error);
                }
                String[] split = line.split("\\s+");
                if (split[0].trim().isEmpty()) {
                    return context.getString(R.string.hash_unsupported);
                } else {
                    return split[0];
                }
            }
        } catch (IOException e) {
            FLog.e(TAG, "calculateMD5: error running rclone", e);
            return context.getString(R.string.hash_error);
        } catch (InterruptedException e) {
            FLog.v(TAG, "calculateMD5: calculation stopped");
            return context.getString(R.string.hash_error);
        }
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
        String[] env = getRcloneEnv();
        Process process;
        try {
            process = getRuntimeProcess(command, env);
            process.waitFor();
            if (process.exitValue() != 0) {
                return context.getString(R.string.hash_error);
            }

            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line = reader.readLine();
                if (line == null || line.trim().isEmpty()) {
                    return context.getString(R.string.hash_error);
                }
                String[] split = line.split("\\s+");
                if (split[0].trim().isEmpty()) {
                    return context.getString(R.string.hash_unsupported);
                } else {
                    return split[0];
                }
            }
        } catch (IOException | InterruptedException e) {
            FLog.e(TAG, "calculateSHA1: error running rclone", e);
            return context.getString(R.string.hash_error);
        }
    }

    public String getRcloneVersion() {
        String[] command = createCommand("--version");
        ArrayList<String> result = new ArrayList<>();
        try {
            Process process = getRuntimeProcess(command);
            process.waitFor();
            if (process.exitValue() != 0) {
                logErrorOutput(process);
                return "-1";
            }

            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    result.add(line);
                }
            }
        } catch (IOException | InterruptedException e) {
            FLog.e(TAG, "getRcloneVersion: error running rclone", e);
            return "-1";
        }

        if (result.isEmpty()) {
            return "-1";
        }
        String[] version = result.get(0).split("\\s+");
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
            process = getRuntimeProcess(command);
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
            process = getRuntimeProcess(command);

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
            process = getRuntimeProcess(command);
            process.waitFor();
        } catch (IOException | InterruptedException e) {
            FLog.e(TAG, "Error running rclone %s", e, Arrays.toString(command));
            return false;
        }
        return process.exitValue() != 0;
    }

    /** Unlock encrypted configuration without ever writing its plaintext representation. */
    public Boolean decryptConfig(String password) {
        try {
            Captured result = capture(createCommand("--ask-password=false", "listremotes"),
                    getRcloneEnv("RCLONE_CONFIG_PASS=" + password), 1024 * 1024, 30000);
            if (result.exit != 0) return false;
            ConfigSecretStore.store(context, password);
            invalidateRemotesCache();
            return true;
        } catch (IOException e) {
            FLog.e(TAG, "Configuration unlock failed");
            return false;
        }
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
        InputStream raw = context.getContentResolver().openInputStream(uri);
        if (raw == null) throw new IOException("Cannot open import");
        boolean found = false;
        long total = 0;
        int entries = 0;
        try (ZipInputStream zip = new ZipInputStream(new BufferedInputStream(raw))) {
            ZipEntry entry;
            byte[] bytes = new byte[8192];
            while ((entry = zip.getNextEntry()) != null) {
                if (++entries > 100) throw new IOException("Too many archive entries");
                boolean selected = target.equals(entry.getName()) && !entry.isDirectory();
                if (selected && found) throw new IOException("Duplicate archive component");
                try (OutputStream out = selected ? new FileOutputStream(targetfile) : new java.io.ByteArrayOutputStream(0)) {
                    int n;
                    while ((n = zip.read(bytes)) != -1) {
                        total += n;
                        if (total > 16L * 1024 * 1024) throw new IOException("Import archive exceeds size limit");
                        // Unknown entries are bounded but never extracted or accumulated.
                        if (selected) out.write(bytes, 0, n);
                    }
                }
                if (selected) found = true;
                zip.closeEntry();
            }
        } catch (IOException e) { targetfile.delete(); throw e; }
        return found ? targetfile : null;
    }

    public String readDatabaseJson(Uri uri) throws Exception {
        return readTextfileFromZip(uri, "rcx.json-tmp", "rcx.json");
    }

    public String readSharedPrefs(Uri uri) throws Exception {
        return readTextfileFromZip(uri, "rcx.prefs-tmp", "rcx.prefs");
    }

    public String readTextfileFromZip(Uri uri, String tempfile, String targetfile) throws Exception {
        File temp = new File(context.getFilesDir(), tempfile);
        try {
            if (getFileFromZip(uri, targetfile, temp) == null) throw new IOException("Missing archive component");
            try (Reader in = new InputStreamReader(new FileInputStream(temp), StandardCharsets.UTF_8)) {
                char[] buffer = new char[4096];
                StringBuilder text = new StringBuilder();
                int n;
                while ((n = in.read(buffer)) != -1) text.append(buffer, 0, n);
                return text.toString();
            }
        } finally { temp.delete(); }
    }

    public void importConfigZipAtomically(Uri uri, Runnable commitMetadata) throws IOException {
        File staged = File.createTempFile("restore-", ".conf", context.getFilesDir());
        try {
            if (getFileFromZip(uri, "rclone.conf", staged) == null || !isValidConfig(staged.getAbsolutePath())) {
                throw new IOException("Missing or invalid rclone configuration");
            }
            ConfigRestoreJournal.install(context, staged, () -> {
                commitMetadata.run();
                try { ConfigSecretStore.clear(context); }
                catch (IOException e) { throw new IllegalStateException("Cannot reset imported configuration unlock", e); }
            });
            invalidateRemotesCache();
        } finally { staged.delete(); }
    }

    public boolean copyConfigFileFromZip(Uri uri) throws Exception {
        importConfigZipAtomically(uri, () -> {});
        return true;
    }

    /** Validate a bounded private candidate before journaled replacement. */
    public boolean copyConfigFile(Uri uri) throws IOException {
        File staged = File.createTempFile("config-import-", ".conf", context.getFilesDir());
        try {
            InputStream raw = context.getContentResolver().openInputStream(uri);
            if (raw == null) throw new IOException("Cannot open configuration import");
            try (InputStream in = raw; FileOutputStream out = new FileOutputStream(staged)) {
                byte[] bytes = new byte[8192]; int n; long total = 0;
                while ((n = in.read(bytes)) != -1) {
                    total += n;
                    if (total > 16L * 1024 * 1024) throw new IOException("Configuration import exceeds size limit");
                    out.write(bytes, 0, n);
                }
                out.getFD().sync();
            }
            if (!isValidConfig(staged.getAbsolutePath())) return false;
            ConfigRestoreJournal.install(context, staged, () -> {
                try { ConfigSecretStore.clear(context); }
                catch (IOException e) { throw new IllegalStateException("Cannot reset imported configuration unlock", e); }
            });
            invalidateRemotesCache();
            return true;
        } finally { staged.delete(); }
    }

    /** Checks the native encrypted format, independently of whether this device has its unlock key. */
    public boolean isConfigAtRestEncrypted() throws IOException {
        return nativeEncryptedHeader(new File(rcloneConf));
    }

    private boolean nativeEncryptedHeader(File file) throws IOException {
        try (BufferedReader in = new BufferedReader(new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))) {
            String line;
            for (int i = 0; i < 8 && (line = in.readLine()) != null; i++) {
                if (line.equals("RCLONE_ENCRYPT_V0:")) return true;
            }
            return false;
        }
    }

    /** Encrypt through rclone itself; passwords enter stdin/environment only, never argv or logs. */
    public void encryptConfigForBackup(String password) throws IOException {
        if (password == null || password.trim().isEmpty() || password.length() > 1024
                || password.indexOf('\n') >= 0 || password.indexOf('\r') >= 0 || password.indexOf('\0') >= 0) {
            throw new IOException("A nonempty single-line configuration password is required");
        }
        File staged = File.createTempFile("config-encryption-", ".conf", context.getFilesDir());
        try {
            ConfigRestoreJournal.install(context, staged, () -> {
                try { ConfigSecretStore.store(context, password); }
                catch (IOException e) { throw new IllegalStateException("Cannot protect configuration unlock", e); }
            }, () -> {
                if (nativeEncryptedHeader(new File(rcloneConf))) {
                    throw new IOException("Configuration is already encrypted; export it directly");
                }
                try (InputStream in = new FileInputStream(rcloneConf); FileOutputStream out = new FileOutputStream(staged)) {
                    byte[] bytes = new byte[8192]; int n; long total = 0;
                    while ((n = in.read(bytes)) != -1) {
                        total += n;
                        if (total > 16L * 1024 * 1024) throw new IOException("Configuration exceeds size limit");
                        out.write(bytes, 0, n);
                    }
                    out.getFD().sync();
                }
                byte[] input = (password + "\n" + password + "\n").getBytes(StandardCharsets.UTF_8);
                try {
                    Captured encrypted = capture(new String[]{rclone, "--config", staged.getAbsolutePath(),
                            "--ask-password=true", "config", "encryption", "set"}, getRcloneEnv(), 64 * 1024, 30000, input);
                    if (encrypted.exit != 0 || !nativeEncryptedHeader(staged)) {
                        throw new IOException("Native configuration encryption failed");
                    }
                    Captured verified = capture(new String[]{rclone, "--config", staged.getAbsolutePath(),
                            "--ask-password=false", "listremotes"}, getRcloneEnv("RCLONE_CONFIG_PASS=" + password),
                            1024 * 1024, 30000);
                    if (verified.exit != 0) throw new IOException("Encrypted configuration validation failed");
                } finally { Arrays.fill(input, (byte) 0); }
            });
            invalidateRemotesCache();
        } finally { staged.delete(); }
    }

    public boolean isValidConfig(String path) {
        File file = new File(path);
        if (!file.isFile() || file.length() > 16L * 1024 * 1024) return false;
        // Locked native configs are valid import candidates; unlock is verified separately.
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))) {
            String line;
            for (int i = 0; i < 8 && (line = reader.readLine()) != null; i++) {
                if (line.equals("RCLONE_ENCRYPT_V0:")) {
                    StringBuilder body = new StringBuilder();
                    while ((line = reader.readLine()) != null) {
                        for (int j = 0; j < line.length(); j++) {
                            char c = line.charAt(j);
                            if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z')
                                    || (c >= '0' && c <= '9') || c == '+' || c == '/' || c == '=') {
                                body.append(c);
                            } else if (!Character.isWhitespace(c)) return false;
                        }
                    }
                    try {
                        // Nonce plus authenticated ciphertext. Authentication still requires the user's password.
                        return android.util.Base64.decode(body.toString(), android.util.Base64.DEFAULT).length >= 40;
                    } catch (IllegalArgumentException malformed) { return false; }
                }
            }
            return capture(new String[]{rclone, "--ask-password=false", "--config", path, "listremotes"},
                    getRcloneEnv(), 1024 * 1024, 30000).exit == 0;
        } catch (IOException e) { return false; }
    }

    public void exportConfigFile(Uri uri) throws IOException {
        // Serialize into private staging first: a destination never receives plaintext config.
        File staged = File.createTempFile("credential-backup-", ".zip", context.getFilesDir());
        try (java.io.Closeable admission = BisyncSession.INSTANCE.previewLease(context)) {
            File config = new File(rcloneConf);
            if (!nativeEncryptedHeader(config)) throw new IOException("Encrypt the configuration before exporting a credential backup");
            try (ZipOutputStream out = new ZipOutputStream(new FileOutputStream(staged))) {
                out.putNextEntry(new ZipEntry("rcx.json"));
                out.write(Exporter.create(context).getBytes(StandardCharsets.UTF_8));
                out.closeEntry();
                out.putNextEntry(new ZipEntry("rcx.prefs"));
                out.write(SharedPreferencesBackup.export(context).getBytes(StandardCharsets.UTF_8));
                out.closeEntry();
                out.putNextEntry(new ZipEntry("rclone.conf"));
                try (InputStream in = new FileInputStream(config)) {
                    byte[] bytes = new byte[8192]; int n; long total = 0;
                    while ((n = in.read(bytes)) != -1) {
                        total += n;
                        if (total > 16L * 1024 * 1024) throw new IOException("Configuration exceeds backup size limit");
                        out.write(bytes, 0, n);
                    }
                }
                out.closeEntry();
            }
            OutputStream raw = context.getContentResolver().openOutputStream(uri, "wt");
            if (raw == null) throw new IOException("Cannot open backup destination");
            try (OutputStream out = raw; InputStream in = new FileInputStream(staged)) {
                byte[] bytes = new byte[8192]; int n;
                while ((n = in.read(bytes)) != -1) out.write(bytes, 0, n);
            }
        } catch (JSONException e) { throw new IOException("Cannot serialize backup", e); }
        catch (RuntimeException e) { throw new IOException("A running job prevents configuration backup", e); }
        finally { staged.delete(); }
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
