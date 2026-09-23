package ca.pkay.rcloneexplorer;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.os.Handler;
import android.os.Looper;
import android.os.StrictMode;
import android.util.Base64;
import android.util.SparseArray;

import androidx.annotation.IntDef;
import androidx.preference.PreferenceManager;
import ca.pkay.rcloneexplorer.util.FLog;
import ca.pkay.rcloneexplorer.util.NativeExecutionHandle;
import ca.pkay.rcloneexplorer.util.ConfigSecretStore;
import ca.pkay.rcloneexplorer.util.EndpointConflictCoordinator;
import ca.pkay.rcloneexplorer.util.EndpointResource;
import ca.pkay.rcloneexplorer.Database.ResourceClaimLease;
import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.PropertyAccessor;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import ca.pkay.rcloneexplorer.util.Rfc3339Deserializer;
import okhttp3.Credentials;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

import java.io.IOException;
import java.io.File;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * The NG version of rclone command runner using rc/rcd. The backbone for
 * VirtualContentProvider.
 */
public class RcloneRcd {

    private static final String TAG = "RcloneRcd";
    private static final MediaType JSON = MediaType.parse("application/json");

    @Retention(RetentionPolicy.SOURCE)
    @IntDef({RUNNING, EXITED, ERROR})
    public @interface ProcessState {
    }

    private static final int RUNNING = 0;
    private static final int EXITED = -1;
    private static final int ERROR = 1;

    private static String rcUser = "admin";
    private static String rcPass = initPass();

    private final Context context;
    //private final Log2File log2File;
    private final ObjectMapper mapper;
    private final ConfigSecretStore configSecretStore;
    private final EndpointConflictCoordinator endpointConflictCoordinator;
    private volatile ConfigIdentitySnapshot configIdentityCache;
    private volatile String configPassword;

    private final String configPath;
    private final String rclone;
    private NativeExecutionHandle rcd;
    private boolean stopped = false;
    private boolean unconfirmedStop = false;
    private Object mainThreadLock = new Object();

    final BlockingQueue<Integer> pendingJobs;
    final SparseArray<JobStatusHandler> jobsHandlers;
    final SparseArray<JobStatusResponse> lastStatus;
    private final ScheduledExecutorService jobMonitorService;
    private final ExecutorService jobStatusExecutor;
    final JobsUpdateHandler jobsUpdateHandler;

    private ScheduledFuture<?> jobsUpdateFuture;
    private int port;
    private Handler mainHandler;

    private static String initPass() {
        SecureRandom random = new SecureRandom();
        byte[] values = new byte[16];
        random.nextBytes(values);
        return Base64.encodeToString(values, Base64.NO_WRAP | Base64.URL_SAFE);
    }

    public RcloneRcd(Context context, JobsUpdateHandler handler) {
        this.context = context;
        this.jobsUpdateHandler = handler;
        configPath = context.getFilesDir().getPath() + "/rclone.conf";
        rclone = context.getApplicationInfo().nativeLibraryDir + "/librclone.so";
        endpointConflictCoordinator = new EndpointConflictCoordinator(context);
        configSecretStore = new ConfigSecretStore(context);
        try {
            configPassword = configSecretStore.load();
        } catch (Exception e) {
            configPassword = null;
            FLog.w(TAG, "Unable to unlock stored rclone config password for rcd");
        }
        mapper = new ObjectMapper();
        mapper.configure(SerializationFeature.FAIL_ON_EMPTY_BEANS, false);
        mapper.setVisibility(PropertyAccessor.FIELD, JsonAutoDetect.Visibility.NON_PRIVATE);
        pendingJobs = new LinkedBlockingQueue<>();
        jobsHandlers = new SparseArray<>();
        lastStatus = new SparseArray<>();
        // Used to fan out per-job job/status HTTP calls so that N concurrent jobs don't get
        // polled strictly sequentially on the single-thread monitor (each call is a localhost
        // round-trip). See the transmission-speed audit (item 12).
        jobStatusExecutor = Executors.newFixedThreadPool(4, r -> {
            Thread t = new Thread(r, "rcd-job-status");
            t.setDaemon(true);
            return t;
        });
        jobMonitorService = Executors.newSingleThreadScheduledExecutor();
    }

    /**
     * Starts the rclone daemon subprocess and a job monitor thread
     */
    public void startRcd() {
        try {
            FLog.d(TAG, "startRcd: starting rclone process");
            port = nextAvailablePort();
            String addr = "localhost:" + port;
            String tmpDir = context.getCacheDir().getAbsolutePath();
            String logFile = context.getExternalFilesDir("logs").getAbsolutePath() + "/rcd.log";
            SharedPreferences pref = PreferenceManager.getDefaultSharedPreferences(context);
            String transfers = pref.getString(context.getString(R.string.pref_key_transfers), "4");
            ArrayList<String> parameters = new ArrayList<>(Arrays.asList(
                    rclone,
                    "--config", configPath,
                    "--rc-addr", addr,
                    "--rc-user", rcUser,
                    "--rc-pass", rcPass,
                    "--rc-serve",
                    // Transfer throughput tuning for VCP/rcd-driven operations.
                    "--transfers", transfers,
                    "--buffer-size", "16M",
                    "--multi-thread-streams", "4"));
            if (pref.getBoolean(context.getString(R.string.pref_key_logs), false)) {
                parameters.addAll(Arrays.asList(
                        "--log-file", logFile,
                        "--dump", "headers",
                        "-vvv"));
            }
            parameters.add("rcd");
            rcd = NativeExecutionHandle.launch(parameters.toArray(new String[0]), getEnv(), "rcd");
            rcd.startDrainers();
        } catch (IOException e) {
            FLog.e(TAG, "startRcd: error", e);
            throw new RuntimeException(e);
        }
        // PendingRcloneJobs blocks thread until first job arrives
        jobsUpdateFuture = jobMonitorService.scheduleWithFixedDelay(new PendingRcloneJobs(), 0, 1, TimeUnit.SECONDS);
    }

    public String[] getEnv() {
        ArrayList<String> environmentValues = new ArrayList<>();
        SharedPreferences pref = PreferenceManager.getDefaultSharedPreferences(context);

        boolean proxyEnabled = pref.getBoolean(context.getString(R.string.pref_key_use_proxy), false);
        if(proxyEnabled) {
            String noProxy = pref.getString(context.getString(R.string.pref_key_no_proxy_hosts), "localhost");
            String protocol = pref.getString(context.getString(R.string.pref_key_proxy_protocol), "http");
            String host = pref.getString(context.getString(R.string.pref_key_proxy_host), "localhost");
            String user = pref.getString(context.getString(R.string.pref_key_proxy_username), "user");
            String pass = pref.getString(context.getString(R.string.pref_key_proxy_password), "pass");
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

        if (configPassword != null && !configPassword.isEmpty()) {
            environmentValues.add("RCLONE_CONFIG_PASS=" + configPassword);
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

    /**
     * Warning: do not hand out to clients, this contains rc auth data!
     * @return
     */
    public String getServeBase() {
        return new StringBuilder()
                .append("http://")
                .append(rcUser)
                .append(':')
                .append(rcPass)
                .append("@127.0.0.1:")
                .append(port)
                .toString();
    }

    private static int nextAvailablePort() {
        try (ServerSocket serverSocket = new ServerSocket(0)) {
            serverSocket.setReuseAddress(true);
            return serverSocket.getLocalPort();
        } catch (IOException e) {
            FLog.e(TAG, "nextAvailablePort: could not get port", e);
        }
        throw new IllegalStateException("No port available");
    }

    /**
     * Stop the rcd server
     */
    public boolean stopRcd() {
        boolean confirmed = true;
        if (null != rcd) {
            FLog.d(TAG, "Stopping Rclone");
            confirmed = rcd.cancelAndAwait(null, null).isConfirmed() || rcd.hasConfirmedReap();
            unconfirmedStop = !confirmed;
        }
        if (null != jobsUpdateFuture) {
            jobsUpdateFuture.cancel(true);
        }
        jobMonitorService.shutdownNow();
        jobStatusExecutor.shutdownNow();
        stopped = true;
        return confirmed;
    }

    public boolean hasUnconfirmedStop() {
        return unconfirmedStop;
    }

    /**
     * Check if the server is still alive
     * @return
     */
    public boolean isAlive() {
        return !(null == rcd || stopped || !(getProcessState() == RUNNING));
    }

    public boolean hasCrashed() {
        return rcd != null && getProcessState() == ERROR;
    }

    /**
     * Retrieve the process state
     * @param process
     * @return 0: running, -1 exited normally, 1 exited with error
     */
    private @ProcessState int getProcessState() {
        if (rcd == null) {
            return EXITED;
        }
        if (rcd.isRunning()) {
            return RUNNING;
        }
        NativeExecutionHandle.Outcome outcome = rcd.getOutcome();
        return outcome != null && outcome.isSuccess() ? EXITED : ERROR;
    }

    /**
     * Convert a rcloneExplorer remote name into fs parameter format
     * @param remoteName
     * @return
     */
    private String remoteNameAsFs(String remoteName) {
        remoteName += ':';
        // TODO: figure out if this is required or vestigal
        if (':' != remoteName.charAt(remoteName.length() - 1)) {
            remoteName += ':';
        }
        return remoteName;
    }

    /**
     * Convert a rcloneExplorer remote name into "remote" path parameter format
     * @param remoteName
     * @param path
     * @return
     */
    private String pathAsPath(String remoteName, String path) {
        if (path.equals("//" + remoteName)) {
            path = path.substring(remoteName.length() + 2);
        }
        return path;
    }

    /**
     * Perform an RC call using a defined response type
     * @param method method name for rclone
     * @param params a method specific param definition
     * @param responseType reponse type used for deserialization
     * @param <T> call return value type (responseType)
     * @return call return value
     */
    private <T extends RcOpResponse> T performRcCall(String method, RcOpParam params, Class<T> responseType) {
        if ("main".equals(Thread.currentThread().getThreadGroup().getName())) {
            StrictMode.ThreadPolicy previous = StrictMode.getThreadPolicy();
            synchronized (mainThreadLock) {
                StrictMode.ThreadPolicy policy = new StrictMode.ThreadPolicy.Builder().permitNetwork().build();
                StrictMode.setThreadPolicy(policy);
                T result = performRcCall(method, params, null, responseType);
                StrictMode.setThreadPolicy(previous);
                return result;
            }
        } else {
            return performRcCall(method, params, null, responseType);
        }
    }

    /**
     * Perform an RC call using a Jackson typed reference (e.g. for Generics)
     * @param method method name for rclone
     * @param params a method specific param definition
     * @param typeReference Jackson generic type reference
     * @param <T> call return value type (responseType)
     * @return call return value
     */
    private <T> T performRcCall(String method, RcOpParam params, TypeReference<T> typeReference) {
        if ("main".equals(Thread.currentThread().getThreadGroup().getName())) {
            StrictMode.ThreadPolicy previous = StrictMode.getThreadPolicy();
            // allow network for vcp calls if the caller was dumb enough to use
            // a main thread.
            synchronized (mainThreadLock) {
                if (!method.equals("config/dump")) {
                    FLog.w(TAG, "Main thread used for rcd call, method=%s", new RuntimeException(), method);
                }
                StrictMode.ThreadPolicy policy = new StrictMode.ThreadPolicy.Builder().permitNetwork().build();
                StrictMode.setThreadPolicy(policy);
                T result = performRcCall(method, params, typeReference, null);
                StrictMode.setThreadPolicy(previous);
                return result;
            }
        } else {
            return performRcCall(method, params, typeReference, null);
        }
    }

    private OkHttpClient okHttpClient;

    private OkHttpClient prepareClient() {
        if (null == okHttpClient) {
            OkHttpClient.Builder builder = new OkHttpClient.Builder()
                    .authenticator((route, response) -> {
                        String credential = Credentials.basic(rcUser, rcPass);
                        return response.request().newBuilder()
                                .header("Authorization", credential).build();
                    });

            /*if (DEBUG) {
                okhttp3.logging.HttpLoggingInterceptor logging = new okhttp3.logging.HttpLoggingInterceptor();
                logging.level(okhttp3.logging.HttpLoggingInterceptor.Level.HEADERS);
                builder.addInterceptor(logging);
            }*/

            okHttpClient = builder.build();
        }
        return okHttpClient;
    }

    // throws RcdOpException when the server returns an error message
    // do _not_ call directly
    private <T> T performRcCall(String method, RcOpParam params, TypeReference<T> typeReference, Class<T> responseType) {
        OkHttpClient client = prepareClient();
        HttpUrl url = new HttpUrl.Builder()
                .scheme("http")
                .host("localhost")
                .port(port)
                .addPathSegments(method)
                .build();
        byte[] callParams;
        try {
            callParams = mapper.writeValueAsBytes(params);
        } catch (JsonProcessingException e) {
            ErrorResponse response = new ErrorResponse();
            response.operation = method;
            response.error = e.getMessage();
            throw new RcdOpException(response);
        }
        RequestBody body = RequestBody.create(callParams, JSON);
        Request request = new Request.Builder().url(url).post(body).build();

        try (Response response = client.newCall(request).execute()) {
            try {
                if (isErrorCode(response.code())) {
                    ErrorResponse error = mapper.readValue(response.body().byteStream(), ErrorResponse.class);
                    // The transport status is authoritative when deciding whether a mutation
                    // request was rejected or may have started without a job handle.
                    error.status = response.code();
                    throw new RcdOpException(error);
                } else {
                    if (typeReference != null) {
                        return mapper.readValue(response.body().byteStream(), typeReference);
                    } else {
                        return mapper.readValue(response.body().byteStream(), responseType);
                    }
                }
            } catch (JsonProcessingException e) {
                FLog.e(TAG, "performRcCall: ", e);
                throw new RuntimeException(e);
            }
        } catch (IOException e) {
            throw new RcdIOException(e);
        }
    }

    private boolean isErrorCode(int code) {
        return 400 <= code;
    }

    ///
    /// Job Handling
    ///
    private static class RunJobStatusHandler implements Runnable {
        private final JobStatusHandler handler;
        private final JobStatusResponse response;

        public RunJobStatusHandler(JobStatusHandler handler, JobStatusResponse response) {
            this.handler = handler;
            this.response = response;
        }

        @Override
        public void run() {
            handler.handleJobStatus(response);
        }
    }

    final class PendingRcloneJobs implements Runnable {

        @Override
        public void run() {
            // block thread until job
            FLog.v(TAG, "pendingRcloneJobs: waiting for new job");
            try {
                Integer jobId = pendingJobs.take();
                pendingJobs.add(jobId);
            } catch (InterruptedException e) {
                // The containing runnable is scheduled as a periodic task to
                // monitor the status of pending rclone jobs. If the rcd
                // instance is shutdown while this job is in its blocking
                // phase (.take()), the job will be stopped by this exception.
                FLog.d(TAG, "pendingRcloneJobs: interrupted, exiting");
                return;
            }
            FLog.v(TAG, "pendingRcloneJobs: checking status");
            // Snapshot current job ids, then fetch each job's status concurrently. The HTTP
            // fetches run in parallel on jobStatusExecutor. lastStatus and jobsHandlers are only
            // mutated inside synchronized blocks; the per-job result (jobId to re-queue, or -1
            // for a terminal job) is returned via the Future and collected back here on the
            // monitor thread, so `pending` is never touched off-thread.
            List<Integer> currentJobIds = new ArrayList<>();
            while (null != pendingJobs.peek()) {
                currentJobIds.add(pendingJobs.remove());
            }
            List<Future<Integer>> futures = new ArrayList<>(currentJobIds.size());
            for (Integer jobId : currentJobIds) {
                futures.add(jobStatusExecutor.submit((Callable<Integer>) () -> {
                    try {
                        JobStatusResponse response = getJobStatus(jobId);
                        synchronized (lastStatus) {
                            lastStatus.put(jobId, response);
                        }
                        if (response.finished) {
                            JobStatusHandler handler;
                            synchronized (jobsHandlers) {
                                handler = jobsHandlers.get(jobId);
                            }
                            if (null != handler) {
                                FLog.v(TAG, "job finished: " + jobId);
                                mainThread(handler, response);
                            }
                            return -1; // terminal: do not re-queue
                        }
                        FLog.v(TAG, "job running: " + jobId);
                        return jobId; // still running: re-queue
                    } catch (RcdOpException e) {
                        FLog.e(TAG, "job error: ", e);
                        if ("job not found".equals(e.getError())) {
                            return -1; // drop unknown job
                        }
                        return jobId; // transient error: retry next tick
                    }
                }));
            }
            List<Integer> pending = new ArrayList<>();
            for (Future<Integer> f : futures) {
                try {
                    int requeue = f.get();
                    if (requeue != -1) {
                        pending.add(requeue);
                    }
                } catch (Exception e) {
                    FLog.w(TAG, "job status future failed: %s", e.toString());
                }
            }
            pendingJobs.addAll(pending);
            if (null != jobsUpdateHandler) {
                SparseArray<JobStatusResponse> snapshot;
                synchronized (lastStatus) {
                    snapshot = lastStatus.clone();
                }
                mainThread(() -> jobsUpdateHandler.onRcdJobsUpdate(snapshot));
            }
        }
    }

    void mainThread(final JobStatusHandler handler, final JobStatusResponse response) {
        mainThread(new RunJobStatusHandler(handler, response));
    }

    void mainThread(Runnable runnable) {
        if(null == mainHandler) {
            mainHandler = new Handler(Looper.getMainLooper());
        }
        mainHandler.post(runnable);
    }

    public boolean hasPendingJobs() {
        return pendingJobs.size() > 0;
    }

    /**
     * Registers a completion handler for an rcd job. Must be synchronized because the job-status
     * poller threads read this map concurrently (see {@link #jobStatusExecutor}).
     */
    private void registerJobHandler(int jobId, JobStatusHandler handler) {
        synchronized (jobsHandlers) {
            jobsHandlers.append(jobId, handler);
        }
    }

    private RcdOpException resourceConflict() {
        ErrorResponse response = new ErrorResponse();
        response.error = "An overlapping app operation is active or requires recovery";
        response.operation = "resource-claim";
        response.status = 409;
        return new RcdOpException(response);
    }

    private ResourceClaimLease acquireGlobalClaim(String operation) {
        try {
            return endpointConflictCoordinator.acquireGlobal(operation);
        } catch (IOException conflict) {
            throw resourceConflict();
        }
    }

    private ConfigIdentitySnapshot configSnapshotForClaims() {
        File configFile = new File(configPath);
        long mtime = configFile.lastModified();
        long length = configFile.length();
        String digest = EndpointConflictCoordinator.fingerprintFile(configFile);
        if (digest == null) return null;
        ConfigIdentitySnapshot cached = configIdentityCache;
        if (cached != null && cached.mtime == mtime && cached.length == length
                && digest.equals(cached.digest)) {
            return cached;
        }

        ResourceClaimLease claim;
        try {
            claim = endpointConflictCoordinator.acquireGlobal("rcd-config-identity");
        } catch (IOException conflict) {
            return null;
        }
        try {
            Map<String, ConfigDumpRemote> remotes = performRcCall(
                    "config/dump", new NoParamRcOpParam(),
                    new TypeReference<Map<String, ConfigDumpRemote>>() { });
            String currentDigest = EndpointConflictCoordinator.fingerprintFile(configFile);
            if (remotes == null || currentDigest == null || !digest.equals(currentDigest)
                    || configFile.lastModified() != mtime || configFile.length() != length) {
                return null;
            }
            ConfigIdentitySnapshot snapshot = new ConfigIdentitySnapshot(remotes, mtime, length, digest);
            configIdentityCache = snapshot;
            return snapshot;
        } catch (RuntimeException failedConfigRead) {
            return null;
        } finally {
            claim.close();
        }
    }

    private static final class ConfigIdentitySnapshot {
        final Map<String, ConfigDumpRemote> remotes;
        final long mtime;
        final long length;
        final String digest;

        ConfigIdentitySnapshot(Map<String, ConfigDumpRemote> remotes, long mtime, long length, String digest) {
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

    private EndpointResource resourceForRemote(
            Map<String, ConfigDumpRemote> remotes, String remoteName, String path) {
        if (remotes == null) return EndpointResource.global();
        ConfigDumpRemote config = remotes.get(remoteName);
        if (config == null || config.type == null || config.type.trim().isEmpty()) {
            return EndpointResource.global();
        }
        boolean wrapped = config.remote != null && !config.remote.trim().isEmpty()
                || config.type.equals("alias") || config.type.equals("crypt")
                || config.type.equals("cache") || config.type.equals("union")
                || config.type.equals("chunker") || config.type.equals("combine")
                || config.type.equals("filter") || config.type.equals("hasher");
        return endpointConflictCoordinator.resourceForRemote(
                config.type, path, wrapped, config.root);
    }

    private ResourceClaimLease acquireRemoteClaim(String operation, String remoteName, String path) {
        ConfigIdentitySnapshot snapshot = configSnapshotForClaims();
        Map<String, ConfigDumpRemote> remotes = snapshot == null ? null : snapshot.remotes;
        String normalizedPath = path != null && path.equals("//" + remoteName) ? "" : path;
        EndpointResource resource = resourceForRemote(remotes, remoteName, normalizedPath);
        try {
            ResourceClaimLease claim = endpointConflictCoordinator.acquireResources(
                    operation, java.util.Collections.singletonList(resource));
            if (snapshot != null && !snapshot.stillMatches(new File(configPath))) {
                claim.close();
                throw resourceConflict();
            }
            return claim;
        } catch (IOException conflict) {
            throw resourceConflict();
        }
    }

    private ResourceClaimLease acquireRemotePairClaim(
            String operation, String sourceRemote, String sourcePath,
            String destinationRemote, String destinationPath) {
        ConfigIdentitySnapshot snapshot = configSnapshotForClaims();
        Map<String, ConfigDumpRemote> remotes = snapshot == null ? null : snapshot.remotes;
        List<EndpointResource> resources = new ArrayList<>(2);
        String normalizedSource = sourcePath != null && sourcePath.equals("//" + sourceRemote) ? "" : sourcePath;
        String normalizedDestination = destinationPath != null && destinationPath.equals("//" + destinationRemote) ? "" : destinationPath;
        resources.add(resourceForRemote(remotes, sourceRemote, normalizedSource));
        resources.add(resourceForRemote(remotes, destinationRemote, normalizedDestination));
        try {
            ResourceClaimLease claim = endpointConflictCoordinator.acquireResources(operation, resources);
            if (snapshot != null && !snapshot.stillMatches(new File(configPath))) {
                claim.close();
                throw resourceConflict();
            }
            return claim;
        } catch (IOException conflict) {
            throw resourceConflict();
        }
    }

    private static final class FsEndpoint {
        final String remoteName;
        final String path;
        FsEndpoint(String remoteName, String path) {
            this.remoteName = remoteName;
            this.path = path;
        }
    }

    private FsEndpoint parseFs(String fs) {
        if (fs == null) return null;
        int colon = fs.indexOf(':');
        if (colon <= 0) return null;
        return new FsEndpoint(fs.substring(0, colon), fs.substring(colon + 1));
    }

    private ResourceClaimLease acquireFsClaim(String operation, String fs) {
        FsEndpoint endpoint = parseFs(fs);
        return endpoint == null
                ? acquireGlobalClaim(operation)
                : acquireRemoteClaim(operation, endpoint.remoteName, endpoint.path);
    }

    private ResourceClaimLease acquireFsPairClaim(String operation, String sourceFs, String destinationFs) {
        FsEndpoint source = parseFs(sourceFs);
        FsEndpoint destination = parseFs(destinationFs);
        return source == null || destination == null
                ? acquireGlobalClaim(operation)
                : acquireRemotePairClaim(operation, source.remoteName, source.path,
                        destination.remoteName, destination.path);
    }

    private JobStatusHandler releaseClaimOnCompletion(ResourceClaimLease claim, JobStatusHandler handler) {
        return response -> {
            if (response != null && response.finished) {
                claim.close();
                synchronized (jobsHandlers) {
                    jobsHandlers.remove(response.id);
                }
            }
            if (handler != null) handler.handleJobStatus(response);
        };
    }

    private void registerClaimedJob(int jobId, JobStatusHandler handler, ResourceClaimLease claim) {
        registerJobHandler(jobId, releaseClaimOnCompletion(claim, handler));
        pendingJobs.add(jobId);
    }

    private <T> T performClaimedCall(ResourceClaimLease claim, ClaimedRcCall<T> operation) {
        try {
            T result = operation.execute();
            claim.close();
            return result;
        } catch (RuntimeException failure) {
            if (!RcdClaimFailurePolicy.mayHaveStarted(failure)) claim.close();
            throw failure;
        }
    }

    /** Starts an async job; uncertain responses leave the durable claim for verified recovery. */
    private void startClaimedJob(ResourceClaimLease claim, Runnable operation) {
        try {
            operation.run();
        } catch (RuntimeException failure) {
            if (!RcdClaimFailurePolicy.mayHaveStarted(failure)) claim.close();
            throw failure;
        }
    }

    private interface ClaimedRcCall<T> {
        T execute();
    }

    public interface JobsUpdateHandler {
        void onRcdJobsUpdate(SparseArray<JobStatusResponse> status);
    }

    //
    // Rclone rc API
    //

    public void cacheExpire(String directoryPath, boolean deleteData) {
        ResourceClaimLease claim = acquireGlobalClaim("rcd-cache-expire");
        performClaimedCall(claim, () -> {
            performRcCall("cache/expire", new CacheExpireRcOpParam(directoryPath, deleteData), EmptyOkResponse.class);
            return null;
        });
    }

    public void cacheFetch(String file, String chunks) {
        ResourceClaimLease claim = acquireGlobalClaim("rcd-cache-fetch");
        performClaimedCall(claim, () -> {
            performRcCall("cache/fetch", new CacheFetchRcOpParam(chunks, file), EmptyOkResponse.class);
            return null;
        });
    }

    public void createConfig(String name, String type, HashMap<String, String> keyValue) {
        ResourceClaimLease claim = acquireGlobalClaim("rcd-config-create");
        performClaimedCall(claim, () -> {
            performRcCall("config/create", new ConfigCreateRcOpParam(name, type, keyValue), EmptyOkResponse.class);
            configIdentityCache = null;
            return null;
        });
    }

    public ListRemotesResponse configListremotes() {
        ResourceClaimLease claim = acquireGlobalClaim("rcd-config-list");
        try {
            return performRcCall("config/listremotes", new NoParamRcOpParam(), ListRemotesResponse.class);
        } finally {
            claim.close();
        }
    }

    public Map<String, ConfigDumpRemote> configDump() {
        ResourceClaimLease claim = acquireGlobalClaim("rcd-config-dump");
        File configFile = new File(configPath);
        long mtime = configFile.lastModified();
        long length = configFile.length();
        String digest = EndpointConflictCoordinator.fingerprintFile(configFile);
        try {
            Map<String, ConfigDumpRemote> remotes = performRcCall(
                    "config/dump", new NoParamRcOpParam(),
                    new TypeReference<Map<String, ConfigDumpRemote>>() { });
            String currentDigest = EndpointConflictCoordinator.fingerprintFile(configFile);
            if (remotes != null && digest != null && digest.equals(currentDigest)
                    && configFile.lastModified() == mtime && configFile.length() == length) {
                configIdentityCache = new ConfigIdentitySnapshot(remotes, mtime, length, digest);
            } else {
                configIdentityCache = null;
            }
            return remotes;
        } finally {
            claim.close();
        }
    }

    public void isOnline() throws RcdIOException {
        ResourceClaimLease claim = acquireGlobalClaim("rcd-health");
        performClaimedCall(claim, () -> {
            performRcCall("rc/noopauth", new NoParamRcOpParam(), EmptyOkResponse.class);
            return null;
        });
    }

    public void sync(String srcFs, String dstFs, JobStatusHandler handler) {
        ResourceClaimLease claim = acquireFsPairClaim("rcd-sync", srcFs, dstFs);
        startClaimedJob(claim, () -> {
            JobIdResponse response = performRcCall("sync/sync", new SyncRcOpParam(srcFs, dstFs), JobIdResponse.class);
            registerClaimedJob(response.jobid, handler, claim);
        });
    }

    public void copy(String srcFs, String dstFs, JobStatusHandler handler) {
        ResourceClaimLease claim = acquireFsPairClaim("rcd-copy", srcFs, dstFs);
        startClaimedJob(claim, () -> {
            JobIdResponse response = performRcCall("sync/copy", new CopyRcOpParam(srcFs, dstFs), JobIdResponse.class);
            registerClaimedJob(response.jobid, handler, claim);
        });
    }

    public void copyFile(String srcRemoteName, String srcPath, String dstRemoteName, String dstPath, JobStatusHandler handler) {
        String srcFs = remoteNameAsFs(srcRemoteName);
        String dstFs = remoteNameAsFs(dstRemoteName);
        String normalizedSrcPath = pathAsPath(srcRemoteName, srcPath);
        String normalizedDstPath = pathAsPath(dstRemoteName, dstPath);
        ResourceClaimLease claim = acquireRemotePairClaim(
                "rcd-copy-file", srcRemoteName, srcPath, dstRemoteName, dstPath);
        startClaimedJob(claim, () -> {
            JobIdResponse response = performRcCall("operations/copyfile",
                    new CopyFileRcOpParam(normalizedSrcPath, srcFs, normalizedDstPath, dstFs),
                    JobIdResponse.class);
            registerClaimedJob(response.jobid, handler, claim);
        });
    }

    public void move(String srcFs, String dstFs, JobStatusHandler handler) {
        ResourceClaimLease claim = acquireFsPairClaim("rcd-move", srcFs, dstFs);
        startClaimedJob(claim, () -> {
            JobIdResponse response = performRcCall("sync/move", new MoveRcOpParam(srcFs, dstFs, false), JobIdResponse.class);
            registerClaimedJob(response.jobid, handler, claim);
        });
    }

    public ListItem[] list(String remoteName, String path) {
        String fs = remoteNameAsFs(remoteName);
        String normalizedPath = pathAsPath(remoteName, path);
        ResourceClaimLease claim = acquireRemoteClaim("rcd-list", remoteName, path);
        return performClaimedCall(claim, () -> performRcCall(
                "operations/list", new ListRcOpParam(fs, normalizedPath), ListRcOpResponse.class).list);
    }

    public JobStatusResponse getJobStatus(int jobId) {
        return performRcCall("job/status", new JobStatusRcOpParam(jobId), JobStatusResponse.class);
    }

    public JobListResponse listJobs() {
        return performRcCall("job/list", new NoParamRcOpParam(), JobListResponse.class);
    }

    public AboutResponse getStorageUsage(String remoteName) {
        ResourceClaimLease claim = acquireRemoteClaim("rcd-about", remoteName, "");
        return performClaimedCall(claim, () -> performRcCall(
                "operations/about", new AboutRcOpParam(remoteNameAsFs(remoteName)), AboutResponse.class));
    }

    // TODO: figure out how this works - docu unclear!
    public void delete(String fs) {
        ResourceClaimLease claim = acquireFsClaim("rcd-delete", fs);
        performClaimedCall(claim, () -> {
            performRcCall("operations/delete", new DeleteRcOpParam(fs), EmptyOkResponse.class);
            return null;
        });
    }

    public void deleteFile(String remoteName, String path, JobStatusHandler handler) {
        String fs = remoteNameAsFs(remoteName);
        String normalizedPath = pathAsPath(remoteName, path);
        ResourceClaimLease claim = acquireRemoteClaim("rcd-delete-file", remoteName, path);
        startClaimedJob(claim, () -> {
            JobIdResponse response = performRcCall("operations/deletefile",
                    new DeleteFileRcOpParam(fs, normalizedPath), JobIdResponse.class);
            registerClaimedJob(response.jobid, handler, claim);
        });
    }

    public void purge(String remoteName, String path, JobStatusHandler handler) {
        String fs = remoteNameAsFs(remoteName);
        String normalizedPath = pathAsPath(remoteName, path);
        ResourceClaimLease claim = acquireRemoteClaim("rcd-purge", remoteName, path);
        startClaimedJob(claim, () -> {
            JobIdResponse response = performRcCall("operations/purge",
                    new PurgeRcOpParam(fs, normalizedPath), JobIdResponse.class);
            registerClaimedJob(response.jobid, handler, claim);
        });
    }

    public FsInfoRcOpResponse getFsInfo(String remoteName) {
        ResourceClaimLease claim = acquireRemoteClaim("rcd-fsinfo", remoteName, "");
        String fs = remoteNameAsFs(remoteName);
        return performClaimedCall(claim, () -> performRcCall(
                "operations/fsinfo", new FsInfoRcOpParam(fs), FsInfoRcOpResponse.class));
    }

    public void mkDir(String remoteName, String path) {
        String fs = remoteNameAsFs(remoteName);
        String normalizedPath = pathAsPath(remoteName, path);
        ResourceClaimLease claim = acquireRemoteClaim("rcd-mkdir", remoteName, path);
        performClaimedCall(claim, () -> {
            performRcCall("operations/mkdir", new MkDirRcOpParam(fs, normalizedPath), EmptyOkResponse.class);
            return null;
        });
    }

    public void moveFile(String srcRemoteName, String srcPath, String dstRemoteName, String dstPath, JobStatusHandler handler) {
        String srcFs = remoteNameAsFs(srcRemoteName);
        String srcRemote = pathAsPath(srcRemoteName, srcPath);
        String dstFs = remoteNameAsFs(dstRemoteName);
        String dstRemote = pathAsPath(dstRemoteName, dstPath);
        ResourceClaimLease claim = acquireRemotePairClaim(
                "rcd-move-file", srcRemoteName, srcPath, dstRemoteName, dstPath);
        startClaimedJob(claim, () -> {
            JobIdResponse response = performRcCall("operations/movefile",
                    new MoveFileRcOpParam(srcFs, srcRemote, dstFs, dstRemote), JobIdResponse.class);
            registerClaimedJob(response.jobid, handler, claim);
        });
    }

    public String getPublicLink(String remoteName, String path) {
        String fs = remoteNameAsFs(remoteName);
        String normalizedPath = pathAsPath(remoteName, path);
        ResourceClaimLease claim = acquireRemoteClaim("rcd-public-link", remoteName, path);
        return performClaimedCall(claim, () -> performRcCall(
                "operations/publiclink", new PublicLinkRcOpParam(fs, normalizedPath), PublicLinkRcOpResponse.class).url);
    }

    public void rmDir(String remoteName, String path) {
        String fs = remoteNameAsFs(remoteName);
        String normalizedPath = pathAsPath(remoteName, path);
        ResourceClaimLease claim = acquireRemoteClaim("rcd-rmdir", remoteName, path);
        performClaimedCall(claim, () -> {
            performRcCall("operations/rmdir", new RmDirRcOpParam(fs, normalizedPath), EmptyOkResponse.class);
            return null;
        });
    }

    public void rmDirs(String remoteName, String path) {
        String fs = remoteNameAsFs(remoteName);
        String normalizedPath = pathAsPath(remoteName, path);
        ResourceClaimLease claim = acquireRemoteClaim("rcd-rmdirs", remoteName, path);
        performClaimedCall(claim, () -> {
            performRcCall("operations/rmdirs", new RmDirsRcOpParam(fs, normalizedPath), EmptyOkResponse.class);
            return null;
        });
    }

    ///
    /// Operation Parameter Classes
    ///

    private static class NoParamRcOpParam implements RcOpParam {
    }

    private static class CacheExpireRcOpParam implements RcOpParam {
        String remote;
        boolean withData;

        public CacheExpireRcOpParam(String remote, boolean withData) {
            this.remote = remote;
            this.withData = withData;
        }
    }

    private static class CacheFetchRcOpParam implements RcOpParam {
        String chunks;
        String file;

        public CacheFetchRcOpParam(String chunks, String file) {
            this.chunks = chunks;
            this.file = file;
        }
    }

    private static class ConfigCreateRcOpParam implements RcOpParam {
        String name;
        String type;
        HashMap<String, String> parameters;

        public ConfigCreateRcOpParam(String name, String type, HashMap<String, String> parameters) {
            this.name = name;
            this.type = type;
            this.parameters = parameters;
        }
    }

    private static class ConfigDeleteRcOpParam implements RcOpParam {
        String name;

        public ConfigDeleteRcOpParam(String name) {
            this.name = name;
        }
    }

    private static class ConfigGetRcOpParam implements RcOpParam {
        String name;

        public ConfigGetRcOpParam(String name) {
            this.name = name;
        }
    }

    private static class SyncRcOpParam implements RcloneRcd.RcOpParam {
        String srcFs;
        String dstFs;
        boolean _async = true;

        public SyncRcOpParam(String srcFs, String dstFs) {
            this.srcFs = srcFs;
            this.dstFs = dstFs;
        }
    }

    private static class CopyRcOpParam implements RcloneRcd.RcOpParam {
        String srcFs;
        String dstFs;
        boolean _async = true;

        public CopyRcOpParam(String srcFs, String dstFs) {
            this.srcFs = srcFs;
            this.dstFs = dstFs;
        }
    }

    private static class CopyFileRcOpParam implements RcloneRcd.RcOpParam {
        String srcFs;
        String srcRemote;
        String dstFs;
        String dstRemote;
        boolean _async = true;

        public CopyFileRcOpParam(String srcRemote, String srcFs, String dstRemote, String dstFs) {
            this.srcRemote = srcRemote;
            this.srcFs = srcFs;
            this.dstRemote = dstRemote;
            this.dstFs = dstFs;
        }
    }

    private static class CopyUrlRcOpParam implements RcloneRcd.RcOpParam {
        String fs;
        String remote;
        String url;
        boolean _async = true;

        public CopyUrlRcOpParam(String fs, String remote, String url) {
            this.fs = fs;
            this.remote = remote;
            this.url = url;
        }
    }

    private static class MoveRcOpParam implements RcloneRcd.RcOpParam {
        String srcFs;
        String dstFs;
        boolean deleteEmptySrcDirs;
        boolean _async = true;

        public MoveRcOpParam(String srcFs, String dstFs, boolean deleteEmptySrcDirs) {
            this.srcFs = srcFs;
            this.dstFs = dstFs;
            this.deleteEmptySrcDirs = deleteEmptySrcDirs;
        }
    }

    private static class MoveFileRcOpParam implements RcloneRcd.RcOpParam {
        String srcFs;
        String srcRemote;
        String dstFs;
        String dstRemote;
        boolean _async = true;

        public MoveFileRcOpParam(String srcFs, String srcRemote, String dstFs, String dstRemote) {
            this.srcFs = srcFs;
            this.srcRemote = srcRemote;
            this.dstFs = dstFs;
            this.dstRemote = dstRemote;
        }
    }

    // TODO: doc unclear
    private static class DeleteRcOpParam implements RcloneRcd.RcOpParam {
        String fs;

        public DeleteRcOpParam(String fs) {
            this.fs = fs;
        }
    }

    private static class DeleteFileRcOpParam implements RcloneRcd.RcOpParam {
        String fs;
        String remote;
        boolean _async = true;

        public DeleteFileRcOpParam(String fs, String remote) {
            this.fs = fs;
            this.remote = remote;
        }
    }

    private static class FsInfoRcOpParam implements RcloneRcd.RcOpParam {
        String fs;

        public FsInfoRcOpParam(String fs) {
            this.fs = fs;
        }
    }

    private static class ListRcOpParam implements RcloneRcd.RcOpParam {
        String fs;
        String remote;

        public ListRcOpParam(String fs, String remote) {
            this.fs = fs;
            this.remote = remote;
        }
    }

    private static class MkDirRcOpParam implements RcloneRcd.RcOpParam {
        String fs;
        String remote;

        public MkDirRcOpParam(String fs, String remote) {
            this.fs = fs;
            this.remote = remote;
        }
    }

    private static class RmDirRcOpParam implements RcloneRcd.RcOpParam {
        String fs;
        String remote;

        public RmDirRcOpParam(String fs, String remote) {
            this.fs = fs;
            this.remote = remote;
        }
    }

    private static class PurgeRcOpParam implements RcloneRcd.RcOpParam {
        String fs;
        String remote;
        boolean _async = true;

        public PurgeRcOpParam(String fs, String remote) {
            this.fs = fs;
            this.remote = remote;
        }
    }

    private static class RmDirsRcOpParam implements RcloneRcd.RcOpParam {
        String fs;
        String remote;
        boolean leaveRoot;

        public RmDirsRcOpParam(String fs, String remote) {
            this.fs = fs;
            this.remote = remote;
            this.leaveRoot = false;
        }

        public RmDirsRcOpParam(String fs, String remote, boolean leaveRoot) {
            this.fs = fs;
            this.remote = remote;
            this.leaveRoot = leaveRoot;
        }
    }

    private static class PublicLinkRcOpParam implements RcloneRcd.RcOpParam {
        String fs;
        String remote;

        public PublicLinkRcOpParam(String fs, String remote) {
            this.fs = fs;
            this.remote = remote;
        }
    }

    private static class JobStatusRcOpParam implements RcloneRcd.RcOpParam {
        int jobid;

        public JobStatusRcOpParam(int jobid) {
            this.jobid = jobid;
        }
    }

    private static class JobStopRcOpParam implements RcloneRcd.RcOpParam {
        int jobid;

        public JobStopRcOpParam(int jobid) {
            this.jobid = jobid;
        }
    }

    private static class AboutRcOpParam implements RcloneRcd.RcOpParam {
        String fs;

        public AboutRcOpParam(String fs) {
            this.fs = fs;
        }
    }

    private static class CleanupRcOpParam implements RcloneRcd.RcOpParam {
        String fs;

        public CleanupRcOpParam(String fs) {
            this.fs = fs;
        }
    }

    /**
     * Marker interface for RC Parameters
     */
    interface RcOpParam {
    }

    /**
     * Marker interface for RC Responses
     */
    interface RcOpResponse {
    }

    public interface JobStatusHandler {
        void handleJobStatus(JobStatusResponse jobStatusResponse);
    }

    public static class RcdOpException extends RuntimeException {
        private ErrorResponse error;

        public RcdOpException(ErrorResponse error) {
            super("Error when executing " + error.operation);
            this.error = error;
        }

        private RcdOpException() {
        }

        public String getError() {
            return error.getError();
        }

        int getStatus() {
            return error == null ? -1 : error.getStatus();
        }
    }

    public static class RcdIOException extends RcdOpException {
        private IOException exception;

        public RcdIOException(IOException exception) {
            this.exception = exception;
        }

        @Override
        public String getError() {
            return exception.getClass().getSimpleName() + ": " + exception.getMessage();
        }
    }

    ///
    /// Operation Response DTOs
    ///
    private static class GenericResponse {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private static class EmptyOkResponse extends GenericResponse implements RcOpResponse {
    }


    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ErrorResponse extends GenericResponse implements RcOpResponse {

        @JsonProperty("error")
        String error;

        @JsonProperty("path")
        String operation;

        @JsonProperty("status")
        int status;

        public String getError() {
            return error;
        }

        public String getOperation() {
            return operation;
        }

        public int getStatus() {
            return status;
        }
    }

    public static class ListRemotesResponse implements RcOpResponse {
        String[] remotes;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ConfigDumpRemote {
        String type;
        String remote;
        String root;
    }

    private static class AboutResponse implements RcOpResponse {
        long free;
        long total;
        long trashed;
        long used;
    }

    // Example
    // ===
    // {
    //	"duration": 0.514823698,
    //	"endTime": "2021-05-11T22:21:41.780060917Z",
    //	"error": "mkdir /Alarms: read-only file system",
    //	"finished": true,
    //	"group": "job/1",
    //	"id": 1,
    //	"output": {},
    //	"startTime": "2021-05-11T22:21:41.265237323Z",
    //	"success": false
    //}
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class JobStatusResponse implements RcOpResponse {
        public int id;
        public boolean finished;
        public boolean success;
        @JsonDeserialize(using = Rfc3339Deserializer.class)
        public long startTime;
        @JsonDeserialize(using = Rfc3339Deserializer.class)
        public long endTime;
        public String error;
        public Object output;
        public String progress;
    }

    public static class JobListResponse implements RcOpResponse {
        public int[] jobids;
    }

    private static class JobIdResponse implements RcOpResponse {
        int jobid;
    }

    private static class ListRcOpResponse implements RcOpResponse {
        ListItem[] list;
    }

    /**
     * Less memory overhead than {@link ca.pkay.rcloneexplorer.Items.FileItem} by removing pregenerated formatting
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ListItem {
        @JsonProperty("Path")
        String path;

        @JsonProperty("Name")
        String name;

        @JsonProperty("MimeType")
        String mimeType;

        @JsonProperty("Size")
        long size;

        @JsonProperty("ModTime")
        @JsonDeserialize(using = Rfc3339Deserializer.class)
        long lastModified;

        @JsonProperty("IsDir")
        boolean isDir;
    }

    private static class FsInfoRcOpResponse implements RcOpResponse {

        @JsonIgnoreProperties(ignoreUnknown = true)
        private static class FsInfoFeatures {
            @JsonProperty("About")
            boolean about;

            @JsonProperty("Copy")
            boolean copy;

            @JsonProperty("DirMove")
            boolean dirMove;

            @JsonProperty("Move")
            boolean move;

            @JsonProperty("PublicLink")
            boolean publicLink;

            @JsonProperty("PutStream")
            boolean putStream;

            @JsonProperty("WrapFs")
            boolean wrapFs;
        }

        @JsonProperty("Features")
        FsInfoFeatures features;

        @JsonProperty("Hashes")
        String[] hashes;

        @JsonProperty("Name")
        String name;

        @JsonProperty("Precision")
        int precision;

        @JsonProperty("String")
        String inLogsAs;
    }

    private static class PublicLinkRcOpResponse implements RcOpResponse {
        String url;
    }
}
