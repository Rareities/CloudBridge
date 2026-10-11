package ca.pkay.rcloneexplorer.util

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import ca.pkay.rcloneexplorer.Items.Task
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class BisyncStateTest {
    private fun store() = BisyncStateStore(File(ApplicationProvider.getApplicationContext<Application>().filesDir, UUID.randomUUID().toString()))

    @Test fun initialisationRequiresACompletedMatchingPreview() {
        val state = store()
        assertThrows(IllegalArgumentException::class.java) { state.begin("run", "one", null, "run") }
        assertThrows(IllegalArgumentException::class.java) { state.begin("initialise", "one", "one", "init") }
        state.begin("preview", "one", null, "preview")
        state.finish("preview", "one", "preview", true, 0, "preview complete")
        assertThrows(IllegalArgumentException::class.java) { state.begin("initialise", "changed", "one", "init") }
        state.begin("initialise", "one", "one", "init")
        state.finish("initialise", "one", "init", true, 0, "success")
        assertEquals(BisyncStateStore.READY, state.read().getString("status"))
    }

    @Test fun interruptedAndFailedRunsCannotAutomaticallyReuseBaseline() {
        val state = store()
        state.begin("preview", "one", null, "preview")
        state.finish("preview", "one", "preview", true, 0, "preview complete")
        state.begin("initialise", "one", "one", "init")
        state.finish("initialise", "one", "init", true, 0, "success")
        state.begin("run", "one", null, "run")
        assertThrows(IllegalArgumentException::class.java) { state.begin("run", "one", null, "after-crash") }
        state.finish("run", "one", "run", false, 7, "cancelled")
        assertEquals(BisyncStateStore.NEEDS_RECOVERY, state.read().getString("status"))
        assertThrows(IllegalArgumentException::class.java) { state.begin("run", "one", null, "retry") }
    }

    @Test fun staleCompletionCannotPromoteAnotherRunsState() {
        val state = store()
        state.begin("preview", "one", null, "current")
        state.finish("preview", "one", "old-run", true, 0, "stale success")
        assertEquals("PREVIEWING", state.read().getString("status"))
    }

    @Test fun identityIncludesEngineConfigurationOptionsAndOrderedPaths() {
        val task = Task(4).apply {
            bisyncUUID = UUID.randomUUID().toString()
            localPath = File(ApplicationProvider.getApplicationContext<Application>().filesDir, "local").absolutePath
            remoteId = "proton"
            remotePath = "folder"
        }
        val initial = BisyncPolicy.fingerprint(task, emptyList(), "account-generation-a")
        assertNotEquals(initial, BisyncPolicy.fingerprint(task, emptyList(), "account-generation-b"))
        task.bisyncAdvancedOptions = "--bwlimit=4M"
        assertNotEquals(initial, BisyncPolicy.fingerprint(task, emptyList(), "account-generation-a"))
        assertFalse(BisyncPolicy.overlaps("/a/b", "/a/b2"))
        assertTrue(BisyncPolicy.overlaps("/a/b", "/a/b/c"))
    }

    @Test fun changingTransferModeRotatesIdentityButLegacyBisyncModesShareOneBaseline() {
        val original = Task(17).apply {
            direction = 5
            bisyncUUID = UUID.randomUUID().toString()
            localPath = File(ApplicationProvider.getApplicationContext<Application>().filesDir, "local").absolutePath
            remoteId = "proton"
            remotePath = "data"
        }
        assertEquals(original.bisyncUUID, BisyncPolicy.identityForEdit(original, 6))
        assertNotEquals(original.bisyncUUID, BisyncPolicy.identityForEdit(original, 1))
        val oneWay = Task(original.id).apply { direction = 1; bisyncUUID = original.bisyncUUID }
        assertEquals(original.bisyncUUID, BisyncPolicy.identityForEdit(oneWay, 2))
        assertNotEquals(original.bisyncUUID, BisyncPolicy.identityForEdit(oneWay, 6))
        val bisync = BisyncPolicy.fingerprint(original, emptyList(), "same-config")
        original.direction = 6
        assertEquals(bisync, BisyncPolicy.fingerprint(original, emptyList(), "same-config"))
        original.direction = 1
        assertNotEquals(bisync, BisyncPolicy.fingerprint(original, emptyList(), "same-config"))
    }

    @Test fun expiredOrMismatchedPreviewNeverAuthorisesInitialisation() {
        val store = store()
        store.begin(BisyncStateStore.PREVIEW, "first", null, "preview")
        store.finish(BisyncStateStore.PREVIEW, "first", "preview", true, 0, "ok")
        val state = store.read()
        val at = state.getLong("previewAt")
        assertTrue(BisyncStateStore.isPreviewApproved(state, "first", at + BisyncStateStore.PREVIEW_LIFETIME_MS))
        assertFalse(BisyncStateStore.isPreviewApproved(state, "first", at + BisyncStateStore.PREVIEW_LIFETIME_MS + 1))
        assertFalse(BisyncStateStore.isPreviewApproved(state, "edited", at))
        store.begin(BisyncStateStore.INITIALISE, "first", "first", "init")
        store.finish(BisyncStateStore.INITIALISE, "first", "init", true, 0, "ok")
        assertTrue(BisyncStateStore.isReady(store.read(), "first"))
        assertFalse(BisyncStateStore.isReady(store.read(), "edited"))
    }
}
