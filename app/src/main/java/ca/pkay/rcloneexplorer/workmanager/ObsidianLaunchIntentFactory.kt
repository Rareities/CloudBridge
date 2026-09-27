package ca.pkay.rcloneexplorer.workmanager

import android.content.Context
import android.content.Intent

/** Builds a package-scoped launcher intent only for a policy-approved user action. */
internal object ObsidianLaunchIntentFactory {
    sealed class Result {
        data class Available(val intent: Intent) : Result()
        data class ManualActionRequired(
            val decision: ObsidianSyncBeforeOpenPolicy.Decision
        ) : Result()
        data class NotReady(
            val decision: ObsidianSyncBeforeOpenPolicy.Decision
        ) : Result()
    }

    /**
     * Returns an intent suitable for a notification PendingIntent. This method does not launch
     * an activity; the caller must expose the returned intent through an explicit user action.
     */
    @JvmStatic
    fun create(
        context: Context,
        decision: ObsidianSyncBeforeOpenPolicy.Decision
    ): Result {
        val spec = decision.launchIntentSpec
        if (decision.state != ObsidianSyncBeforeOpenPolicy.State.USER_ACTION_AVAILABLE ||
            spec == null ||
            spec.packageName != ObsidianSyncBeforeOpenPolicy.OBSIDIAN_PACKAGE ||
            spec.action != ObsidianSyncBeforeOpenPolicy.ACTION_MAIN ||
            spec.category != ObsidianSyncBeforeOpenPolicy.CATEGORY_LAUNCHER) {
            return Result.NotReady(decision)
        }

        val resolved = context.packageManager.getLaunchIntentForPackage(spec.packageName)
        val component = resolved?.component
        if (component == null || component.packageName != spec.packageName) {
            return Result.ManualActionRequired(
                ObsidianSyncBeforeOpenPolicy.onLaunchIntentUnavailable(decision)
            )
        }

        val intent = Intent(spec.action)
            .setPackage(spec.packageName)
            .setComponent(component)
            .addCategory(spec.category)
        return Result.Available(intent)
    }
}
