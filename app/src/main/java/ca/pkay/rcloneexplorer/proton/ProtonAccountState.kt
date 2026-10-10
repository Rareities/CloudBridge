package ca.pkay.rcloneexplorer.proton

import android.content.Context

/** Same declared Proton username shares a gate; unverified aliases are never guessed. */
object ProtonAccountState {
    class NeedsUserAction : java.io.IOException("Proton account needs reconnect or verification in the task editor")
    private fun prefs(context: Context) = context.getSharedPreferences("proton_account_state", Context.MODE_PRIVATE)
    fun action(context: Context, identity: String): ProtonAccountPolicy.Action? =
        prefs(context).getString(identity, null)?.let { value ->
            ProtonAccountPolicy.Action.entries.firstOrNull { it.name == value } ?: ProtonAccountPolicy.Action.RECONNECT
        }
    fun requireReady(context: Context, identity: String?) {
        if (identity != null && action(context, identity) != null) throw NeedsUserAction()
    }
    @Synchronized fun pause(context: Context, identity: String, action: ProtonAccountPolicy.Action) {
        val editor = prefs(context).edit().putString(identity, action.name)
        if (this.action(context, identity) != action) editor.remove("$identity.notified")
        check(editor.commit()) { "Cannot persist account pause" }
    }
    @Synchronized fun consumeNotification(context: Context, identity: String): Boolean {
        val preferences = prefs(context)
        if (action(context, identity) == null) return false
        if (preferences.getBoolean("$identity.notified", false)) return false
        check(preferences.edit().putBoolean("$identity.notified", true).commit()) { "Cannot persist account notification" }
        return true
    }
    @Synchronized fun verified(context: Context, identity: String) {
        check(prefs(context).edit().remove(identity).remove("$identity.notified").commit()) { "Cannot persist verified account state" }
    }
}
