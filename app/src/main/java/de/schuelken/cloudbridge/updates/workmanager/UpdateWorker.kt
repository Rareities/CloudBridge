package de.schuelken.cloudbridge.updates.workmanager

import android.content.Context
import androidx.preference.PreferenceManager
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import ca.pkay.rcloneexplorer.BuildConfig
import ca.pkay.rcloneexplorer.R
import de.schuelken.cloudbridge.extensions.tag
import de.schuelken.cloudbridge.notifications.AppUpdateNotification
import ca.pkay.rcloneexplorer.util.FLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit

class UpdateWorker (private var mContext: Context, workerParams: WorkerParameters): CoroutineWorker(mContext, workerParams) {

    private val preferenceManager = PreferenceManager.getDefaultSharedPreferences(mContext)

    private val ignoredVersion = preferenceManager.getString(mContext.getString(R.string.pref_key_app_update_dismiss_current_update), "")
    private var lastFoundVersion = preferenceManager.getString(mContext.getString(R.string.pref_key_app_updates_found_update_for_version), BuildConfig.VERSION_NAME)?:BuildConfig.VERSION_NAME


    override suspend fun doWork(): Result {

        currentCoroutineContext().ensureActive()

        FLog.e(tag(), "Try to check updates...")

        // This worker can run at startup and on the 14-day connected-network periodic schedule.
        if (!preferenceManager.getBoolean(mContext.getString(R.string.pref_key_app_updates), false)) {
            return Result.success()
        }

        // if we have a new version stored in the preference, only show a notification
        if(BuildConfig.VERSION_NAME != lastFoundVersion) {
            notifyIfRequired()
            // If the last found version is ignored, still do the check
            if (ignoredVersion != lastFoundVersion) {
                return Result.success()
            }
        }

        return try {
            checkGithubReleases()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            FLog.e(tag(), "Update check failed unexpectedly; previous update state was retained")
            Result.retry()
        }
    }

    /**
     * Notification-only update check: scans a bounded set of Rareities releases and compares
     * semantic versions with stable/prerelease channel metadata. Inlined to replace the
     * AppUpdateChecker library (flagged NonFreeNet by the FOSS scan).
     */
    private suspend fun checkGithubReleases(): Result = withContext(Dispatchers.IO) {
        val client = OkHttpClient.Builder()
            .callTimeout(30, TimeUnit.SECONDS)
            .build()
        val workerContext = currentCoroutineContext()
        val outcome = UpdateReleaseScanner.scanAndApply(
            BuildConfig.VERSION_NAME,
            fetchPage = { page ->
                val request = Request.Builder()
                    .url(UpdateReleasePolicy.releasesApiUrl(page))
                    .header("Accept", "application/vnd.github+json")
                    .header("User-Agent", "CloudBridge-Updater")
                    .build()
                val call = client.newCall(request)
                suspendCancellableCoroutine { continuation ->
                    continuation.invokeOnCancellation { call.cancel() }
                    call.enqueue(object : Callback {
                        override fun onFailure(call: Call, e: IOException) {
                            if (continuation.isActive) continuation.resumeWith(kotlin.Result.failure(e))
                        }

                        override fun onResponse(call: Call, response: Response) {
                            try {
                                val httpPage = response.use {
                                    val boundedBody = readBoundedBody(it.body)
                                    UpdateReleaseScanner.HttpPage(
                                        it.code,
                                        boundedBody.text,
                                        boundedBody.exceededLimit
                                    )
                                }
                                if (continuation.isActive) continuation.resumeWith(kotlin.Result.success(httpPage))
                            } catch (error: Exception) {
                                if (continuation.isActive) continuation.resumeWith(kotlin.Result.failure(error))
                            }
                        }
                    })
                }
            },
            onComplete = { newest ->
                if (newest != null) {
                    FLog.e(tag(), "Update found: ${newest.tagName}")
                    setReleaseState(newest.tagName, newest.changelog)
                    notifyIfRequired()
                } else {
                    setFoundVersion(BuildConfig.VERSION_NAME)
                }
            },
            checkCancelled = { workerContext.ensureActive() }
        )
        when (outcome) {
            is UpdateReleaseScanner.Outcome.Complete -> Result.success()
            is UpdateReleaseScanner.Outcome.Incomplete -> {
                FLog.e(tag(), "Release scan incomplete (${outcome.reason}); leaving stored update state unchanged")
                if (outcome.retryable) Result.retry() else Result.failure()
            }
        }
    }

    private data class BoundedBody(val text: String?, val exceededLimit: Boolean)

    /** Reads at most the configured page limit plus one byte; never calls ResponseBody.string(). */
    private fun readBoundedBody(body: ResponseBody?): BoundedBody {
        if (body == null) return BoundedBody(null, false)
        val limit = UpdateReleaseScanner.MAX_RESPONSE_BYTES
        if (body.contentLength() > limit) return BoundedBody(null, true)

        val bytes = body.byteStream().use { input ->
            ByteArrayOutputStream(minOf(limit + 1, 32 * 1024)).use { output ->
                val buffer = ByteArray(8 * 1024)
                var remaining = limit + 1
                while (remaining > 0) {
                    val count = input.read(buffer, 0, minOf(buffer.size, remaining))
                    if (count < 0) break
                    output.write(buffer, 0, count)
                    remaining -= count
                }
                output.toByteArray()
            }
        }
        if (bytes.size > limit) return BoundedBody(null, true)
        return BoundedBody(String(bytes, Charsets.UTF_8), false)
    }

    /**
     * Does not notify the user when the user skipped this update.
     */
    private fun notifyIfRequired(){
        if (ignoredVersion != lastFoundVersion){
            AppUpdateNotification(mContext).showNotification(lastFoundVersion)
        } else {
            FLog.e(tag(), "Hide this version, because it is ignored.")
        }
    }

    /** Stores version and its changelog in one preferences transaction after a complete scan. */
    private fun setReleaseState(version: String, changelog: String) {
        val versionKey = mContext.getString(R.string.pref_key_app_updates_found_update_for_version)
        val changelogKey = mContext.getString(R.string.pref_key_app_updates_changelog)
        preferenceManager.edit()
            .putString(versionKey, version)
            .putString(changelogKey, changelog)
            .apply()
        lastFoundVersion = version
    }

    fun getChangelog(): String{
        return preferenceManager.getString(mContext.getString(R.string.pref_key_app_updates_changelog), "") ?: ""
    }

    private fun setFoundVersion(version: String){
        lastFoundVersion = version
        val versionKey = mContext.getString(R.string.pref_key_app_updates_found_update_for_version)
        val changelogKey = mContext.getString(R.string.pref_key_app_updates_changelog)
        preferenceManager.edit()
            .putString(versionKey, version)
            .putString(changelogKey, "")
            .apply()
    }
}
