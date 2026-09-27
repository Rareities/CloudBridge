package ca.pkay.rcloneexplorer.Activities;

import static ca.pkay.rcloneexplorer.util.ActivityHelper.tryStartService;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Process;
import android.content.pm.PackageManager;
import android.provider.OpenableColumns;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.core.view.WindowCompat;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.fragment.app.FragmentTransaction;
import androidx.work.Operation;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

import ca.pkay.rcloneexplorer.Dialogs.Dialogs;
import ca.pkay.rcloneexplorer.Dialogs.LoadingDialog;
import ca.pkay.rcloneexplorer.Fragments.ShareFragment;
import ca.pkay.rcloneexplorer.Fragments.ShareRemotesFragment;
import ca.pkay.rcloneexplorer.Items.RemoteItem;
import ca.pkay.rcloneexplorer.R;
import ca.pkay.rcloneexplorer.Rclone;
import ca.pkay.rcloneexplorer.RuntimeConfiguration;
import ca.pkay.rcloneexplorer.util.ActivityHelper;
import ca.pkay.rcloneexplorer.util.FLog;
import ca.pkay.rcloneexplorer.util.IncomingShareUriPolicy;
import ca.pkay.rcloneexplorer.util.ShareStagingPolicy;
import ca.pkay.rcloneexplorer.workmanager.EphemeralTaskManager;
import ca.pkay.rcloneexplorer.workmanager.ShareUploadBatchTagPolicy;
import es.dmoral.toasty.Toasty;

public class SharingActivity extends AppCompatActivity implements ShareRemotesFragment.OnRemoteClickListener,
        ShareFragment.OnShareDestinationSelected {

    private static final String TAG = "SharingActivity";
    private Fragment fragment;
    private volatile List<String> uploadList = Collections.emptyList();
    private volatile ShareStagingPolicy.StagedShare stagedShare;
    private volatile String shareUploadBatchTag;
    private volatile boolean stagedShareOwnershipTransferred;
    private volatile boolean activityFinishing;
    private final CountDownLatch stagingComplete = new CountDownLatch(1);
    private volatile boolean stagingSucceeded;
    private volatile boolean stagingLimitExceeded;
    private CopyFile copyFileTask;
    private UploadTask uploadTask;

    @Override
    protected void attachBaseContext(Context newBase) {
        super.attachBaseContext(RuntimeConfiguration.attach(this, newBase));
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        WindowCompat.setDecorFitsSystemWindows(getWindow(), true);
        ActivityHelper.applyTheme(this);
        setContentView(R.layout.activity_sharing);
        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);

        Rclone rclone = new Rclone(this);
        Intent intent = getIntent();
        String action = intent.getAction();
        String type = intent.getType();

        if (Intent.ACTION_SEND.equals(action) && type != null) {
            copyFile(intent);
        } else if (Intent.ACTION_SEND_MULTIPLE.equals(action) && type != null) {
            copyFiles(intent);
        } else {
            finish();
            return;
        }

        if (rclone.isConfigEncrypted() || !rclone.isConfigFileCreated() || rclone.getRemotes().isEmpty()) {
            AlertDialog.Builder builder = new AlertDialog.Builder(this);
            builder
                    .setTitle(R.string.app_not_configured)
                    .setMessage(R.string.open_app_to_configure)
                    .setPositiveButton(R.string.ok, (dialog, which) -> finish())
                    .show();
        } else {
            startRemotesFragment();
        }
    }

    @Override
    protected void onDestroy() {
        activityFinishing = true;
        if (copyFileTask != null) {
            copyFileTask.cancel(true);
        }
        if (uploadTask != null) {
            uploadTask.cancel(true);
        }
        cleanupStagedShareIfOwned();
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        if (fragment != null && fragment instanceof ShareFragment) {
            if (((ShareFragment)fragment).onBackButtonPressed()) {
                return;
            }
        }
        super.onBackPressed();
    }

    private void startRemotesFragment() {
        fragment = ShareRemotesFragment.newInstance();
        FragmentManager fragmentManager = getSupportFragmentManager();

        for (int i = 0; i < fragmentManager.getBackStackEntryCount(); i++) {
            fragmentManager.popBackStack();
        }

        fragmentManager.beginTransaction().replace(R.id.flFragment, fragment).commit();
    }

    @Override
    public void onRemoteClick(RemoteItem remote) {
        startRemote(remote);
    }

    private void startRemote(RemoteItem remoteItem) {
        fragment = ShareFragment.newInstance(remoteItem);
        FragmentTransaction transaction = getSupportFragmentManager().beginTransaction();
        transaction.replace(R.id.flFragment, fragment);
        transaction.addToBackStack(null);
        transaction.commit();
    }

    @Override
    public void onShareDestinationSelected(RemoteItem remote, String path) {
        uploadTask = new UploadTask(this, remote, path);
        uploadTask.execute();
    }

    private void copyFile(Intent intent) {
        ArrayList<Uri> uris = extractStreamUris(intent, false);
        if (uris == null || !hasAuthorizedReadGrant(intent, uris)) {
            FLog.w(TAG, "Rejected an incoming share with invalid data or missing read access");
            finish();
            return;
        }
        copyFileTask = new CopyFile(this, uris.get(0));
        copyFileTask.execute();
    }

    private void copyFiles(Intent intent) {
        ArrayList<Uri> uris = extractStreamUris(intent, true);
        if (uris == null || !hasAuthorizedReadGrant(intent, uris)) {
            FLog.w(TAG, "Rejected an incoming share with invalid data or missing read access");
            finish();
            return;
        }
        copyFileTask = new CopyFile(this, uris);
        copyFileTask.execute();
    }

    /** Read untrusted share extras without casting a caller-controlled Parcelable or list. */
    @Nullable
    private ArrayList<Uri> extractStreamUris(@Nullable Intent intent, boolean multiple) {
        try {
            Bundle extras = intent == null ? null : intent.getExtras();
            if (extras == null) return null;
            // Only framework Uri parcelables are valid here. Do not let a share sender ask
            // this exported activity to instantiate an app-defined Parcelable from the Bundle.
            extras.setClassLoader(Uri.class.getClassLoader());
            if (!extras.containsKey(Intent.EXTRA_STREAM)) return null;
            Object value = extras.get(Intent.EXTRA_STREAM);
            ArrayList<Uri> result = new ArrayList<>();
            if (!multiple) {
                if (!(value instanceof Uri)) return null;
                result.add((Uri) value);
                return result;
            }
            if (!(value instanceof List<?>)) return null;
            List<?> candidates = (List<?>) value;
            if (candidates.isEmpty()
                    || candidates.size() > ShareStagingPolicy.DEFAULT_LIMITS.getMaxFiles()) {
                return null;
            }
            for (Object candidate : candidates) {
                if (!(candidate instanceof Uri)) return null;
                result.add((Uri) candidate);
            }
            return result;
        } catch (RuntimeException malformedExtra) {
            // Bundle unparcelling can throw for hostile or malformed external Parcelable data.
            return null;
        }
    }

    private boolean hasAuthorizedReadGrant(@Nullable Intent intent, List<Uri> uris) {
        if (intent == null
                || (intent.getFlags() & Intent.FLAG_GRANT_READ_URI_PERMISSION) == 0) {
            return false;
        }
        for (Uri uri : uris) {
            if (uri == null || uri.getPath() == null) return false;
            boolean appCanRead = checkUriPermission(
                    uri, Process.myPid(), Process.myUid(), Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    == PackageManager.PERMISSION_GRANTED;
            if (!IncomingShareUriPolicy.allows(
                    uri.getScheme(), uri.getAuthority(), getPackageName(), true, appCanRead)) {
                return false;
            }
        }
        return true;
    }

    private void cleanupStagedShareIfOwned() {
        if (stagedShareOwnershipTransferred) return;
        ShareStagingPolicy.StagedShare share = stagedShare;
        if (share == null) return;
        try {
            share.cleanup();
            stagedShare = null;
        } catch (IOException failure) {
            FLog.e(TAG, "Unable to clean an abandoned staged share");
        }
    }

    @SuppressLint("StaticFieldLeak")
    private class UploadTask extends AsyncTask<Void, Void, Boolean> {

        RemoteItem remote;
        String path;
        Context context;
        LoadingDialog loadingDialog;

        UploadTask(Context context, RemoteItem remote, String path) {
            this.context = context;
            this.remote = remote;
            this.path = path;
        }

        @Override
        protected void onPreExecute() {
            super.onPreExecute();
            loadingDialog = new LoadingDialog()
                    .setTitle(R.string.loading)
                    .setNegativeButton(R.string.cancel)
                    .setOnNegativeListener(() -> cancel(true));
            loadingDialog.show(getSupportFragmentManager(), "loading dialog");
        }

        @Override
        protected Boolean doInBackground(Void... voids) {
            while (!isCancelled()) {
                try {
                    if (stagingComplete.await(100, TimeUnit.MILLISECONDS)) {
                        return stagingSucceeded;
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
            return false;
        }

        @Override
        protected void onPostExecute(Boolean ready) {
            super.onPostExecute(ready);
            Dialogs.dismissSilently(loadingDialog);

            if (!Boolean.TRUE.equals(ready)) {
                cleanupStagedShareIfOwned();
                finish();
                return;
            }

            try {
                Operation operation = EphemeralTaskManager.Companion.queueShareUploads(
                        this.context, remote, uploadList, path, shareUploadBatchTag);
                stagedShareOwnershipTransferred = true;
                operation.getResult().addListener(() -> {
                    try {
                        operation.getResult().get();
                    } catch (InterruptedException failure) {
                        Thread.currentThread().interrupt();
                        FLog.e(TAG, "Share upload enqueue status is unknown; staged files were retained");
                    } catch (ExecutionException | RuntimeException failure) {
                        FLog.e(TAG, "Share upload enqueue failed; staged files were retained");
                    }
                }, new Executor() {
                    @Override
                    public void execute(Runnable command) {
                        command.run();
                    }
                });
            } catch (RuntimeException failure) {
                // Enqueue may have committed before reporting a scheduling error. Preserve the
                // staged sources because WorkInfo cannot prove whether a native reader is gone.
                stagedShareOwnershipTransferred = true;
                FLog.e(TAG, "Share upload enqueue failed; staged files were retained");
                Toasty.error(context, getString(R.string.error_retrieving_files),
                        Toast.LENGTH_LONG, true).show();
                finish();
                return;
            }
            finish();
        }

        @Override
        protected void onCancelled(Boolean ready) {
            super.onCancelled(ready);
            Dialogs.dismissSilently(loadingDialog);
            cleanupStagedShareIfOwned();
            finish();
        }
    }

    @SuppressLint("StaticFieldLeak")
    private class CopyFile extends AsyncTask<Void, Void, Boolean> {

        private static final String TAG = "SharingActvty/CopyFile";
        private Context context;
        private ArrayList<Uri> uris;

        CopyFile(Context context, Uri uri) {
            this.context = context;
            uris = new ArrayList<>();
            uris.add(uri);
        }

        CopyFile(Context context, ArrayList<Uri> uris) {
            this.context = context;
            this.uris = uris;
        }

        @Override
        protected Boolean doInBackground(Void... voids) {
            if (uris == null || uris.isEmpty()) {
                return false;
            }
            if (uris.size() > ShareStagingPolicy.DEFAULT_LIMITS.getMaxFiles()) {
                stagingLimitExceeded = true;
                return false;
            }

            List<ShareStagingPolicy.Source> sources = new ArrayList<>(uris.size());
            try {
                for (Uri uri : uris) {
                    if (isCancelled()) return false;
                    // Reject non-content URIs and this app's own providers before querying or
                    // opening. An exported activity has the provider app's UID and does not
                    // need an external caller's grant to read its own provider content.
                    if (uri == null || uri.getPath() == null
                            || !IncomingShareUriPolicy.allows(
                                    uri.getScheme(), uri.getAuthority(), getPackageName())) {
                        return false;
                    }

                    String fileName = resolveName(uri);
                    Uri sourceUri = uri;
                    sources.add(new ShareStagingPolicy.Source(
                            fileName,
                            () -> getContentResolver().openInputStream(sourceUri)
                    ));
                }

                ShareStagingPolicy.StagedShare staged = ShareStagingPolicy.stage(
                        getCacheDir(), sources, ShareStagingPolicy.DEFAULT_LIMITS,
                        () -> isCancelled() || activityFinishing ||
                                Thread.currentThread().isInterrupted());
                stagedShare = staged;
                shareUploadBatchTag = ShareUploadBatchTagPolicy.forStagingDirectory(
                        staged.getDirectory());
                if (isCancelled() || activityFinishing) {
                    cleanupStagedShareIfOwned();
                    return false;
                }
                List<String> stagedPaths = new ArrayList<>(staged.getFiles().size());
                for (File stagedFile : staged.getFiles()) {
                    stagedPaths.add(stagedFile.getAbsolutePath());
                }
                uploadList = Collections.unmodifiableList(stagedPaths);
                return true;
            } catch (ShareStagingPolicy.LimitExceededException limitExceeded) {
                stagingLimitExceeded = true;
                FLog.w(TAG, "Incoming share exceeds the staging size or file-count limit");
                return false;
            } catch (IOException | RuntimeException failure) {
                // Content-provider exceptions can include the full incoming URI, which may
                // contain user data or access tokens. Keep this diagnostic deliberately generic.
                FLog.e(TAG, "Unable to stage incoming shared files");
                return false;
            }
        }

        @NonNull
        private String resolveName(@NonNull Uri uri) {
            String[] projection = {OpenableColumns.DISPLAY_NAME};
            try (Cursor cursor = getContentResolver().query(uri, projection, null, null, null)) {
                if (null != cursor && cursor.moveToFirst()) {
                    int index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                    if(index>=0) {
                        String name = cursor.getString(index);
                        if (null != name) {
                            return name;
                        }
                    }
                }
            }
            List<String> segments = uri.getPathSegments();
            if (segments.size() >= 1) {
                return segments.get(segments.size() - 1);
            }
            return "unnamed";
        }

        @Override
        protected void onPostExecute(Boolean success) {
            super.onPostExecute(success);
            stagingSucceeded = Boolean.TRUE.equals(success);
            stagingComplete.countDown();
            if (!stagingSucceeded) {
                int message = stagingLimitExceeded
                        ? R.string.share_staging_limit_exceeded
                        : R.string.error_retrieving_files;
                Toasty.error(context, getString(message), Toast.LENGTH_LONG, true).show();
                finish();
            }
        }

        @Override
        protected void onCancelled(Boolean success) {
            super.onCancelled(success);
            stagingSucceeded = false;
            stagingComplete.countDown();
        }
    }
}
