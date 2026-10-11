package ca.pkay.rcloneexplorer.util;

import org.junit.Test;
import java.io.File;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

public class ProcessRunnerTest {
    private Process child(String mode) throws Exception {
        File java = new File(System.getProperty("java.home"), "bin/java" + (File.separatorChar == '\\' ? ".exe" : ""));
        String classes = new File(Child.class.getProtectionDomain().getCodeSource().getLocation().toURI()).getAbsolutePath();
        return new ProcessBuilder(java.getAbsolutePath(), "-cp", classes, Child.class.getName(), mode).start();
    }
    @Test(timeout=20000) public void drainsBothFullPipesWithoutDeadlock() throws Exception {
        AtomicInteger stdout = new AtomicInteger(), stderr = new AtomicInteger();
        ProcessRunner.Outcome result = new ProcessRunner().run(child("flood"), () -> false,
                line -> stdout.incrementAndGet(), line -> stderr.incrementAndGet());
        assertTrue(result.success());
        assertEquals(16000, stdout.get());
        assertEquals(16000, stderr.get());
    }
    @Test(timeout=10000) public void nonzeroExitWithoutErrorLogIsFailure() throws Exception {
        ProcessRunner.Outcome result = new ProcessRunner().run(child("fail"), () -> false, null, null);
        assertEquals(Integer.valueOf(7), result.exitCode);
        assertFalse(result.success());
    }
    @Test(timeout=10000) public void warningLogDoesNotOverrideSuccessfulExit() throws Exception {
        ProcessRunner.Outcome result = new ProcessRunner().run(child("warning"), () -> false, null, null);
        assertTrue(result.success());
    }
    @Test(timeout=20000) public void cancellationCannotReturnSuccess() throws Exception {
        ProcessRunner runner = new ProcessRunner();
        long started = System.nanoTime();
        ProcessRunner.Outcome result = runner.run(child("sleep"), () -> System.nanoTime() - started > 300_000_000L, null, null);
        assertTrue(result.cancelled);
        assertTrue(result.exited);
        assertFalse(result.success());
    }
    @Test(timeout=20000) public void silentLocalCheckIsStoppedAtDeadline() throws Exception {
        ProcessRunner.Outcome result = new ProcessRunner().run(child("sleep"), () -> false, null, null, 200L);
        assertTrue(result.timedOut);
        assertTrue(result.exited);
        assertFalse(result.success());
    }
    @Test(timeout=10000) public void deadlineDoesNotTurnNormalExitIntoTimeout() throws Exception {
        ProcessRunner.Outcome result = new ProcessRunner().run(child("warning"), () -> false, null, null, 5000L);
        assertTrue(result.success());
        assertFalse(result.timedOut);
    }
    @Test public void missingProcessIsNeverSuccess() {
        assertFalse(new ProcessRunner().run(null, () -> false, null, null).success());
    }
    public static class Child {
        public static void main(String[] args) throws Exception {
            switch (args[0]) {
                case "flood":
                    String row = "abcdefghijklmnopqrstuvwxyz".repeat(12);
                    for (int i = 0; i < 16000; i++) { System.out.println(row); System.err.println(row); }
                    break;
                case "fail": System.exit(7); break;
                case "warning": System.err.println("warning: advisory only"); break;
                case "sleep": Thread.sleep(120000L); break;
                default: throw new IllegalArgumentException("Unknown fixture");
            }
        }
    }
}
