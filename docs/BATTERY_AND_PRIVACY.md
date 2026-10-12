# Battery and privacy optimization audit

This pass covers the app's workers, alarms, foreground/background services,
native process and lock ownership, progress/logging, optional network checks,
permissions, Markdown rendering, and existing network/backup/credential boundaries.
It identifies concrete extra work and lifecycle/privacy defects in source code;
it does not prove which one dominates the reported battery drain on a phone.

## Changes and tradeoffs

| Area | Previous behaviour | New behaviour |
| --- | --- | --- |
| Transfer radio locks | High-performance Wi-Fi lock regardless of network | No application Wi-Fi lock; Android manages radio power saving |
| Worker CPU locks | App lock plus WorkManager foreground ownership | Framework-owned worker CPU lock; explicit service/RC locks retained |
| Native exit coordinator | Inspect exit ten times per second | OS exit waiter; at most one-second coordinator interval, immediate exit wakeup and stop signal |
| Progress | Native stats every second; notifications as often as 0.5–0.75s | Stats/notifications every five seconds; quiet heartbeat every fifteen seconds |
| Active RC jobs | Poll every second | Poll every three seconds; terminal detection may be up to two seconds later |
| Idle schedules | New preference default enabled allow-while-idle wakeups | New default disabled; existing preferences preserved |
| Battery exemption | Setup/permission warning encourages exemption | Optional; keep Android optimization enabled unless it blocks necessary work |
| Optional session checks | Extra native listing, unbounded probes, false reconnect alarms | Dump only once; bounded concurrent pipe drains, cancellation, two-minute budget plus stop grace, positive auth evidence |
| Optional update checks | Could run with low battery | Battery-not-low constraint updates existing periodic request |
| Thumbnail server | Stderr drained only when diagnostics enabled; private endpoint logged | Always drain both pipes, retain process ownership, cancel on stop, omit endpoint |
| Markdown | Embedded resources could fetch network data; view executor outlived host | Local rendering, no JS/network/file/content access, browser-only HTTP(S) links; lifecycle cleanup |

Foreground promotion is confirmed before an ephemeral file operation starts.
Completion, cancellation and error outcomes bypass progress throttling. Native
admission remains held until actual OS-confirmed exit, including cancellation.
The previous reviewed rclone engine pin is retained. Fresh authenticated Proton
listings, checksum policy, explicit bisync preview/initialisation and baseline
recovery are preserved; no stale cache, automatic resync or data-loss shortcut
was introduced to reduce work.

Existing protected backups, disabled Android automatic backups, encrypted secret
storage, authenticated loopback RC, private notification presentation, opt-in
updates/session probes, and exported-component guards remain in place. No new
SDK, telemetry, analytics or background network destination was added.

## Existing installations

Saved idle-wakeup choices are preserved. For lower idle drain, turn off General
settings → Allow tasks to run when the device is idle. Android can then defer
scheduled starts while sleeping. Charging-only, quiet-hours and battery-not-low
constraints remain available per task.

An APK cannot revoke an Android battery exemption you previously granted. In
Android app battery settings, use Optimized rather than Unrestricted if deferred
background starts are acceptable. Keep optional session health checks and verbose
file diagnostics disabled unless needed. An active encrypted transfer still
uses CPU and network energy; an idle app and a running transfer are different
measurement cases.

## Verification and device measurement

Regression checks cover quiet-process coordinator wakeup counts, bounded local
checks, cancellation, pipe flooding, service CPU ownership without a Wi-Fi lock,
permissions without a battery exemption, reconnect classification, and blocked
Markdown subresources/custom schemes. The full app unit suite, lint, APK assembly
and instrumentation compilation are required before publication.

For actual energy measurements, compare the same phone, account, task schedule,
network, screen state and file workload before and after the update. Measure a
quiet/background period separately from a complete sync (including scans).
Android's app battery usage and `adb shell dumpsys batterystats
io.github.rareities.protonbridge` can show CPU/wakelock/network attribution.
Capture `adb shell dumpsys power` after completion to check for lingering locks.
Verify matching files and a successful final task state as well as energy and
elapsed time. No physical-device energy or live-account acceptance measurement
has been performed for this pass; no percentage battery saving is claimed.

Local exact-source validation passed all 181 app tests (no failures/skips),
seven release-version tests, lint, APK assembly and instrumentation compilation.
Independent review passed, including cancellation/ownership and privacy checks.
