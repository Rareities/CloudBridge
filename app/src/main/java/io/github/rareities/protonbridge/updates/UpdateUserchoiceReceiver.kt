package io.github.rareities.protonbridge.updates

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.preference.PreferenceManager
import io.github.rareities.protonbridge.R
import io.github.rareities.protonbridge.extensions.tag
import io.github.rareities.protonbridge.notifications.AppUpdateNotification


class UpdateUserchoiceReceiver : BroadcastReceiver() {

    companion object {
        var ACTION_IGNORE = "ACTION_IGNORE"
        var IGNORE_VERSION_EXTRA = "IGNORE_VERSION_EXTRA"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if(intent.action == ACTION_IGNORE) {
            Log.e(tag(), "Ignore current update!")
            val preferenceManager = PreferenceManager.getDefaultSharedPreferences(context)
            val key = context.getString(R.string.pref_key_app_update_dismiss_current_update)
            preferenceManager.edit().putString(key, intent.getStringExtra(IGNORE_VERSION_EXTRA)).apply()
            AppUpdateNotification(context).cancelNotification()
        }
    }
}
