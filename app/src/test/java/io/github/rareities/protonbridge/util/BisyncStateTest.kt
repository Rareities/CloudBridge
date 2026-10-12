package io.github.rareities.protonbridge.util

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import io.github.rareities.protonbridge.Items.Task
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
        assertThrows(IllegalArgumentException::class.java) { state.begin("initialise", "one", "one", "00000000-0000-0000-0000-000000000001") }
        state.begin("preview", "one", null, "preview")
        state.finish("preview", "one", "preview", true, 0, "preview complete")
        assertThrows(IllegalArgumentException::class.java) { state.begin("initialise", "changed", "one", "00000000-0000-0000-0000-000000000001") }
        state.begin("initialise", "one", "one", "00000000-0000-0000-0000-000000000001")
        state.finish("initialise", "one", "00000000-0000-0000-0000-000000000001", true, 0, "success")
        assertEquals(BisyncStateStore.READY, state.read().getString("status"))
    }

    @Test fun interruptedAndFailedRunsCannotAutomaticallyReuseBaseline() {
        val state = store()
        state.begin("preview", "one", null, "preview")
        state.finish("preview", "one", "preview", true, 0, "preview complete")
        state.begin("initialise", "one", "one", "00000000-0000-0000-0000-000000000001")
        state.finish("initialise", "one", "00000000-0000-0000-0000-000000000001", true, 0, "success")
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
        store.begin(BisyncStateStore.INITIALISE, "first", "first", "00000000-0000-0000-0000-000000000001")
        store.finish(BisyncStateStore.INITIALISE, "first", "00000000-0000-0000-0000-000000000001", true, 0, "ok")
        assertTrue(BisyncStateStore.isReady(store.read(), "first"))
        assertFalse(BisyncStateStore.isReady(store.read(), "edited"))
    }

    @Test fun advisoryPreviewKeepsAnExistingBaselineAndDoesNotRequireResync() {
        val store = store()
        store.begin(BisyncStateStore.PREVIEW, "original", null, "preview-initial")
        store.finish(BisyncStateStore.PREVIEW, "original", "preview-initial", true, 0, "ok")
        store.begin(BisyncStateStore.INITIALISE, "original", "original", "00000000-0000-0000-0000-000000000002")
        store.finish(BisyncStateStore.INITIALISE, "original", "00000000-0000-0000-0000-000000000002", true, 0, "ok")
        val before = store.read()
        assertTrue(BisyncStateStore.isAdvisoryPreview(before, "original"))
        assertFalse(BisyncStateStore.isAdvisoryPreview(before, "edited"))
        store.begin(BisyncStateStore.PREVIEW, "original", null, "advisory")
        store.finish(BisyncStateStore.PREVIEW, "original", "advisory", true, 0, "no changes")
        assertTrue(BisyncStateStore.isReady(store.read(), "original"))
        assertEquals("00000000-0000-0000-0000-000000000002", store.read().optString("baselineId"))
        assertFalse(BisyncStateStore.isPreviewApproved(store.read(), "original"))
        store.begin(BisyncStateStore.PREVIEW, "original", null, "advisory-failed")
        store.finish(BisyncStateStore.PREVIEW, "original", "advisory-failed", false, 3, "connection lost")
        assertTrue(BisyncStateStore.isReady(store.read(), "original"))
    }

    @Test fun changedOptionsAfterReadyRequireANewExplicitApproval() {
        val store = store()
        store.begin(BisyncStateStore.PREVIEW, "old", null, "p1")
        store.finish(BisyncStateStore.PREVIEW, "old", "p1", true, 0, "ok")
        store.begin(BisyncStateStore.INITIALISE, "old", "old", "00000000-0000-0000-0000-000000000003")
        store.finish(BisyncStateStore.INITIALISE, "old", "00000000-0000-0000-0000-000000000003", true, 0, "ok")
        store.begin(BisyncStateStore.PREVIEW, "edited", null, "p2")
        store.finish(BisyncStateStore.PREVIEW, "edited", "p2", true, 0, "ok")
        assertFalse(BisyncStateStore.isReady(store.read(), "old"))
        assertTrue(BisyncStateStore.isPreviewApproved(store.read(), "edited"))
        assertThrows(IllegalArgumentException::class.java) { store.begin(BisyncStateStore.RUN, "old", null, "run") }
    }

    @Test fun firstRunReportsInitialisationInsteadOfParsingAnEmptyUuid() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            BisyncStateStore.baselineForRun(store().read(), "current")
        }
        assertTrue(error.message!!.contains("Preview"))
        assertTrue(error.message!!.contains("Initialise"))
        assertFalse(error.message!!.contains("Invalid UUID"))
    }

    @Test fun malformedOrMissingBaselineCannotEnableRunOrAdvisoryPreview() {
        for (baseline in listOf("", "broken", "1-1-1-1-1", "../escape")) {
            val state = org.json.JSONObject().put("status", BisyncStateStore.READY)
                .put("fingerprint", "current").put("baselineId", baseline)
            assertFalse(BisyncStateStore.isReady(state, "current"))
            assertFalse(BisyncStateStore.isAdvisoryPreview(state, "current"))
            assertThrows(IllegalArgumentException::class.java) {
                BisyncStateStore.baselineForRun(state, "current")
            }
        }
    }

    @Test fun savingRepairsMalformedTaskIdentityAndInvalidWorkdirHasAnActionableError() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val task = Task(7).apply { direction = 6; bisyncUUID = "bad uuid" }
        val error = assertThrows(IllegalArgumentException::class.java) { BisyncPolicy.workdir(context, task) }
        assertTrue(error.message!!.contains("save"))
        assertFalse(error.message!!.contains("Invalid UUID"))
        val repaired = BisyncPolicy.identityForEdit(task, 6)
        assertTrue(BisyncPolicy.isValidIdentity(repaired))
        task.bisyncUUID = repaired
        assertEquals(repaired, BisyncPolicy.identityForEdit(task, 6))
    }
    @Test fun disposablePreviewRetiresOnlyAfterNativeExitAndKeepsBaselines() {
        val root = File(ApplicationProvider.getApplicationContext<Application>().filesDir, UUID.randomUUID().toString())
        val runId = UUID.randomUUID().toString()
        val preview = File(root, "previews/$runId").apply { mkdirs() }
        File(preview, "temporary.lst").writeText("private preview metadata")
        val baseline = File(root, "baselines/$runId").apply { mkdirs() }
        File(baseline, "saved.lst").writeText("healthy baseline")
        assertFalse(BisyncPolicy.retirePreview(root, runId, false))
        assertTrue(preview.isDirectory)
        assertFalse(BisyncPolicy.retirePreview(root, "../baselines/$runId", true))
        assertTrue(BisyncPolicy.retirePreview(root, runId, true))
        assertFalse(preview.exists())
        assertEquals("healthy baseline", File(baseline, "saved.lst").readText())
    }

    @Test fun previewRetirementCannotFollowLinkIntoBaseline() {
        val root = File(ApplicationProvider.getApplicationContext<Application>().filesDir, UUID.randomUUID().toString())
        val runId = UUID.randomUUID().toString()
        val baseline = File(root, "baselines/$runId").apply { mkdirs() }
        File(baseline, "saved.lst").writeText("healthy baseline")
        val previews = File(root, "previews").apply { mkdirs() }
        java.nio.file.Files.createSymbolicLink(File(previews, runId).toPath(), baseline.toPath())
        assertFalse(BisyncPolicy.retirePreview(root, runId, true))
        assertEquals("healthy baseline", File(baseline, "saved.lst").readText())
    }
    @Test fun previewRetirementCannotTraverseNestedLinkIntoBaseline() {
        val root = File(ApplicationProvider.getApplicationContext<Application>().filesDir, UUID.randomUUID().toString())
        val runId = UUID.randomUUID().toString()
        val baseline = File(root, "baselines/$runId").apply { mkdirs() }
        File(baseline, "saved.lst").writeText("healthy baseline")
        val preview = File(root, "previews/$runId").apply { mkdirs() }
        java.nio.file.Files.createSymbolicLink(File(preview, "nested").toPath(), baseline.toPath())
        assertFalse(BisyncPolicy.retirePreview(root, runId, true))
        assertEquals("healthy baseline", File(baseline, "saved.lst").readText())
    }
    @Test fun previewRetirementCannotFollowLinkIntoSiblingPreview() {
        val root = File(ApplicationProvider.getApplicationContext<Application>().filesDir, UUID.randomUUID().toString())
        val sibling = File(root, "previews/${UUID.randomUUID()}").apply { mkdirs() }
        File(sibling, "active.lst").writeText("active preview")
        val runId = UUID.randomUUID().toString()
        java.nio.file.Files.createSymbolicLink(File(root, "previews/$runId").toPath(), sibling.toPath())
        assertFalse(BisyncPolicy.retirePreview(root, runId, true))
        assertEquals("active preview", File(sibling, "active.lst").readText())
    }

    @Test fun readinessMessagesDistinguishChangedSettingsFromRecovery() {
        val baseline=UUID.randomUUID().toString()
        val healthy=org.json.JSONObject().put("status",BisyncStateStore.READY)
            .put("fingerprint","old").put("baselineId",baseline)
        val changed=assertThrows(IllegalArgumentException::class.java) { BisyncStateStore.baselineForRun(healthy,"new") }
        assertTrue(changed.message!!.contains("filters"))
        assertFalse(changed.message!!.contains("previous bisync did not complete"))
        healthy.put("fingerprint","new")
        assertEquals(baseline,BisyncStateStore.baselineForRun(healthy,"new"))
        healthy.put("status",BisyncStateStore.NEEDS_RECOVERY)
        val interrupted=assertThrows(IllegalArgumentException::class.java) { BisyncStateStore.baselineForRun(healthy,"new") }
        assertTrue(interrupted.message!!.contains("previous bisync did not complete"))
        healthy.put("status",BisyncStateStore.READY).put("baselineId","invalid")
        val invalid=assertThrows(IllegalArgumentException::class.java) { BisyncStateStore.baselineForRun(healthy,"new") }
        assertTrue(invalid.message!!.contains("baseline identity"))
    }
}
