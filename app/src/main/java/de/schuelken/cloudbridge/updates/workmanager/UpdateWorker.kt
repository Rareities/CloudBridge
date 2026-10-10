package de.schuelken.cloudbridge.updates.workmanager

import android.content.Context
import ca.pkay.rcloneexplorer.util.FLog
import kotlinx.coroutines.CancellationException
import androidx.preference.PreferenceManager
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import ca.pkay.rcloneexplorer.BuildConfig
import ca.pkay.rcloneexplorer.R
import de.schuelken.cloudbridge.extensions.tag
import de.schuelken.cloudbridge.notifications.AppUpdateNotification
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

        // this is supposed to only run on startup and once a week.
        if(!checkForUpdates) {
            return Result.success()
        }

        try {
            checkGithubReleases()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            FLog.e(tag(), "Update check failed: ${e.javaClass.simpleName}")
        }

        // Indicate whether the work finished successfully with the Result
        return Result.success()
    }

    companion object {
        private const val REPO_OWNER = "Rareities"
        private const val REPO_NAME = "CloudBridge"
    }

    /**
     * Notification-only check for the fork's newest eligible release. Stable builds
     * skip previews; existing beta builds can follow previews without ordering SHA tags lexically.
     */
    private suspend fun checkGithubReleases() = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("https://api.github.com/repos/$REPO_OWNER/$REPO_NAME/releases?per_page=100")
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "CloudBridge-Updater")
            .build()

        OkHttpClient().newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                FLog.e(tag(), "Release API returned HTTP ${response.code}")
                return@withContext
            }
            val body = response.body?.string()
            val releases = if (body.isNullOrEmpty()) null else JSONArray(body)
            if (releases == null || releases.length() == 0) {
                setFoundVersion(BuildConfig.VERSION_NAME)
                return@withContext
            }
            val candidates = (0 until releases.length()).map { index ->
                val release = releases.getJSONObject(index)
                ReleasePolicy.Release(
                    tag = release.optString("tag_name"), body = release.optString("body"),
                    draft = release.optBoolean("draft"), prerelease = release.optBoolean("prerelease"),
                    publishedAt = release.optString("published_at"))
            }
            val newest = ReleasePolicy.select(BuildConfig.VERSION_NAME, candidates)
            if (newest != null) {
                val changed = lastFoundVersion != newest.tag
                setFoundVersion(newest.tag)
                setChangelog(newest.body)
                if (changed) notifyIfRequired()
            } else {
                setFoundVersion(BuildConfig.VERSION_NAME)
            }
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
