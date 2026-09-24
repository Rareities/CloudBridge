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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray

class UpdateWorker (private var mContext: Context, workerParams: WorkerParameters): CoroutineWorker(mContext, workerParams) {

    private val preferenceManager = PreferenceManager.getDefaultSharedPreferences(mContext)

    private var checkForUpdates = preferenceManager.getBoolean(mContext.getString(R.string.pref_key_app_updates), false)
    private val ignoredVersion = preferenceManager.getString(mContext.getString(R.string.pref_key_app_update_dismiss_current_update), "")
    private var lastFoundVersion = preferenceManager.getString(mContext.getString(R.string.pref_key_app_updates_found_update_for_version), BuildConfig.VERSION_NAME)?:BuildConfig.VERSION_NAME


    override suspend fun doWork(): Result {

        FLog.e(tag(), "Try to check updates...")

        // This worker can run at startup and on the 14-day connected-network periodic schedule.
        if(!checkForUpdates) {
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

        try {
            checkGithubReleases()
        } catch (e: Exception) {
            FLog.e(tag(), "Error checking updates", e)
        }

        // Indicate whether the work finished successfully with the Result
        return Result.success()
    }

    /**
     * Notification-only update check: scans a bounded set of Rareities releases and compares
     * semantic versions with stable/prerelease channel metadata. Inlined to replace the
     * AppUpdateChecker library (flagged NonFreeNet by the FOSS scan).
     */
    private suspend fun checkGithubReleases() = withContext(Dispatchers.IO) {
        val client = OkHttpClient()
        val candidates = mutableListOf<UpdateReleasePolicy.ReleaseCandidate>()
        var releaseListComplete = false

        for (page in 1..UpdateReleasePolicy.MAX_RELEASE_PAGES) {
            val request = Request.Builder()
                .url(UpdateReleasePolicy.releasesApiUrl(page))
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "CloudBridge-Updater")
                .build()

            var continuePaging = true
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    FLog.e(tag(), "Release API returned HTTP ${response.code} on page $page")
                    return@withContext
                }
                val body = response.body?.string()
                if (body.isNullOrEmpty()) {
                    FLog.e(tag(), "Release API returned an empty response body on page $page")
                    return@withContext
                }
                val releases = try {
                    JSONArray(body)
                } catch (_: Exception) {
                    FLog.e(tag(), "Release API returned malformed JSON on page $page")
                    return@withContext
                }

                if (releases.length() == 0) {
                    releaseListComplete = true
                    continuePaging = false
                } else {
                    for (index in 0 until releases.length()) {
                        val release = releases.optJSONObject(index) ?: continue
                        candidates += UpdateReleasePolicy.ReleaseCandidate(
                            release.optString("tag_name").takeIf { it.isNotBlank() },
                            release.optBoolean("prerelease"),
                            release.optBoolean("draft"),
                            release.optString("body")
                        )
                    }
                    if (releases.length() < UpdateReleasePolicy.RELEASES_PER_PAGE) {
                        releaseListComplete = true
                        continuePaging = false
                    }
                }
            }

            if (!continuePaging) break
        }

        if (!releaseListComplete) {
            FLog.e(tag(), "Release scan reached its page limit; leaving the stored update state unchanged")
            return@withContext
        }

        val newest = UpdateReleasePolicy.newestEligibleRelease(BuildConfig.VERSION_NAME, candidates)
        if (newest != null) {
            val tagName = newest.tagName
            FLog.e(tag(), "Update found: $tagName")
            setFoundVersion(tagName)
            setChangelog(newest.changelog)
            notifyIfRequired()
        } else {
            setFoundVersion(BuildConfig.VERSION_NAME)
        }
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

    private fun setChangelog(changelog: String){
        val key = mContext.getString(R.string.pref_key_app_updates_changelog)
        preferenceManager.edit().putString(key, changelog).apply()
    }

    fun getChangelog(): String{
        return preferenceManager.getString(mContext.getString(R.string.pref_key_app_updates_changelog), "") ?: ""
    }

    private fun setFoundVersion(version: String){
        lastFoundVersion = version
        val key = mContext.getString(R.string.pref_key_app_updates_found_update_for_version)
        preferenceManager.edit().putString(key, version).apply()
    }
}
