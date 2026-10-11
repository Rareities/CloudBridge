package ca.pkay.rcloneexplorer.proton

import android.content.Context
import java.io.IOException

/** A private hashed-account pause survives restarts and debounces repeated schedule alerts. */
object ProtonAccountState {
    class NeedsUserAction(val identity: String, val action: ProtonAccountPolicy.Action) : IOException(ProtonAccountPolicy.message(action))
    private fun prefs(context: Context) = context.getSharedPreferences("proton_account_state", Context.MODE_PRIVATE)

    @JvmStatic fun action(context: Context, identity: String): ProtonAccountPolicy.Action? {
        require(identity.matches(Regex("[0-9a-f]{64}"))) { "Invalid account identity" }
        return prefs(context).getString(identity, null)?.let { stored ->
            ProtonAccountPolicy.Action.entries.firstOrNull { it.name == stored } ?: ProtonAccountPolicy.Action.RECONNECT
        }
    }

    @JvmStatic fun requireReady(context: Context, identity: String) {
        action(context, identity)?.let { throw NeedsUserAction(identity, it) }
    }

    @JvmStatic @Synchronized fun pause(context: Context, identity: String, action: ProtonAccountPolicy.Action) {
        val editor = prefs(context).edit().putString(identity, action.name)
        if (this.action(context, identity) != action) editor.remove("$identity.notified")
        check(editor.commit()) { "Could not persist the account pause" }
    }

    @JvmStatic @Synchronized fun consumeNotification(context: Context, identity: String): Boolean {
        val store = prefs(context)
        if (action(context, identity) == null || store.getBoolean("$identity.notified", false)) return false
        check(store.edit().putBoolean("$identity.notified", true).commit()) { "Could not persist account notification state" }
        return true
    }

    @JvmStatic @Synchronized fun verified(context: Context, identity: String) {
        check(prefs(context).edit().remove(identity).remove("$identity.notified").commit()) { "Could not record account verification" }
    }
}
