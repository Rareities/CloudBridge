package io.github.rareities.protonbridge.RemoteConfig

import android.app.Application
import android.content.Intent
import android.os.Looper
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.SearchView
import androidx.test.core.app.ApplicationProvider
import io.github.rareities.protonbridge.BuildConfig
import io.github.rareities.protonbridge.R
import io.github.rareities.protonbridge.Rclone
import io.github.rareities.protonbridge.util.ProcessRunner
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class RemoteConfigSubmissionTest {
    private val app: Application get() = ApplicationProvider.getApplicationContext()
    private val providerCache: File get() = File(app.cacheDir, "rclone.provider.${BuildConfig.VERSION_CODE}")

    @Before fun installDisposableProvider() {
        // Provider discovery otherwise launches the native binary on editor creation.
        providerCache.writeText(
            """[{"Name":"local","Options":[{"Name":"password","Type":"string","IsPassword":true}, {"Name":"description","Type":"string"}]}]"""
        )
        RemoteConfig.editConfigForTests = null
    }

    @After fun removeDisposableProvider() {
        RemoteConfig.editConfigForTests = null
        providerCache.delete()
    }

    private fun allEditors(root: View): List<EditText> = when (root) {
        is EditText -> listOf(root)
        is ViewGroup -> (0 until root.childCount).flatMap { allEditors(root.getChildAt(it)) }
        else -> emptyList()
    }

    private fun passwordEditor(fragment: DynamicRemoteConfigFragment): EditText =
        allEditors(fragment.requireView()).single {
            (it.inputType and InputType.TYPE_TEXT_VARIATION_PASSWORD) != 0
        }

    @Test(timeout = 15000)
    fun realConfiguredEditorRecreatesWithPrivateConfigAndWorkingSearchWithoutSavingPasswords() {
        val persistedSecret = "disposable-saved-password"
        val unsavedSecret = "disposable-unsaved-password"
        RemoteConfig.editConfigForTests = { name ->
            if (name == "demo") hashMapOf(
                Rclone.RCLONE_CONFIG_NAME_KEY to name,
                "type" to "local",
                "password" to persistedSecret,
                "description" to "saved description"
            ) else null
        }
        val intent = Intent(app, RemoteConfig::class.java)
            .putExtra(RemoteConfig.CONFIG_EDIT_TARGET, "demo")
        val controller = Robolectric.buildActivity(RemoteConfig::class.java, intent).setup()
        try {
            controller.get().supportFragmentManager.executePendingTransactions()
            val original = controller.get().supportFragmentManager
                .findFragmentById(R.id.flFragment) as DynamicRemoteConfigFragment
            assertTrue(original.isEditConfig())
            val arguments = original.requireArguments()
            assertEquals(3, arguments.keySet().size)
            assertTrue(arguments.keySet().all { arguments.get(it) is String || arguments.get(it) is Boolean })
            assertFalse(arguments.toString().contains(persistedSecret))

            val originalPassword = passwordEditor(original)
            assertEquals(persistedSecret, originalPassword.text.toString())
            originalPassword.setText(unsavedSecret)
            assertFalse("Credential inputs must not save their state in an Activity Bundle",
                originalPassword.isSaveEnabled)

            controller.recreate()
            controller.get().supportFragmentManager.executePendingTransactions()
            val fragments = controller.get().supportFragmentManager.fragments
                .filterIsInstance<DynamicRemoteConfigFragment>()
            assertEquals("Restoration must reuse the restored fragment", 1, fragments.size)
            val restored = fragments.single()
            assertNotSame(original, restored)
            assertTrue(restored.isEditConfig())
            assertEquals("demo", controller.get().findViewById<EditText>(R.id.remote_name).text.toString())
            assertEquals("Reload saved credentials privately; discard unsaved password edits",
                persistedSecret, passwordEditor(restored).text.toString())
            assertFalse(restored.requireArguments().toString().contains(persistedSecret))
            assertFalse(restored.requireArguments().toString().contains(unsavedSecret))

            val search = controller.get().findViewById<SearchView>(R.id.search)
            assertNotNull(search)
            search.visibility = View.VISIBLE
            search.setQuery("password", false)
            assertTrue(controller.get().onSupportNavigateUp())
            assertEquals(View.GONE, search.visibility)
            assertFalse(controller.get().isFinishing)
        } finally {
            controller.destroy()
        }
    }

    @Test(timeout = 15000)
    fun missingSavedRemoteDuringRecreationClosesEditorGracefully() {
        RemoteConfig.editConfigForTests = { name ->
            if (name == "demo") hashMapOf(
                Rclone.RCLONE_CONFIG_NAME_KEY to name, "type" to "local"
            ) else null
        }
        val intent = Intent(app, RemoteConfig::class.java)
            .putExtra(RemoteConfig.CONFIG_EDIT_TARGET, "demo")
        val controller = Robolectric.buildActivity(RemoteConfig::class.java, intent).setup()
        try {
            controller.get().supportFragmentManager.executePendingTransactions()
            assertTrue(controller.get().supportFragmentManager
                .findFragmentById(R.id.flFragment) is DynamicRemoteConfigFragment)
            RemoteConfig.editConfigForTests = { null }
            controller.recreate()
            assertTrue("A remote deleted while rotating must not crash the form",
                controller.get().isFinishing)
        } finally {
            controller.destroy()
        }
    }

    @Test(timeout = 15000)
    fun providerPickerAlsoRecreatesWithWorkingSearchAndBackNavigation() {
        val controller = Robolectric.buildActivity(RemoteConfig::class.java).setup()
        try {
            controller.get().supportFragmentManager.executePendingTransactions()
            assertTrue(controller.get().supportFragmentManager
                .findFragmentById(R.id.flFragment) is ProviderListFragment)
            controller.recreate()
            controller.get().supportFragmentManager.executePendingTransactions()
            val fragments = controller.get().supportFragmentManager.fragments
                .filterIsInstance<ProviderListFragment>()
            assertEquals(1, fragments.size)
            val search = controller.get().findViewById<SearchView>(R.id.search)
            search.visibility = View.VISIBLE
            search.setQuery("local", false)
            assertTrue(controller.get().onSupportNavigateUp())
            assertEquals(View.GONE, search.visibility)
            assertFalse(controller.get().isFinishing)
        } finally {
            controller.destroy()
        }
    }

    /** A native command that remains busy until the test finishes or cancels it. */
    private class ControlledProcess : Process() {
        private val stdout = PipedInputStream()
        private val stderr = PipedInputStream()
        private val stdoutWriter = PipedOutputStream(stdout)
        private val stderrWriter = PipedOutputStream(stderr)
        private val finished = CountDownLatch(1)
        val destroyed = CountDownLatch(1)
        @Volatile private var code: Int? = null

        override fun getOutputStream(): OutputStream = ByteArrayOutputStream()
        override fun getInputStream() = stdout
        override fun getErrorStream() = stderr
        override fun waitFor(): Int { finished.await(); return code!! }
        override fun exitValue(): Int = code ?: throw IllegalThreadStateException("Fake process is still running")
        override fun destroy() {
            destroyed.countDown()
            finish(0) // Even a zero exit after cancellation must not count as success.
        }
        @Synchronized fun finish(exitCode: Int) {
            if (code != null) return
            code = exitCode
            stdoutWriter.close()
            stderrWriter.close()
            finished.countDown()
        }
    }

    private fun editor(edit: Boolean): Pair<org.robolectric.android.controller.ActivityController<RemoteConfig>, DynamicRemoteConfigFragment> {
        val controller = Robolectric.buildActivity(RemoteConfig::class.java).setup()
        controller.get().supportFragmentManager.executePendingTransactions()
        val options = if (edit) hashMapOf(Rclone.RCLONE_CONFIG_NAME_KEY to "demo", "type" to "local") else null
        val fragment = DynamicRemoteConfigFragment("local", options)
        controller.get().supportFragmentManager.beginTransaction()
            .replace(R.id.flFragment, fragment, "disposable-config").commitNow()
        if (!edit) controller.get().findViewById<android.widget.EditText>(R.id.remote_name).setText("demo")
        return controller to fragment
    }

    private fun idleUntil(message: String, predicate: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(6)
        while (!predicate() && System.nanoTime() < deadline) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        assertTrue(message, predicate())
    }

    @Test(timeout = 15000) fun blockedEditDoesNotBlockUiOrSubmitASecondCreate() {
        val (activity, fragment) = editor(edit = true)
        val child = ControlledProcess()
        val started = CountDownLatch(1)
        val requests = AtomicInteger()
        val update = AtomicBoolean()
        val args = AtomicReference<List<String>>()
        try {
            fragment.configRunForTests = { options, isUpdate, runner, stop ->
                requests.incrementAndGet()
                update.set(isUpdate)
                args.set(options.toList())
                started.countDown()
                RemoteConfigHelper.awaitProcess(child, runner, stop, TimeUnit.SECONDS.toMillis(12))
            }
            val save = activity.get().findViewById<View>(R.id.finish)
            val began = System.nanoTime()
            save.performClick()
            save.performClick()
            assertTrue("The UI click waited for a blocked native process",
                TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - began) < 2000)
            assertFalse("A pending save must disable repeated taps", save.isEnabled)
            assertTrue(started.await(3, TimeUnit.SECONDS))
            assertTrue(update.get())
            assertEquals(listOf("demo", "local"), args.get()!!.take(2))
            assertEquals("The edit must not fall through into create", 1, requests.get())
            assertFalse(activity.get().isFinishing)

            child.finish(0)
            idleUntil("A confirmed update should close the editor") { activity.get().isFinishing }
            assertEquals(1, requests.get())
        } finally {
            child.destroy()
            activity.destroy()
        }
    }

    @Test(timeout = 15000) fun failedCreateKeepsFormAndAllowsOneRetry() {
        val (activity, fragment) = editor(edit = false)
        val first = ControlledProcess()
        val second = ControlledProcess()
        val requests = AtomicInteger()
        val sawUpdate = AtomicBoolean()
        val secondStarted = CountDownLatch(1)
        try {
            fragment.configRunForTests = { _, updating, runner, stop ->
                sawUpdate.set(updating)
                val number = requests.incrementAndGet()
                if (number == 2) secondStarted.countDown()
                RemoteConfigHelper.awaitProcess(if (number == 1) first else second, runner, stop, 12000)
            }
            val save = activity.get().findViewById<View>(R.id.finish)
            save.performClick()
            idleUntil("The first create should have started") { requests.get() == 1 }
            assertFalse("A new remote must not take the update branch", sawUpdate.get())
            first.finish(7)
            idleUntil("A failed create must restore the submit button") { save.isEnabled }
            assertFalse("A failed process must not close the editor", activity.get().isFinishing)
            assertNotNull("The form must remain available for corrections", fragment.view)

            save.performClick()
            assertTrue(secondStarted.await(3, TimeUnit.SECONDS))
            assertEquals(2, requests.get())
            second.finish(0)
            idleUntil("A later confirmed create should close the editor") { activity.get().isFinishing }
        } finally {
            first.destroy()
            second.destroy()
            activity.destroy()
        }
    }

    @Test(timeout = 15000) fun destroyingTheFormCancelsTheNativeProcessWithoutFalseSuccess() {
        val (activity, fragment) = editor(edit = true)
        val child = ControlledProcess()
        val started = CountDownLatch(1)
        val stopped = CountDownLatch(1)
        val result = AtomicReference<RemoteConfigHelper.Result>()
        try {
            fragment.configRunForTests = { _, _, runner, stop ->
                started.countDown()
                RemoteConfigHelper.awaitProcess(child, runner, stop, 12000).also {
                    result.set(it)
                    stopped.countDown()
                }
            }
            activity.get().findViewById<View>(R.id.finish).performClick()
            assertTrue(started.await(3, TimeUnit.SECONDS))
            activity.get().supportFragmentManager.beginTransaction().remove(fragment).commitNow()
            assertTrue("Closing the form must stop the child", child.destroyed.await(4, TimeUnit.SECONDS))
            assertTrue("Cancellation must reach a terminal result", stopped.await(4, TimeUnit.SECONDS))
            assertFalse("A cancelled zero-exit process is never a success", result.get()!!.success)
            assertFalse("Closing a view must not falsely finish the activity", activity.get().isFinishing)
        } finally {
            child.destroy()
            activity.destroy()
        }
    }

    @Test(timeout = 5000) fun nativeTimeoutCannotBeReportedAsACompletedSave() {
        val child = ControlledProcess()
        try {
            val result = RemoteConfigHelper.awaitProcess(child, ProcessRunner(), { false }, 150)
            assertTrue(result.timedOut)
            assertFalse(result.success)
            assertTrue(child.destroyed.await(1, TimeUnit.SECONDS))
        } finally {
            child.destroy()
        }
    }
}
