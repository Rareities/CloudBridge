package ca.pkay.rcloneexplorer.Database

/** Read-only native root probe; contains counts and reason codes, never paths or listing data. */
data class BisyncRootScanResult(
    val evidence: BisyncListingEvidence,
    val statProbeSucceeded: Boolean
)
