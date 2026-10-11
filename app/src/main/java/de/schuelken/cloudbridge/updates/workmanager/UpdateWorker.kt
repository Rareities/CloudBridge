package de.schuelken.cloudbridge.updates.workmanager

import android.content.Context
import android.util.Log
import androidx.preference.PreferenceManager
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import ca.pkay.rcloneexplorer.BuildConfig
import ca.pkay.rcloneexplorer.R
import de.schuelken.cloudbridge.extensions.tag
import de.schuelken.cloudbridge.notifications.AppUpdateNotification
import de.schuelken.cloudbridge.updates.ReleaseSelector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit

class UpdateWorker (private var mContext: Context, workerParams: WorkerParameters): CoroutineWorker(mContext, workerParams) {

    private val preferenceManager = PreferenceManager.getDefaultSharedPreferences(mContext)

    override suspend fun doWork(): Result {
        if (!preferenceManager.getBoolean(mContext.getString(R.string.pref_key_app_updates), false)) return Result.success()
        try {
            checkGithubReleases()
        } catch (e: Exception) {
            Log.w(tag(), "Update check unavailable: ${e.javaClass.simpleName}")
        }
        return Result.success()
    }

    companion object {
        private const val REPO_OWNER = "Rareities"
        private const val REPO_NAME = "CloudBridge"
        private const val MAX_RESPONSE_BYTES = 512 * 1024
        private val client = OkHttpClient.Builder()
            .callTimeout(15, TimeUnit.SECONDS)
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build()
    }

    private suspend fun checkGithubReleases() = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("https://api.github.com/repos/$REPO_OWNER/$REPO_NAME/releases?per_page=20")
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "CloudBridge-Updater")
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                Log.e(tag(), "Release API returned HTTP ${response.code}")
                return@withContext
            }
            val body = response.body ?: throw IOException("Missing release response")
            val buffer = ByteArrayOutputStream()
            body.byteStream().use { stream ->
                val bytes = ByteArray(8192)
                while (true) {
                    val count = stream.read(bytes)
                    if (count < 0) break
                    if (buffer.size() + count > MAX_RESPONSE_BYTES) throw IOException("Release response exceeds limit")
                    buffer.write(bytes, 0, count)
                }
            }
            val release = ReleaseSelector.newest(BuildConfig.VERSION_NAME,
                JSONArray(buffer.toString(Charsets.UTF_8.name())))
            val installed = BuildConfig.VERSION_NAME
            val previouslyFound = preferenceManager.getString(
                mContext.getString(R.string.pref_key_app_updates_found_update_for_version), installed)
            if (release == null) {
                if (previouslyFound != installed) {
                    setFoundVersion(installed)
                    setChangelog("")
                }
                return@withContext
            }
            if (release.tag != previouslyFound) {
                setFoundVersion(release.tag)
                setChangelog(release.changelog)
                val ignored = preferenceManager.getString(
                    mContext.getString(R.string.pref_key_app_update_dismiss_current_update), "")
                if (ignored != release.tag) {
                    AppUpdateNotification.RELEASES_URL = "https://github.com/Rareities/CloudBridge/releases"
                    AppUpdateNotification(mContext).showNotification(release.tag)
                }
            }
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
        val key = mContext.getString(R.string.pref_key_app_updates_found_update_for_version)
        preferenceManager.edit().putString(key, version).apply()
    }
}
