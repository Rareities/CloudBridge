package io.github.rareities.protonbridge.util

/** Platform rejection is distinct from an engine failure or damaged bisync state. */
object ForegroundStartPolicy {
    const val ACTION_REQUIRED = "Android blocked this task from starting in the background. No transfer started and this attempt did not change the saved bisync baseline. Open ProtonBridge and retry. For scheduled sync, review ProtonBridge's battery/background restrictions in Android App info. This startup error does not require resync."

    fun isDenied(error: Throwable): Boolean {
        var cause: Throwable? = error
        repeat(8) {
            if (cause?.javaClass?.name == "android.app.ForegroundServiceStartNotAllowedException") return true
            cause = cause?.cause
        }
        return false
    }
}
