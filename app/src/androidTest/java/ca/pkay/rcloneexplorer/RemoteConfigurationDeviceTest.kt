package ca.pkay.rcloneexplorer

import android.content.Intent
import android.os.Build
import android.widget.EditText
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import ca.pkay.rcloneexplorer.RemoteConfig.DynamicRemoteConfigFragment
import ca.pkay.rcloneexplorer.RemoteConfig.RemoteConfig
import ca.pkay.rcloneexplorer.util.NativeConfigEncryption
import ca.pkay.rcloneexplorer.util.NativeSessionGate
import ca.pkay.rcloneexplorer.util.ProcessRunner
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit

/** Exercises real remote setup/edit and recreation with a disposable local backend. */
@RunWith(AndroidJUnit4::class)
class RemoteConfigurationDeviceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    private fun await(label: String, ready: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
        while (System.nanoTime() < deadline) {
            if (ready()) return
            Thread.sleep(100)
        }
        assertTrue("Timed out: $label", ready())
    }

    @Test(timeout = 120000)
    fun createEditAndRecreateUsingTheRealProtectedConfiguration() {
        assumeTrue(context.packageName.endsWith(".debug") && Build.VERSION.SDK_INT == 35 &&
            (Build.HARDWARE.contains("ranchu") || Build.HARDWARE.contains("goldfish")))
        val remote = "gui_config_" + UUID.randomUUID().toString().replace("-", "")
        val rclone = Rclone(context)
        var attempted = false
        try {
            await("previous native work to exit") { !NativeSessionGate.GLOBAL.isBusy }
            rclone.prepareProtectedConfig()
            val provider = rclone.getProvider("local")
            ActivityScenario.launch<RemoteConfig>(Intent(context, RemoteConfig::class.java)).use { screen ->
                var activity: RemoteConfig? = null
                screen.onActivity { activity = it; it.onProviderSelected(provider) }
                instrumentation.waitForIdleSync()
                screen.onActivity {
                    it.findViewById<EditText>(R.id.remote_name).setText(remote)
                    assertFalse(it.isFinishing)
                }
                attempted = true
                onView(withId(R.id.finish)).perform(click())
                await("successful native create to close the form") { activity?.isFinishing == true }
            }
            await("configuration process ownership to close") { !NativeSessionGate.GLOBAL.isBusy }
            assertEquals("local", requireNotNull(rclone.getConfig(remote))["type"])
            assertTrue(NativeConfigEncryption.isEncrypted(File(context.filesDir, "rclone.conf")))

            ActivityScenario.launch<RemoteConfig>(Intent(context, RemoteConfig::class.java)
                .putExtra(RemoteConfig.CONFIG_EDIT_TARGET, remote)).use { screen ->
                screen.recreate()
                var activity: RemoteConfig? = null
                screen.onActivity {
                    activity = it
                    assertEquals(remote, it.findViewById<EditText>(R.id.remote_name).text.toString())
                    assertTrue(it.supportFragmentManager.findFragmentById(R.id.flFragment) is DynamicRemoteConfigFragment)
                }
                onView(withId(R.id.finish)).perform(click())
                await("successful native edit after recreation") { activity?.isFinishing == true }
            }
            await("edited native configuration to settle") { !NativeSessionGate.GLOBAL.isBusy }
            assertEquals("local", requireNotNull(rclone.getConfig(remote))["type"])
        } finally {
            if (attempted) {
                await("native ownership before fixture cleanup") { !NativeSessionGate.GLOBAL.isBusy }
                val started = System.nanoTime()
                val result = ProcessRunner().run(rclone.config("delete", mutableListOf(remote)),
                    { System.nanoTime() - started > TimeUnit.SECONDS.toNanos(20) }, null, null)
                assertTrue("Could not remove the disposable remote", result.success())
            }
        }
    }
}
