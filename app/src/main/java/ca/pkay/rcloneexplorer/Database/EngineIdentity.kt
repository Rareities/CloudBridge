package ca.pkay.rcloneexplorer.Database

import ca.pkay.rcloneexplorer.BuildConfig

/** Immutable engine identity captured by every profile and run snapshot. */
internal object EngineIdentity {
    val current: String
        get() = "rclone:${BuildConfig.RCLONE_ENGINE_VERSION}@${BuildConfig.RCLONE_ENGINE_REF}"
}
