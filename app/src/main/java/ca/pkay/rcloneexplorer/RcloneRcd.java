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
import ca.pkay.rcloneexplorer.util.NativePipeTransfer;
import ca.pkay.rcloneexplorer.util.NativeSessionGate;
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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The NG version of rclone command runner using rc/rcd. The backbone for
 * VirtualContentProvider.
 */
public class RcloneRcd {

    private static final String TAG = "RcloneRcd";
    private static final java.util.Set<Process> activeProcesses = java.util.Collections.newSetFromMap(
            new java.util.concurrent.ConcurrentHashMap<Process, Boolean>());
    private static final java.util.Set<RcloneRcd> activeDaemons = java.util.Collections.newSetFromMap(
            new java.util.concurrent.ConcurrentHashMap<RcloneRcd, Boolean>());

    /** Explicit configuration recovery may retire idle browsing, never active user transfers. */
    public static void quiesceForConfigurationChange() throws IOException {
        if (Looper.myLooper() == Looper.getMainLooper())
            throw new IOException("Reconnect must run outside the UI thread");
        java.util.List<RcloneRcd> retiring;
        java.util.concurrent.locks.Lock lock = ca.pkay.rcloneexplorer.util.ConfigurationExclusion.LOCK.writeLock();
        lock.lock();
        try {
            if (NativeSessionGate.GLOBAL.isBusy())
                throw new IOException("Finish active transfers and file streams before reconnecting");
            retiring = new java.util.ArrayList<>(activeDaemons);
            for (RcloneRcd daemon : retiring) {
                if (daemon.hasActiveWork()) throw new IOException("The remote service has active work; retry after it finishes");
            }
            for (RcloneRcd daemon : retiring) {
                if (!daemon.stopped && !daemon.stopRcdIfIdle())
                    throw new IOException("New remote work started; retry after it finishes");
            }
        } finally { lock.unlock(); }
        for (RcloneRcd daemon : retiring) {
            if (!daemon.awaitNativeExit(5000)) throw new IOException("The remote service is still stopping; retry after it exits");
        }
        if (NativeSessionGate.GLOBAL.isBusy()) throw new IOException("Another native operation started; retry when it finishes");
    }

    public static boolean hasRunningProcess() {
        for (Process process : activeProcesses) {
            try {
                process.exitValue();
                activeProcesses.remove(process);
            } catch (IllegalThreadStateException running) {
                // Keep the process registered until it confirms termination.
            }
        }
        return !activeProcesses.isEmpty();
    }
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

    private final String configPath;
    private final String rclone;
    private Process rcd;
    private volatile boolean stopped = false;
    private volatile long sessionGeneration;
    private NativePipeTransfer nativeLifetime;
    private final Map<Integer, NativeSessionGate.Lease> jobSessions = new ConcurrentHashMap<>();
    private final Map<Integer, Integer> statusFailures = new ConcurrentHashMap<>();
    private final java.util.concurrent.atomic.AtomicBoolean exitHandled = new java.util.concurrent.atomic.AtomicBoolean();
    private final java.util.concurrent.CountDownLatch nativeExited = new java.util.concurrent.CountDownLatch(1);
    private final java.util.concurrent.atomic.AtomicReference<NativeSessionGate.Lease> startupSession = new java.util.concurrent.atomic.AtomicReference<>();
    private Object mainThreadLock = new Object();

    final BlockingQueue<Integer> pendingJobs;
    final SparseArray<JobStatusHandler> jobsHandlers;
    final SparseArray<JobStatusResponse> lastStatus;
    private final ScheduledExecutorService jobMonitorService;
    private final ExecutorService jobStatusExecutor;
    final JobsUpdateHandler jobsUpdateHandler;
    private final AtomicInteger activeRequests = new AtomicInteger();
    private final java.util.Set<Integer> activeJobs = java.util.Collections.newSetFromMap(new ConcurrentHashMap<Integer, Boolean>());
    private final Object processUseLock = new Object();

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
        startRcdWithStartup(this::startRcdUnlocked);
    }

    /** Package-private seam for testing failures before native process creation. */
    void startRcdWithStartup(Runnable startup) {
        ca.pkay.rcloneexplorer.util.ConfigurationExclusion.LOCK.writeLock().lock();
        try {
            sessionGeneration = NativeSessionGate.GLOBAL.generation();
            try { startupSession.set(NativeSessionGate.GLOBAL.acquireRc(sessionGeneration)); }
            catch (IOException busy) { throw new RcdIOException(busy); }
            activeDaemons.add(this);
            try { startup.run(); }
            catch (RuntimeException | Error failure) {
                // Even an unchecked pre-spawn failure must relinquish the startup
                // lease. If native launch succeeded, ownership lasts until exit.
                stopRcd();
                if (rcd == null) releaseStartupSession();
                else if (nativeLifetime == null) releaseStartupAfterExit(rcd);
                throw failure;
            }
        } finally {
            ca.pkay.rcloneexplorer.util.ConfigurationExclusion.LOCK.writeLock().unlock();
        }
    }

    private void releaseStartupAfterExit(Process process) {
        Thread waiter = new Thread(() -> {
            boolean interrupted = false;
            for (;;) {
                try { process.waitFor(); break; }
                catch (InterruptedException retry) { interrupted = true; }
            }
            onNativeExit();
            if (interrupted) Thread.currentThread().interrupt();
        }, "rcd-startup-exit");
        waiter.setDaemon(true);
        waiter.start();
        process.destroy();
    }

    private void startRcdUnlocked() {
        try {
            if (hasRunningProcess()) throw new IOException("The previous remote service is still stopping. Retry after it exits.");
            FLog.d(TAG, "startRcd: starting rclone process");
            port = nextAvailablePort();
            String addr = "localhost:" + port;
            String tmpDir = context.getCacheDir().getAbsolutePath();
            SharedPreferences pref = PreferenceManager.getDefaultSharedPreferences(context);
            String transfers = pref.getString(context.getString(R.string.pref_key_transfers), "4");
            ArrayList<String> parameters = new ArrayList<>(Arrays.asList(
                    rclone,
                    "--config", configPath,
                    "--rc-addr", addr,
                    "--rc-serve",
                    // Transfer throughput tuning for VCP/rcd-driven operations.
                    "--transfers", transfers,
                    "--buffer-size", "16M",
                    "--multi-thread-streams", "4"));
            parameters.add("--log-level=NOTICE");
            parameters.add("rcd");
            rcd = ca.pkay.rcloneexplorer.util.ProtectedRcloneConfig.launch(context, parameters.toArray(new String[0]), getEnv());
            activeProcesses.add(rcd);
            // Drain both pipes from startup. A native log file would bypass the
            // app redactor, and an unread stderr pipe can block a long RC job.
            final ca.pkay.rcloneexplorer.util.NativePipeTransfer diagnostics =
                    new ca.pkay.rcloneexplorer.util.NativePipeTransfer(rcd, true);
            nativeLifetime = diagnostics;
            final boolean logging = pref.getBoolean(context.getString(R.string.pref_key_logs), false);
            diagnostics.onExit(() -> {
                onNativeExit();
                try { diagnostics.awaitSuccessfulExit(); }
                catch (IOException error) {
                    if (logging) FLog.w(TAG, "RC process ended: %s", error.getMessage());
                }
            });
            diagnostics.output().close();
        } catch (IOException e) {
            stopRcd();
            FLog.e(TAG, "startRcd: error", e);
            throw new RuntimeException(e);
        }
        // PendingRcloneJobs blocks thread until first job arrives
        jobsUpdateFuture = jobMonitorService.scheduleWithFixedDelay(new PendingRcloneJobs(), 0, 1, TimeUnit.SECONDS);
    }

    public String[] getEnv() {
        return new Rclone(context).getRcloneEnv("RCLONE_RC_USER=" + rcUser, "RCLONE_RC_PASS=" + rcPass);
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
    public void stopRcd() {
        synchronized (processUseLock) { stopped = true; }
        if (nativeLifetime != null) nativeLifetime.cancel();
        else if (rcd == null) onNativeExit();
        if (null != jobsUpdateFuture) {
            jobsUpdateFuture.cancel(true);
        }
        jobStatusExecutor.shutdownNow();
        jobMonitorService.shutdownNow();
        activityChanged();
    }

    private void onNativeExit() {
        if (!exitHandled.compareAndSet(false, true)) return;
        synchronized (processUseLock) { stopped = true; }
        if (jobsUpdateFuture != null) jobsUpdateFuture.cancel(true);
        jobStatusExecutor.shutdownNow();
        jobMonitorService.shutdownNow();
        // A requested stop may precede actual termination. Only OS-confirmed
        // exit permits unknown RC jobs to release mutation/session ownership.
        synchronized (jobsHandlers) {
            for (int i = 0; i < jobsHandlers.size(); i++) {
                int jobId = jobsHandlers.keyAt(i);
                JobStatusResponse result;
                synchronized (lastStatus) { result = lastStatus.get(jobId); }
                if (result == null || !result.finished) {
                    result = new JobStatusResponse();
                    result.id = jobId;
                    result.finished = true;
                    result.success = false;
                    result.error = "RC daemon stopped before job completion";
                    synchronized (lastStatus) { lastStatus.put(jobId, result); }
                }
                mainThread(jobsHandlers.valueAt(i), result);
            }
            jobsHandlers.clear();
        }
        for (NativeSessionGate.Lease lease : jobSessions.values()) lease.close();
        jobSessions.clear();
        statusFailures.clear();
        activeJobs.clear();
        releaseStartupSession();
        activeDaemons.remove(this);
        nativeExited.countDown();
        activityChanged();
    }

    /** Atomically exclude new requests when idle eviction starts. */
    public boolean stopRcdIfIdle() {
        synchronized (processUseLock) {
            if (stopped || activeRequests.get() > 0 || !activeJobs.isEmpty()) return false;
            stopped = true;
        }
        stopRcd();
        return true;
    }

    /**
     * Check if the server is still alive
     * @return
     */
    public boolean isAlive() {
        return !(null == rcd || stopped || !(getProcessState() == RUNNING)) &&
                NativeSessionGate.GLOBAL.isCurrent(sessionGeneration);
    }

    public boolean hasCrashed() {
        return rcd != null && getProcessState() == ERROR;
    }

    public boolean hasLiveProcess() {
        if (rcd == null) return false;
        try { rcd.exitValue(); return false; }
        catch (IllegalThreadStateException running) { return true; }
    }

    /**
     * Retrieve the process state
     * @param process
     * @return 0: running, -1 exited normally, 1 exited with error
     */
    private @ProcessState int getProcessState() {
        try {
            return rcd.exitValue() != 0 ? ERROR : EXITED;
        } catch (IllegalThreadStateException e) {
            return RUNNING;
        }
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
                try { return performRcCall(method, params, null, responseType); }
                finally { StrictMode.setThreadPolicy(previous); }
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
                try { return performRcCall(method, params, typeReference, null); }
                finally { StrictMode.setThreadPolicy(previous); }
            }
        } else {
            return performRcCall(method, params, typeReference, null);
        }
    }

    private OkHttpClient okHttpClient;

    private OkHttpClient prepareClient() {
        if (null == okHttpClient) {
            OkHttpClient.Builder builder = new OkHttpClient.Builder()
                    .retryOnConnectionFailure(false)
                    .followRedirects(false)
                    .followSslRedirects(false)
                    .connectTimeout(3, TimeUnit.SECONDS)
                    .callTimeout(30, TimeUnit.SECONDS)
                    .authenticator((route, response) -> {
                        if (response.request().header("Authorization") != null) return null;
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
        NativeSessionGate.Lease session = null;
        boolean localControl = method.startsWith("job/") || method.startsWith("rc/") ||
                method.equals("config/dump") || method.equals("config/listremotes");
        if (!localControl) {
            try { session = NativeSessionGate.GLOBAL.acquireRc(sessionGeneration); }
            catch (IOException busy) { throw new RcdIOException(busy); }
        }
        synchronized (processUseLock) {
            if (stopped) {
                if (session != null) session.close();
                throw new RcdIOException(new IOException("RC daemon stopped; retry operation"));
            }
            activeRequests.incrementAndGet();
        }
        activityChanged();
        try {
            return performRcCallActive(method, params, typeReference, responseType);
        } catch (RuntimeException failure) {
            if (session != null && (failure instanceof RcdIOException || !(failure instanceof RcdOpException))) {
                // The server may have accepted a mutation before its response
                // was lost. Never retry it or unlock while its daemon is live.
                session.closeAfterExit(rcd);
                session = null;
                stopRcd();
            }
            throw failure;
        } finally {
            if (session != null) session.close();
            activeRequests.decrementAndGet();
            activityChanged();
        }
    }

    private <T> T performRcCallActive(String method, RcOpParam params, TypeReference<T> typeReference, Class<T> responseType) {
        OkHttpClient client = prepareClient();
        if (method.equals("rc/noopauth")) client = client.newBuilder()
                .callTimeout(700, TimeUnit.MILLISECONDS).build();
        HttpUrl url = new HttpUrl.Builder()
                .scheme("http")
                .host("127.0.0.1")
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
                        JobStatusHandler terminalHandler = claimJobStatus(jobId, response);
                        if (response.finished) {
                            statusFailures.remove(jobId);
                            if (terminalHandler != null) {
                                FLog.v(TAG, "job finished: " + jobId);
                                mainThread(terminalHandler, response);
                            }
                            NativeSessionGate.Lease session = jobSessions.remove(jobId);
                            if (session != null) session.close();
                            activeJobs.remove(jobId);
                            activityChanged();
                            return -1; // terminal: do not re-queue
                        }
                        FLog.v(TAG, "job running: " + jobId);
                        statusFailures.remove(jobId);
                        return jobId; // still running: re-queue
                    } catch (RcdOpException e) {
                        FLog.e(TAG, "job error: ", e);
                        if ("job not found".equals(e.getError())) {
                            failJob(jobId, "RC job disappeared before its result could be confirmed");
                            return -1; // drop unknown job
                        }
                        if (!isAlive()) {
                            failJob(jobId, "RC daemon exited while the job was running");
                            return -1;
                        }
                        if (statusFailureLimitReached(jobId)) { stopRcd(); return -1; }
                        return jobId; // bounded transient error retries
                    }
                }));
            }
            List<Integer> pending = new ArrayList<>();
            for (int i = 0; i < futures.size(); i++) {
                Future<Integer> f = futures.get(i);
                try {
                    int requeue = f.get();
                    if (requeue != -1) {
                        pending.add(requeue);
                    }
                } catch (Exception e) {
                    FLog.w(TAG, "job status future failed: %s", e.toString());
                    int jobId = currentJobIds.get(i);
                    if (isAlive() && !statusFailureLimitReached(jobId)) pending.add(jobId);
                    else failJob(jobId, "RC daemon exited before the job result was confirmed");
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
        return !activeJobs.isEmpty();
    }

    /** Publish a confirmed terminal response and claim its callback atomically with exit fanout. */
    JobStatusHandler claimJobStatus(int jobId, JobStatusResponse response) {
        synchronized (jobsHandlers) {
            if (exitHandled.get()) return null;
            synchronized (lastStatus) { lastStatus.put(jobId, response); }
            if (!response.finished) return null;
            JobStatusHandler handler = jobsHandlers.get(jobId);
            jobsHandlers.remove(jobId);
            return handler;
        }
    }

    private void failJob(int jobId, String reason) {
        if (hasLiveProcess()) { stopRcd(); return; }
        JobStatusResponse failure = new JobStatusResponse();
        failure.id = jobId;
        failure.finished = true;
        failure.success = false;
        failure.error = reason;
        JobStatusHandler handler;
        synchronized (jobsHandlers) {
            if (exitHandled.get()) {
                // Exit fanout owns every unclaimed callback once exit is observed.
                handler = null;
            } else {
                handler = jobsHandlers.get(jobId);
                jobsHandlers.remove(jobId);
            }
            if (handler != null) {
                synchronized (lastStatus) {
                    JobStatusResponse confirmed = lastStatus.get(jobId);
                    if (confirmed != null && confirmed.finished) failure = confirmed;
                    else lastStatus.put(jobId, failure);
                }
            }
        }
        if (handler != null) mainThread(handler, failure);
        NativeSessionGate.Lease session = jobSessions.remove(jobId);
        if (session != null) session.closeAfterExit(rcd);
        activeJobs.remove(jobId);
        activityChanged();
    }

    private boolean statusFailureLimitReached(int jobId) {
        synchronized (statusFailures) {
            Integer previous = statusFailures.get(jobId);
            int count = previous == null ? 1 : previous + 1;
            statusFailures.put(jobId, count);
            return count >= 3;
        }
    }

    public boolean hasActiveWork() {
        return activeRequests.get() > 0 || !activeJobs.isEmpty() || startupSession.get() != null;
    }

    private void activityChanged() {
        if (jobsUpdateHandler != null) jobsUpdateHandler.onRcdActivityChanged();
    }

    /**
     * Registers a completion handler for an rcd job. Must be synchronized because the job-status
     * poller threads read this map concurrently (see {@link #jobStatusExecutor}).
     */
    private void registerJobHandler(int jobId, JobStatusHandler handler) {
        synchronized (jobsHandlers) {
            jobsHandlers.append(jobId, handler);
        }
        activeJobs.add(jobId);
        activityChanged();
    }

    /** Keep the RC request admitted until the job handler is registered and queued. */
    private void submitJob(String operation, RcOpParam params, JobStatusHandler handler) {
        NativeSessionGate.Lease session;
        try { session = NativeSessionGate.GLOBAL.acquireRc(sessionGeneration); }
        catch (IOException busy) { throw new RcdIOException(busy); }
        synchronized (processUseLock) {
            if (stopped) { session.close(); throw new RcdIOException(new IOException("RC daemon stopped; retry operation")); }
            activeRequests.incrementAndGet();
        }
        activityChanged();
        try {
            JobIdResponse response = performRcCall(operation, params, JobIdResponse.class);
            if (response == null || response.jobid <= 0)
                throw new RcdIOException(new IOException("RC did not confirm a valid job identifier"));
            synchronized (processUseLock) {
                if (stopped) throw new RcdIOException(new IOException("RC stopped before job acceptance was confirmed"));
                jobSessions.put(response.jobid, session);
                session = null;
                RcJobRegistration.publish(response.jobid, handler, this::registerJobHandler, pendingJobs);
            }
        } catch (RuntimeException failure) {
            boolean rejected = failure instanceof RcdOpException && !(failure instanceof RcdIOException);
            if (session != null) {
                if (rejected) session.close(); else session.closeAfterExit(rcd);
                session = null;
            }
            if (!rejected) stopRcd();
            throw failure;
        } finally {
            if (session != null) session.close();
            activeRequests.decrementAndGet();
            activityChanged();
        }
    }

    public interface JobsUpdateHandler {
        void onRcdJobsUpdate(SparseArray<JobStatusResponse> status);
        void onRcdActivityChanged();
    }

    //
    // Rclone rc API
    //

    public void cacheExpire(String directoryPath, boolean deleteData) {
        performRcCall("cache/expire", new CacheExpireRcOpParam(directoryPath, deleteData), EmptyOkResponse.class);
    }

    public void cacheFetch(String file, String chunks) {
        performRcCall("cache/fetch", new CacheFetchRcOpParam(chunks, file), EmptyOkResponse.class);
    }

    public void createConfig(String name, String type, HashMap<String, String> keyValue) {
        performRcCall("config/create", new ConfigCreateRcOpParam(name, type, keyValue), EmptyOkResponse.class);
    }

    public ListRemotesResponse configListremotes() {
        return performRcCall("config/listremotes", new NoParamRcOpParam(), ListRemotesResponse.class);
    }

    public Map<String, ConfigDumpRemote> configDump() {
        return performRcCall("config/dump", new NoParamRcOpParam(), new TypeReference<Map<String, ConfigDumpRemote>>() {
        });
    }

    public void isOnline() throws RcdIOException {
        performRcCall("rc/noopauth", new NoParamRcOpParam(), EmptyOkResponse.class);
        releaseStartupSession();
    }

    private void releaseStartupSession() {
        NativeSessionGate.Lease lease = startupSession.getAndSet(null);
        if (lease != null) lease.close();
    }

    public boolean awaitNativeExit(long timeoutMillis) {
        // Observing exitValue is earlier than the exit callback releasing job
        // and startup leases. Wait for that publication before allowing restart.
        if (rcd == null) return true;
        try { return nativeExited.await(Math.max(0, timeoutMillis), TimeUnit.MILLISECONDS); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); return false; }
    }

    public void sync(String srcFs, String dstFs, JobStatusHandler handler) {
        submitJob("sync/sync", new SyncRcOpParam(srcFs, dstFs), handler);
    }

    public void copy(String srcFs, String dstFs, JobStatusHandler handler) {
        submitJob("sync/copy", new CopyRcOpParam(srcFs, dstFs), handler);
    }

    public void copyFile(String srcRemoteName, String srcPath, String dstRemoteName, String dstPath, JobStatusHandler handler) {
        String srcFs = remoteNameAsFs(srcRemoteName);
        String dstFs = remoteNameAsFs(dstRemoteName);
        srcPath = pathAsPath(srcRemoteName, srcPath);
        dstPath = pathAsPath(dstRemoteName, dstPath);
        submitJob("operations/copyfile", new CopyFileRcOpParam(srcPath, srcFs, dstPath, dstFs), handler);
    }

    public void move(String srcFs, String dstFs, JobStatusHandler handler) {
        submitJob("sync/move", new MoveRcOpParam(srcFs, dstFs, false), handler);
    }

    public ListItem[] list(String remoteName, String path) {
        String fs = remoteNameAsFs(remoteName);
        path = pathAsPath(remoteName, path);
        ListRcOpResponse response = performRcCall("operations/list", new ListRcOpParam(fs, path), ListRcOpResponse.class);
        return response.list;
    }

    public JobStatusResponse getJobStatus(int jobId) {
        return performRcCall("job/status", new JobStatusRcOpParam(jobId), JobStatusResponse.class);
    }

    public JobListResponse listJobs() {
        return performRcCall("job/list", new NoParamRcOpParam(), JobListResponse.class);
    }

    public AboutResponse getStorageUsage(String remoteName) {
        return performRcCall("operations/about", new AboutRcOpParam(remoteNameAsFs(remoteName)), AboutResponse.class);
    }

    // TODO: figure out how this works - docu unclear!
    public void delete(String fs) {
        performRcCall("operations/delete", new DeleteRcOpParam(fs), EmptyOkResponse.class);
    }

    public void deleteFile(String remoteName, String path, JobStatusHandler handler) {
        String fs = remoteNameAsFs(remoteName);
        path = pathAsPath(remoteName, path);
        submitJob("operations/deletefile", new DeleteFileRcOpParam(fs, path), handler);
    }

    public void purge(String remoteName, String path, JobStatusHandler handler) {
        String fs = remoteNameAsFs(remoteName);
        path = pathAsPath(remoteName, path);
        submitJob("operations/purge", new PurgeRcOpParam(fs, path), handler);
    }

    public FsInfoRcOpResponse getFsInfo(String remoteName) {
        String fs = remoteNameAsFs(remoteName);
        return performRcCall("operations/fsinfo", new FsInfoRcOpParam(fs), FsInfoRcOpResponse.class);
    }

    public void mkDir(String remoteName, String path) {
        String fs = remoteNameAsFs(remoteName);
        path = pathAsPath(remoteName, path);
        performRcCall("operations/mkdir", new MkDirRcOpParam(fs, path), EmptyOkResponse.class);
    }

    public void moveFile(String srcRemoteName, String srcPath, String dstRemoteName, String dstPath, JobStatusHandler handler) {
        String srcFs = remoteNameAsFs(srcRemoteName);
        String srcRemote = pathAsPath(srcRemoteName, srcPath);
        String dstFs = remoteNameAsFs(dstRemoteName);
        String dstRemote = pathAsPath(dstRemoteName, dstPath);
        submitJob("operations/movefile", new MoveFileRcOpParam(srcFs, srcRemote, dstFs, dstRemote), handler);
    }

    public String getPublicLink(String remoteName, String path) {
        String fs = remoteNameAsFs(remoteName);
        path = pathAsPath(remoteName, path);
        PublicLinkRcOpResponse response = performRcCall("operations/publiclink", new PublicLinkRcOpParam(fs, path), PublicLinkRcOpResponse.class);
        return response.url;
    }

    public void rmDir(String remoteName, String path) {
        String fs = remoteNameAsFs(remoteName);
        path = pathAsPath(remoteName, path);
        performRcCall("operations/rmdir", new RmDirRcOpParam(fs, path), EmptyOkResponse.class);
    }

    public void rmDirs(String remoteName, String path) {
        String fs = remoteNameAsFs(remoteName);
        path = pathAsPath(remoteName, path);
        performRcCall("operations/rmdirs", new RmDirsRcOpParam(fs, path), EmptyOkResponse.class);
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

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ListRemotesResponse implements RcOpResponse {
        String[] remotes;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ConfigDumpRemote {
        String type;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
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

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class JobListResponse implements RcOpResponse {
        public int[] jobids;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private static class JobIdResponse implements RcOpResponse {
        int jobid;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
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

    @JsonIgnoreProperties(ignoreUnknown = true)
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

    @JsonIgnoreProperties(ignoreUnknown = true)
    private static class PublicLinkRcOpResponse implements RcOpResponse {
        String url;
    }
}
