package ca.pkay.rcloneexplorer.RemoteConfig;

import android.content.Context;
import android.os.Environment;
import android.widget.Toast;
import ca.pkay.rcloneexplorer.Items.RemoteItem;
import ca.pkay.rcloneexplorer.R;
import ca.pkay.rcloneexplorer.Rclone;
import ca.pkay.rcloneexplorer.util.FLog;
import ca.pkay.rcloneexplorer.util.NativeExecutionHandle;
import es.dmoral.toasty.Toasty;
import io.github.x0b.safdav.SafAccessProvider;
import io.github.x0b.safdav.file.SafConstants;

import java.util.ArrayList;

public class RemoteConfigHelper {

    public static String getRemotePath(String path, RemoteItem selectedRemote) {
        String remotePath;
        if (selectedRemote.isRemoteType(RemoteItem.LOCAL)) {
            if (path.equals("//" + selectedRemote.getName())) {
                remotePath = Environment.getExternalStorageDirectory().getAbsolutePath() + "/";
            } else {
                remotePath = Environment.getExternalStorageDirectory().getAbsolutePath() + "/" + path;
            }
        } else {
            if (path.equals("//" + selectedRemote.getName())) {
                remotePath = selectedRemote.getName() + ":";
            } else {
                remotePath = selectedRemote.getName() + ":" + path;
            }
        }
        return remotePath;
    }

    public static void updateAndWait(Context context, ArrayList<String> options) {
        Rclone rclone = new Rclone(context);
        NativeExecutionHandle execution = rclone.configUpdateOwned(options);
        rcloneRun(execution, context);
    }

    public static void setupAndWait(Context context, ArrayList<String> options) {
        Rclone rclone = new Rclone(context);
        NativeExecutionHandle execution = rclone.configCreateOwned(options);
        rcloneRun(execution, context);
    }

    private static void rcloneRun(NativeExecutionHandle execution, Context context) {
        if (execution == null) {
            Toasty.error(context, context.getString(R.string.error_creating_remote), Toast.LENGTH_SHORT, true).show();
            return;
        }

        NativeExecutionHandle.Outcome outcome = execution.await(2 * 60 * 1000L, null, null);
        if (!outcome.isSuccess()) {
            FLog.e("RemoteConfigHelper", "rclone config ended with state: %s", outcome.getState());
            Toasty.error(context, context.getString(R.string.error_creating_remote), Toast.LENGTH_SHORT, true).show();
        } else {
            Toasty.success(context, context.getString(R.string.remote_creation_success), Toast.LENGTH_SHORT, true).show();
        }
    }

    public static void enableSaf(Context context) {
        String user = SafAccessProvider.getUser(context);
        String pass = SafAccessProvider.getPassword(context);
        ArrayList<String> options = new ArrayList<>();
        options.add(SafConstants.SAF_REMOTE_NAME);
        options.add("webdav");
        options.add("url");
        options.add(SafConstants.SAF_REMOTE_URL);
        options.add("user");
        options.add(user);
        options.add("pass");
        options.add(pass);
        setupAndWait(context, options);
    }
}
