package io.github.rareities.protonbridge.Services;

import android.app.IntentService;
import android.content.Intent;
import androidx.annotation.Nullable;
import io.github.rareities.protonbridge.Items.RemoteItem;
import io.github.rareities.protonbridge.Rclone;
import io.github.rareities.protonbridge.util.FLog;

public class ThumbnailsLoadingService extends IntentService {

    private static final String TAG = "ThumbnailsLoadingSvc";
    public static final String REMOTE_ARG = "io.github.rareities.protonbridge.ThumbnailsLoadingService.REMOTE_ARG";
    public static final String HIDDEN_PATH = "io.github.rareities.protonbridge.ThumbnailsLoadingService.HIDDEN_PATH";
    public static final String SERVER_PORT = "io.github.rareities.protonbridge.ThumbnailsLoadingService.PORT";

    private Rclone rclone;
    private volatile Process process;
    private volatile io.github.rareities.protonbridge.util.NativePipeTransfer transfer;
    private volatile boolean stopping;

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
        if (stopping) return; // Never log the private thumbnail endpoint.
        process = rclone.serve(Rclone.SERVE_PROTOCOL_HTTP, serverPort, false, null, null, remote, "", hiddenPath);
        if (process != null) {
            // Always drain both pipes, even when file logging is disabled.
            io.github.rareities.protonbridge.util.NativePipeTransfer owned =
                    new io.github.rareities.protonbridge.util.NativePipeTransfer(process, true);
            transfer = owned;
            if (stopping) owned.cancel();
            try {
                owned.output().close();
                owned.awaitSuccessfulExit();
            } catch (java.io.IOException failure) {
                FLog.w(TAG, "Thumbnail listener stopped");
            } finally { owned.cancel(); }
        }
    }

    @Override
    public void onDestroy() {
        stopping = true;
        io.github.rareities.protonbridge.util.NativePipeTransfer owned = transfer;
        if (owned != null) owned.cancel();
        // A child created concurrently observes stopping before its wait begins.
        super.onDestroy();
    }
}
