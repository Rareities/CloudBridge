package io.github.rareities.protonbridge.Services;

import android.app.IntentService;
import android.content.Intent;
import androidx.annotation.Nullable;
import androidx.preference.PreferenceManager;
import io.github.rareities.protonbridge.Items.RemoteItem;
import io.github.rareities.protonbridge.R;
import io.github.rareities.protonbridge.Rclone;
import io.github.rareities.protonbridge.util.FLog;

public class ThumbnailsLoadingService extends IntentService {

    private static final String TAG = "ThumbnailsLoadingSvc";
    public static final String REMOTE_ARG = "io.github.rareities.protonbridge.ThumbnailsLoadingService.REMOTE_ARG";
    public static final String HIDDEN_PATH = "io.github.rareities.protonbridge.ThumbnailsLoadingService.HIDDEN_PATH";
    public static final String SERVER_PORT = "io.github.rareities.protonbridge.ThumbnailsLoadingService.PORT";

    private Rclone rclone;
    private Process process;

    public ThumbnailsLoadingService() {
        super("io.github.rareities.protonbridge.ThumbnailLoadingService");
    }

    @Override
    public void onCreate() {
        super.onCreate();
        rclone = new Rclone(this);
    }

    @Override
    protected void onHandleIntent(@Nullable Intent intent) {
        if (intent == null) {
            return;
        }

        RemoteItem remote = intent.getParcelableExtra(REMOTE_ARG);
        if (remote == null) {
            return;
        }
        String hiddenPathExtra = intent.getStringExtra(HIDDEN_PATH);
        if (hiddenPathExtra == null) {
            hiddenPathExtra = "";
        }
        String hiddenPath = "/" + hiddenPathExtra + '/' + remote.getName();
        int serverPort = intent.getIntExtra(SERVER_PORT, 29179);
        FLog.d(TAG, "onHandleIntent: hiddenPath=%s", hiddenPath);
        process = rclone.serve(Rclone.SERVE_PROTOCOL_HTTP, serverPort, false, null, null, remote, "", hiddenPath);
        if (process != null) {
            try {
                if(PreferenceManager.getDefaultSharedPreferences(this).
                        getBoolean(getString(R.string.pref_key_logs), false)) {
                    new Thread() {
                        @Override
                        public void run() {
                            rclone.logErrorOutput(process);
                        }
                    }.start();
                }
                process.waitFor();
            } catch (InterruptedException e) {
                FLog.e(TAG, "onHandleIntent: error waiting for process", e);
            }
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (process != null) {
            process.destroy();
        }
    }
}
