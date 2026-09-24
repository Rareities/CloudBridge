package de.schuelken.cloudbridge.updates.workmanager

import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CancellationException

/** Bounded release scan. Persisted update state is touched only after a complete scan. */
internal object UpdateReleaseScanner {
    const val MAX_RESPONSE_BYTES = 512 * 1024
    const val MAX_CHANGELOG_CHARS = 16 * 1024

    data class HttpPage(
        val statusCode: Int,
        val body: String?,
        val bodyTooLarge: Boolean = false
    )

    sealed class Outcome {
        data class Complete(val newest: UpdateReleasePolicy.ReleaseCandidate?) : Outcome()
        data class Incomplete(val reason: String, val retryable: Boolean) : Outcome()
    }

    /**
     * Fetches pages through the supplied seam and invokes [onComplete] exactly once only when
     * the release list is known to be complete. A transport, HTTP, or decoding failure leaves
     * the caller's persisted state untouched.
     */
    suspend fun scanAndApply(
        installedVersion: String,
        fetchPage: suspend (Int) -> HttpPage,
        onComplete: (UpdateReleasePolicy.ReleaseCandidate?) -> Unit,
        checkCancelled: () -> Unit = {}
    ): Outcome {
        val outcome = scan(installedVersion, fetchPage, checkCancelled)
        if (outcome is Outcome.Complete) {
            checkCancelled()
            onComplete(outcome.newest)
        }
        return outcome
    }

    private suspend fun scan(
        installedVersion: String,
        fetchPage: suspend (Int) -> HttpPage,
        checkCancelled: () -> Unit
    ): Outcome {
        val candidates = mutableListOf<UpdateReleasePolicy.ReleaseCandidate>()

        for (page in 1..UpdateReleasePolicy.MAX_RELEASE_PAGES) {
            checkCancelled()
            val httpPage = try {
                fetchPage(page)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                checkCancelled()
                return Outcome.Incomplete("request failed on page $page", retryable = true)
            }
            if (httpPage.statusCode !in 200..299) {
                val retryable = httpPage.statusCode == 408 || httpPage.statusCode == 429 ||
                    httpPage.statusCode in 500..599
                return Outcome.Incomplete("HTTP ${httpPage.statusCode} on page $page", retryable)
            }
            if (httpPage.bodyTooLarge) {
                return Outcome.Incomplete("response exceeded the page byte limit", retryable = false)
            }
            val body = httpPage.body
            if (body.isNullOrBlank()) {
                return Outcome.Incomplete("empty response on page $page", retryable = true)
            }
            if (body.length > MAX_RESPONSE_BYTES) {
                return Outcome.Incomplete("response exceeded the page character limit", retryable = false)
            }

            val releases = try {
                JSONArray(body)
            } catch (_: Exception) {
                return Outcome.Incomplete("malformed JSON on page $page", retryable = true)
            }

            if (releases.length() == 0) {
                return Outcome.Complete(UpdateReleasePolicy.newestEligibleRelease(installedVersion, candidates))
            }

            for (index in 0 until releases.length()) {
                val release = releases.opt(index) as? JSONObject
                    ?: return Outcome.Incomplete("malformed release entry on page $page", retryable = false)
                val tag = release.opt("tag_name") as? String
                    ?: return Outcome.Incomplete("missing release tag on page $page", retryable = false)
                val prerelease = release.opt("prerelease") as? Boolean
                    ?: return Outcome.Incomplete("missing prerelease flag on page $page", retryable = false)
                val draft = release.opt("draft") as? Boolean
                    ?: return Outcome.Incomplete("missing draft flag on page $page", retryable = false)
                val changelogValue = release.opt("body")
                val changelog = when (changelogValue) {
                    null, JSONObject.NULL -> ""
                    is String -> {
                        if (changelogValue.length > MAX_CHANGELOG_CHARS) {
                            return Outcome.Incomplete("release changelog exceeded the character limit", retryable = false)
                        }
                        changelogValue
                    }
                    else -> return Outcome.Incomplete("malformed release body on page $page", retryable = false)
                }
                candidates += UpdateReleasePolicy.ReleaseCandidate(tag, prerelease, draft, changelog)
            }

            if (releases.length() < UpdateReleasePolicy.RELEASES_PER_PAGE) {
                return Outcome.Complete(UpdateReleasePolicy.newestEligibleRelease(installedVersion, candidates))
            }
        }

        return Outcome.Incomplete("page limit reached", retryable = false)
    }
}
