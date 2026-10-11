package ca.pkay.rcloneexplorer.RemoteConfig;

import android.app.Application;
import androidx.test.core.app.ApplicationProvider;
import ca.pkay.rcloneexplorer.Rclone;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, application = Application.class)
public class OauthProcessTest {
    private static final class Child extends Process {
        final CountDownLatch destroyed = new CountDownLatch(1);
        final CountDownLatch exit;
        final AtomicBoolean outClosed = new AtomicBoolean(), errClosed = new AtomicBoolean();
        final int status;
        final String errorText;
        Child(int status, boolean running) { this(status, running, ""); }
        Child(int status, boolean running, String errorText) {
            this.status = status; this.errorText = errorText;
            exit = new CountDownLatch(running ? 1 : 0);
        }
        private InputStream pipe(AtomicBoolean closed) {
            return new ByteArrayInputStream(new byte[131072]) {
                @Override public void close() { closed.set(true); }
            };
        }
        @Override public InputStream getInputStream() { return pipe(outClosed); }
        @Override public InputStream getErrorStream() {
            return errorText.isEmpty() ? pipe(errClosed) : new ByteArrayInputStream(
                    errorText.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
        @Override public OutputStream getOutputStream() { return new ByteArrayOutputStream(); }
        @Override public int waitFor() throws InterruptedException { exit.await(); return status; }
        @Override public int exitValue() {
            if (exit.getCount() > 0) throw new IllegalThreadStateException();
            return status;
        }
        @Override public void destroy() { destroyed.countDown(); exit.countDown(); }
    }

    @Test public void oauthOutcomeRequiresExitAndBothPipesAreClosed() {
        Application app = ApplicationProvider.getApplicationContext();
        for (int status : new int[]{0, 7}) {
            Child child = new Child(status, false);
            Rclone rclone = new Rclone(app) {
                @Override public Process configCreate(List<String> options) { return child; }
            };
            assertEquals(status == 0, OauthHelper.createOptionsWithOauth(new ArrayList<>(), rclone, app));
            assertTrue(child.outClosed.get());
            assertTrue(child.errClosed.get());
        }
    }

    @Test(timeout = 10000) public void cancelledOauthThreadStopsItsChild() throws Exception {
        Child child = new Child(0, true);
        OauthHelper.UrlAuthThread auth = new OauthHelper.UrlAuthThread(child, ApplicationProvider.getApplicationContext());
        auth.start();
        try {
            auth.forceStop();
            auth.join(5000);
            assertFalse(auth.isAlive());
            assertTrue(child.destroyed.await(1, TimeUnit.SECONDS));
            assertTrue(auth.isStopped());
        } finally { child.destroy(); auth.interrupt(); auth.join(2000); }
    }

    @Test(timeout = 10000) public void cancelledAttemptCannotOpenABufferedAuthenticationUrl() throws Exception {
        Application app = ApplicationProvider.getApplicationContext();
        Child child = new Child(0, true,
                "go to the following link: http://127.0.0.1/authorize?state=disposable\n");
        OauthHelper.UrlAuthThread auth = new OauthHelper.UrlAuthThread(child, app);
        auth.forceStop();
        auth.start();
        try {
            auth.join(5000);
            assertFalse(auth.isAlive());
            assertNull(Shadows.shadowOf(app).getNextStartedActivity());
        } finally { child.destroy(); auth.interrupt(); auth.join(2000); }
    }
}
