package de.schuelken.cloudbridge.updates.workmanager

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.coroutines.runBlocking
import java.util.concurrent.CancellationException

class UpdateReleaseScannerTest {
    @Test
    fun httpFailureLeavesPreviouslyPersistedUpdateStateUnchanged() {
        assertIncompletePreservesState { page ->
            if (page == 1) UpdateReleaseScanner.HttpPage(503, "service unavailable")
            else error("scanner must stop after the failed page")
        }
    }

    @Test
    fun malformedJsonLeavesPreviouslyPersistedUpdateStateUnchanged() {
        assertIncompletePreservesState { UpdateReleaseScanner.HttpPage(200, "[{not-json]") }
    }

    @Test
    fun emptyBodyLeavesPreviouslyPersistedUpdateStateUnchanged() {
        assertIncompletePreservesState { UpdateReleaseScanner.HttpPage(200, "") }
    }

    @Test
    fun malformedReleaseEntryLeavesPreviouslyPersistedUpdateStateUnchanged() {
        assertIncompletePreservesState { UpdateReleaseScanner.HttpPage(200, "[null]") }
    }

    @Test
    fun oversizedResponseLeavesPreviouslyPersistedUpdateStateUnchanged() {
        assertIncompletePreservesState {
            UpdateReleaseScanner.HttpPage(200, null, bodyTooLarge = true)
        }
    }

    @Test
    fun oversizedChangelogLeavesPreviouslyPersistedUpdateStateUnchanged() {
        val largeBody = "a".repeat(UpdateReleaseScanner.MAX_CHANGELOG_CHARS + 1)
        val page = """[{"tag_name":"v2.0.0","prerelease":false,"draft":false,"body":"$largeBody"}]"""
        assertIncompletePreservesState { UpdateReleaseScanner.HttpPage(200, page) }
    }

    @Test
    fun laterPageFailureDoesNotApplyCandidatesFromEarlierPages() {
        val fullPage = fullPage("v9.0.0")
        var requestedPages = 0
        var applied = false
        val state = PersistedState("v1.2.3", "previous changelog")

        val outcome = runScan(
            installedVersion = "1.0.0",
            fetchPage = { page ->
                requestedPages++
                if (page == 1) UpdateReleaseScanner.HttpPage(200, fullPage)
                else UpdateReleaseScanner.HttpPage(503, "temporarily unavailable")
            },
            onComplete = { candidate ->
                applied = true
                state.version = candidate?.tagName ?: "1.0.0"
                state.changelog = candidate?.changelog ?: ""
            }
        )

        assertTrue(outcome is UpdateReleaseScanner.Outcome.Incomplete)
        assertEquals(2, requestedPages)
        assertFalse(applied)
        assertEquals("v1.2.3", state.version)
        assertEquals("previous changelog", state.changelog)
    }

    @Test
    fun cancellationBeforeFirstPageDoesNotFetchOrApply() {
        var fetched = false
        var applied = false
        try {
            runScan(
                installedVersion = "1.0.0",
                fetchPage = { fetched = true; UpdateReleaseScanner.HttpPage(200, "[]") },
                onComplete = { applied = true },
                checkCancelled = { throw CancellationException("stop before fetch") }
            )
            throw AssertionError("Expected cancellation")
        } catch (_: CancellationException) {
            assertFalse(fetched)
            assertFalse(applied)
        }
    }

    @Test
    fun cancellationBetweenPagesDoesNotApplyEarlierPage() {
        var checks = 0
        var fetchedPages = 0
        var applied = false
        try {
            runScan(
                installedVersion = "1.0.0",
                fetchPage = { page ->
                    fetchedPages++
                    assertEquals(1, page)
                    UpdateReleaseScanner.HttpPage(200, fullPage("v9.0.0"))
                },
                onComplete = { applied = true },
                checkCancelled = {
                    checks++
                    if (checks == 2) throw CancellationException("stop between pages")
                }
            )
            throw AssertionError("Expected cancellation")
        } catch (_: CancellationException) {
            assertEquals(1, fetchedPages)
            assertFalse(applied)
        }
    }

    @Test
    fun cancellationAfterCompleteScanDoesNotApplyUpdateState() {
        var checks = 0
        var applied = false
        try {
            runScan(
                installedVersion = "1.0.0",
                fetchPage = { UpdateReleaseScanner.HttpPage(200, "[]") },
                onComplete = { applied = true },
                checkCancelled = {
                    checks++
                    if (checks == 2) throw CancellationException("stop before state apply")
                }
            )
            throw AssertionError("Expected cancellation")
        } catch (_: CancellationException) {
            assertFalse(applied)
        }
    }

    @Test
    fun exhaustingPageLimitLeavesPreviouslyPersistedUpdateStateUnchanged() {
        val fullPage = "[${List(UpdateReleasePolicy.RELEASES_PER_PAGE) {
            "{\"tag_name\":\"v9.0.0\",\"prerelease\":false,\"draft\":false,\"body\":\"candidate\"}"
        }.joinToString(",")}]"
        val state = PersistedState("v1.2.3", "previous changelog")
        var applied = false
        var requestedPages = 0

        val outcome = runScan(
            installedVersion = "1.0.0",
            fetchPage = { page ->
                requestedPages++
                assertEquals(requestedPages, page)
                UpdateReleaseScanner.HttpPage(200, fullPage)
            },
            onComplete = { candidate ->
                applied = true
                state.version = candidate?.tagName ?: "1.0.0"
                state.changelog = candidate?.changelog ?: ""
            }
        )

        assertTrue(outcome is UpdateReleaseScanner.Outcome.Incomplete)
        assertEquals(UpdateReleasePolicy.MAX_RELEASE_PAGES, requestedPages)
        assertFalse(applied)
        assertEquals("v1.2.3", state.version)
        assertEquals("previous changelog", state.changelog)
    }

    private fun assertIncompletePreservesState(fetchPage: (Int) -> UpdateReleaseScanner.HttpPage) {
        val state = PersistedState("v1.2.3", "previous changelog")
        var applied = false

        val outcome = runScan(
            installedVersion = "1.0.0",
            fetchPage = fetchPage,
            onComplete = { candidate ->
                applied = true
                state.version = candidate?.tagName ?: "1.0.0"
                state.changelog = candidate?.changelog ?: ""
            }
        )

        assertTrue(outcome is UpdateReleaseScanner.Outcome.Incomplete)
        assertFalse(applied)
        assertEquals("v1.2.3", state.version)
        assertEquals("previous changelog", state.changelog)
    }

    private fun fullPage(tag: String): String = "[${List(UpdateReleasePolicy.RELEASES_PER_PAGE) {
        "{\"tag_name\":\"$tag\",\"prerelease\":false,\"draft\":false,\"body\":\"candidate\"}"
    }.joinToString(",")}]"

    private fun runScan(
        installedVersion: String,
        fetchPage: suspend (Int) -> UpdateReleaseScanner.HttpPage,
        onComplete: (UpdateReleasePolicy.ReleaseCandidate?) -> Unit,
        checkCancelled: () -> Unit = {}
    ): UpdateReleaseScanner.Outcome = runBlocking {
        UpdateReleaseScanner.scanAndApply(installedVersion, fetchPage, onComplete, checkCancelled)
    }

    private data class PersistedState(var version: String, var changelog: String)
}
