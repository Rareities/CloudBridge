package ca.pkay.rcloneexplorer.RemoteConfig;

import android.content.Context;
import android.net.Uri;
import androidx.annotation.NonNull;
import androidx.browser.customtabs.CustomTabsIntent;
import ca.pkay.rcloneexplorer.InteractiveRunner;
import ca.pkay.rcloneexplorer.R;
import ca.pkay.rcloneexplorer.Rclone;
import ca.pkay.rcloneexplorer.util.FLog;
import ca.pkay.rcloneexplorer.util.NativeExecutionHandle;

import java.util.ArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Provides utility methods for authorization of OAuth remotes
 */
public class OauthHelper {

    private static final String TAG = "OAuthHelper";
    private static final String regex = "go to the following link: ([^\\s]+)";
    private static final Pattern authUrlPattern = Pattern.compile(regex, 0);
    private static final OauthProcessToken oauthProcessToken = new OauthProcessToken();

    // Since OAuth always blocks port 53682, only a single authentication
    // attempt is allowed at a time.
    public interface ExecutionFactory {
        NativeExecutionHandle launch();
    }

    static class OauthProcessToken {

        private NativeExecutionHandle execution;
        private InteractiveRunner runner;

        synchronized NativeExecutionHandle startAttempt(ExecutionFactory factory) {
            if (!forceRelease() || Thread.currentThread().isInterrupted()) {
                return null;
            }
            NativeExecutionHandle next = factory.launch();
            if (next != null) {
                execution = next;
                runner = null;
            }
            return next;
        }

        synchronized boolean registerRunner(InteractiveRunner candidate,
                                            NativeExecutionHandle candidateExecution) {
            if (execution != candidateExecution) {
                candidate.stopAndAwait();
                return false;
            }
            runner = candidate;
            return true;
        }

        synchronized boolean forceRelease() {
            NativeExecutionHandle current = execution;
            InteractiveRunner currentRunner = runner;
            if (current == null) {
                runner = null;
                return true;
            }

            boolean stopped;
            if (currentRunner != null) {
                stopped = currentRunner.stopAndAwait();
            } else {
                NativeExecutionHandle.Outcome outcome = current.cancelAndAwait(null, null);
                stopped = (outcome.isConfirmed() || current.hasConfirmedReap())
                        && current.hasConfirmedReap();
            }

            if (!stopped || !current.hasConfirmedReap()) {
                return false;
            }
            execution = null;
            runner = null;
            return true;
        }

        synchronized void release(NativeExecutionHandle completed) {
            if (execution == completed && completed.hasConfirmedReap()) {
                execution = null;
                runner = null;
            }
        }
    }

    /**
     * Ensure that an OAuth attempt can be made.
     */
    public static NativeExecutionHandle startAttempt(ExecutionFactory factory) {
        return oauthProcessToken.startAttempt(factory);
    }

    public static boolean registerRunner(InteractiveRunner runner,
                                         NativeExecutionHandle execution) {
        return oauthProcessToken.registerRunner(runner, execution);
    }

    public static void release(NativeExecutionHandle execution) {
        oauthProcessToken.release(execution);
    }

    /**
     * Save the options in the rclone config file and start the OAuth authentication process
     * @param options a list of rclone options, starting with remote name and type
     * @param rclone the rclone to use
     * @param context a context to start
     * @return true if successful
     **/
    public static boolean createOptionsWithOauth(ArrayList<String> options, Rclone rclone, Context context) {
        // Reserve the fixed OAuth port only after the previous owner has been stopped and reaped.
        NativeExecutionHandle execution = oauthProcessToken.startAttempt(
                () -> rclone.configCreateOwned(options));
        if (execution == null) {
            return false;
        }
        AtomicBoolean browserLaunched = new AtomicBoolean(false);
        try {
            NativeExecutionHandle.Outcome outcome = execution.await(
                    NativeExecutionHandle.NO_TIMEOUT, null, line -> {
                        Matcher matcher = authUrlPattern.matcher(line);
                        if (matcher.find() && browserLaunched.compareAndSet(false, true)) {
                            String url = matcher.group(1);
                            if (url != null) {
                                launchBrowser(context.getApplicationContext(), url);
                            }
                        }
                    });
            return outcome.isSuccess();
        } finally {
            if (!execution.hasConfirmedReap()) {
                execution.cancelAndAwait(null, null);
            }
            oauthProcessToken.release(execution);
        }
    }

    /**
     * Monitor a rclone process for an authentication url and launch a browser
     * tab for the user. Note: this consumes the processes InputStream (stdout).
     */
    static void launchBrowser(@NonNull Context context, @NonNull String url) {
        CustomTabsIntent.Builder builder = new CustomTabsIntent.Builder();
        CustomTabsIntent customTabsIntent = builder.build();
        try {
            customTabsIntent.launchUrl(context, Uri.parse(url));
        } catch (SecurityException e) {
            // This happens if a buggy third party component is registered for
            // browser intents with a non-exported activity.
            // TODO: Fix this for Android TV
            FLog.e(TAG, "Could not launch browser", e);
        }
    }

    private static class OauthAction implements InteractiveRunner.Action {

        private static final Pattern pattern = Pattern.compile(regex, 0);
        private Context context;

        public OauthAction(Context context) {
            this.context = context;
        }

        @Override
        public void onTrigger(String cliBuffer) {
            Matcher matcher = pattern.matcher(cliBuffer);
            if (matcher.find()) {
                String url = matcher.group(1);
                if (url != null) {
                    launchBrowser(context, url);
                }
            } else {
                FLog.w(TAG, "OAuth prompt did not contain a usable authorization URL");
            }
        }

        @Override
        public String getInput() {
            return "";
        }
    }

    public static class InitOauthStep extends InteractiveRunner.Step {
        private static final String TRIGGER = "Log in and authorize rclone for access";

        /**
         * An OAuth step that launches a browser. ATTENTION: must be registered
         * with {@link OauthHelper} to allow removal in case the port is needed.
         * @param context
         */
        public InitOauthStep(Context context) {
            super(TRIGGER,  new OauthHelper.OauthAction(context));
        }
    }

    public static class OauthFinishStep extends InteractiveRunner.Step {

        private static final String TRIGGER = "Got code\n";

        public OauthFinishStep() {
            super(TRIGGER, InteractiveRunner.Step.ENDS_WITH, InteractiveRunner.Step.STDOUT,
                    new InteractiveRunner.StringAction(""));
        }

        @Override
        public long getTimeout() {
            return 5 * 60 * 1000L;
        }
    }

    /**
     * An action that shows a dialog promp for Internxt 2FA code.
     * Uses a CountDownLatch to block until user enters the code.
     */
    public static class InternxtTwoFactorAction implements InteractiveRunner.Action {
        private final Context context;
        private String twoFactorCode = "";
        private final java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);

        public InternxtTwoFactorAction(Context context) {
            this.context = context;
        }

        @Override
        public void onTrigger(String cliBuffer) {
            // Show dialog on UI thread and wait for input
            android.os.Handler mainHandler = new android.os.Handler(android.os.Looper.getMainLooper());
            mainHandler.post(() -> showTwoFactorDialog());

            // Wait for user to enter the code (timeout after 5 minutes)
            try {
                latch.await(5, java.util.concurrent.TimeUnit.MINUTES);
            } catch (InterruptedException e) {
                FLog.e(TAG, "2FA wait interrupted", e);
            }
        }

        private void showTwoFactorDialog() {
            androidx.appcompat.app.AlertDialog.Builder builder = 
                new androidx.appcompat.app.AlertDialog.Builder(context);
            builder.setTitle(context.getString(R.string.internxt_2fa_title));
            builder.setMessage(context.getString(R.string.internxt_2fa_message));

            final android.widget.EditText input = new android.widget.EditText(context);
            input.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
            input.setHint("6-digit code");
            
            android.widget.LinearLayout layout = new android.widget.LinearLayout(context);
            layout.setOrientation(android.widget.LinearLayout.VERTICAL);
            int padding = (int) (16 * context.getResources().getDisplayMetrics().density);
            layout.setPadding(padding, padding, padding, 0);
            layout.addView(input);
            builder.setView(layout);

            builder.setPositiveButton("Submit", (dialog, which) -> {
                twoFactorCode = input.getText().toString().trim();
                latch.countDown();
            });
            
            builder.setNegativeButton("Cancel", (dialog, which) -> {
                twoFactorCode = "";
                latch.countDown();
            });
            
            builder.setCancelable(false);
            builder.show();
        }

        @Override
        public String getInput() {
            return twoFactorCode;
        }
    }

    /**
     * A step that triggers on Internxt 2FA prompt and shows a dialog for code input.
     * Trigger pattern matches exact text from rclone internxt.go source.
     */
    public static class InternxtTwoFactorStep extends InteractiveRunner.Step {
        // Exact prompt from rclone internxt.go: fs.ConfigInput("2fa", "config_2fa", "Two-factor authentication code")
        private static final String TRIGGER = "Two-factor authentication code";

        public InternxtTwoFactorStep(Context context) {
            super(TRIGGER, InteractiveRunner.Step.CONTAINS, InteractiveRunner.Step.INTERLEAVED,
                    new InternxtTwoFactorAction(context));
        }

        @Override
        public long getTimeout() {
            // Wait up to 2 minutes for the 2FA prompt to appear
            return 2 * 60 * 1000L;
        }
    }

    /**
     * A step for Internxt that just waits for the config to complete.
     * Triggers when we see successful completion indicators.
     */
    public static class InternxtFinishStep extends InteractiveRunner.Step {
        private static final String TRIGGER = "Keep this";

        public InternxtFinishStep() {
            super(TRIGGER, InteractiveRunner.Step.CONTAINS, InteractiveRunner.Step.INTERLEAVED,
                    new InteractiveRunner.StringAction("y"));
        }

        @Override
        public long getTimeout() {
            // Wait up to 2 minutes for login to complete
            return 2 * 60 * 1000L;
        }
    }
}
