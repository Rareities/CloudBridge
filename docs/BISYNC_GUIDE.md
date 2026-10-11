# Proton Drive bisync candidate

This branch extends CloudBridge's existing Android application. The supported
first validation target is an ordinary Proton Drive folder paired with a native
local directory. It retains ordinary copy, one-way sync, cloud-to-cloud copy/sync,
remote forms and Android document-provider integration. It is not a replacement
transfer engine or a desktop application.

## Main controls and optional tools

The normal setup is **Save and preview → Review and initialise → Run**.
Keep independent backups and review which side wins differing files during setup.

| Control | What it does | Is it needed? |
| --- | --- | --- |
| Save and preview | Saves this task and performs a dry run showing proposed changes. Wait for successful completion. | Yes, before first initialization or changed baseline settings. |
| Review and initialise | Checks that the completed preview still matches, displays the report, and asks for explicit approval before establishing the baseline. | Yes, once during setup/recovery. It can copy or overwrite files. |
| View last preview | Opens the saved report without running a fresh scan. | Optional. |
| Advanced settings | Reveals backups, access markers, flags and helper tools. Collapsing it does not remove saved options. | Optional; defaults work without custom flags. |
| Build a flag (formerly Add an option) | Helps enter an option name and value into the flags field. | Optional; manual entry is supported. |
| Choose performance preset (formerly Apply tuning template) | Copies a set of performance options into the task. | Optional; it is not required for Proton or bisync. |
| View rclone command (formerly Review effective arguments) | Shows the arguments the app will use; does not start a transfer. | Optional troubleshooting. |
| rclone flag reference (formerly Show engine option help) | Displays help from the exact bundled engine. | Optional reference. |
| Proton login help (formerly Proton connection) | Reconnects and verifies a Proton account when login needs attention; an optional 2FA code is not saved. | Only for login problems. It does not initialize a sync baseline. |

You can type flags manually, one per line, for example:

```text
--bwlimit=8M
--checkers=2
```

Managed safety controls, credentials and commands cannot be overridden through
this field. Change settings, then save and preview again.

### When actions are unavailable

Review becomes available after a successful, unexpired initialization preview.
It remains unavailable before preview completes, after failure/expiry, or while
work is queued/running. Opening Review rechecks the actual saved settings; it
never bypasses initialization approval.

If Preview is also disabled, inspect the queued/running message and use Cancel
queued or running task before starting over. Android may be waiting for network,
charging or background limits. A failure before the preview starts is shown as
Last attempt, with details in Run history. Failed configuration verification is
shown rather than silently leaving actions unavailable.

## Create and initialise a task

1. Configure the remote through the existing remote editor. Use a disposable
   Proton folder for initial validation and keep an independent backup.
2. Create a task, select **Bisync**, and choose the local and remote folders.
   The local directory must exist and remain accessible to the app.
3. Choose which side should win differing files **during initialisation**.
   This is separate from normal conflict handling, which preserves conflicts.
4. Review the deletion percentage, optional backup folders and access markers.
   The initial deletion limit is 10 percent, not ten files. A backup folder must
   be outside the synchronised root; a whole-remote-root task cannot use a
   subfolder of that root as its remote backup.
5. Save and run **Preview**. Read the summary, then explicitly approve
   initialisation. A preview is advisory, can authenticate to the remote, and
   is not a frozen cloud snapshot. Long output is a bounded, labelled summary.
6. After successful initialisation the task becomes ready for normal runs.
   Use **Run**, **Cancel**, **History** and **Schedules** in the task editor.

For an already-ready task, **Save and preview** checks ordinary changes against
a private copy of the current listings and their verified endpoint identity.
It preserves the successful baseline and does not require another initialisation.
Use **View last preview** to inspect the timestamped, bounded report, including
affected filenames. A historical report is not approval and may no longer match
current settings or cloud contents.

Approval expires after 30 minutes and is bound to the task, ordered endpoints,
filters, effective options, configuration identity and exact engine commit.
Editing baseline settings or changing the engine requires a new review. Missing
or interrupted baseline state cannot silently become a successful run. The app
does not automatically apply `--resync`, `--force`, empty trash or delete
unfamiliar remote drafts to repair a failure.

## Comparison, deletion and conflicts

Managed bisync uses size and a common checksum with the fork's opt-in
`--require-checksum` guard. Missing plaintext sizes, missing common/per-file
hashes and damaged baseline rows stop reconciliation. The app does not quietly
fall back to size-only comparison. Backends or paths without the required
metadata therefore cannot use this managed mode.

Normal conflicts retain both contents. Newer/older automatic precedence is not
offered because meaningful modification times are not guaranteed for this
local/Proton pairing. Existing filters remain available, but changing them
requires baseline review. Enable access checks only after deliberately placing
the required `RCLONE_TEST` marker in both roots. The marker checks availability;
it is not a transactional consistency guarantee.

## Advanced flags

Use the option/value dialog or one `--flag` / `--flag=value` per line. Examples:

```text
--bwlimit=8M
--checkers=4
--retries=3
--timeout=2m
--exclude=*.tmp
```

Values are individual process arguments, not shell text. Spaces and Unicode
are retained. Repeated filter/include/exclude rules are supported; duplicate
scalar options are rejected. The exact bundled executable validates option
names and values before remote mutation. Its help is available in the editor.

Relevant ordinary flags are supported, while app-owned state, endpoints,
initialisation, comparison/deletion protection, listeners, credentials,
executable hooks and unsafe diagnostics cannot be overridden through a task.
Use the task controls or remote editor for these. This deliberately does not
mean unrestricted execution of arbitrary rclone commands.

## Scheduling and task editing

The editor exposes saved schedules, next run, Wi-Fi/unmetered constraints,
charging, battery-not-low and quiet hours. Daily/selected-weekday schedules use
civil time and a timezone; fixed intervals use checked long arithmetic and have
a 15-minute minimum. Missed occurrences coalesce instead of replaying a backlog.
Follow-up chains retain success/failure semantics, reject cycles and stop after
a bounded number of tasks.

WorkManager owns constrained transfers. Exact alarms are used only when
permitted; otherwise scheduling falls back to an inexact alarm. Doze, Android
quotas, permission changes and force-stop can delay work. Force-stopped apps
cannot promise automatic resumption before reopening. Quiet hours defer
automatic work; an explicit manual request is not silently treated as automatic.

The native child exit code determines success. Cancellation, missing processes,
stream failures and nonzero exits cannot trigger success follow-ups. A durable
in-progress history entry without a terminal result is shown as **completion
unconfirmed**, not success.

The task saved by Preview, Run or Manage schedules retains its ID when the editor
is recreated. An unavailable saved remote remains selected and blocks saving or
running until it is reconnected or explicitly replaced. Folder selection uses
this installation's document-provider authority and preserves encoded names.
Editing a queued task invalidates the old request revision; it cannot silently
run against newly selected locations.

## Security and resource behaviour

Native configuration encryption, explicit encrypted credential backup and
journalled recovery are described in [ADR-002](ADR-002-native-rclone-config.md).
New backups encrypt all component contents using standard AES-256 ZIP entries,
including task/path metadata and proxy credentials. Fixed component names and
sizes remain visible. Keep the export password separately and validate recovery.
Proxy passwords are Keystore-wrapped at rest. Legacy proxy-enabled archives
without a protected password require explicit proxy reconfiguration.

Task IDs alone cannot authorise exported shortcuts. Diagnostics are redacted
and bounded, and private history is retained by task identity. Release signing
requires the approved certificate fingerprint; debug packages are clearly
separate. App self-updates remain notification-only.

Mutating task workers share conservative admission. Document-provider mutations
and native streams participate in that exclusion. A stream lease lasts until
both pipe handling and native process exit finish. RC starts on demand and
retires after a 30-second idle grace when no requests, jobs or streams remain.
This grace is an initial policy, not a measured optimum. Periodic session-health
checks are opt-in and default off. No energy-saving percentage is claimed.

Configuration sessions exclude incompatible CLI and RC work, including unknown
outcomes after request timeouts. Native exit and lease cleanup are separate
events; restart waits for the cleanup barrier. Loopback remains the serving
default. Explicit LAN exposure requires authenticated HTTPS/HTTPS WebDAV with
readable certificate/key files; FTP and DLNA remain limited to this device.

## Engine and validation boundaries

The packaged engine is pinned to the reviewed Rareities/rclone master commit
recorded in `gradle.properties`, source label `v1.76.0` (development),
with the reviewed API and bridge dependencies. The APK includes an engine
provenance manifest, per-ABI SHA-256 values and module versions. Engine fixes
and upstream relationships are recorded in that repository's
`docs/PROTON_BISYNC_PATCHES.md` and `third_party/proton-api-bridge/UPSTREAM.md`.

The engine binds legacy-compatible listing filenames to the exact ordered
endpoint pair. Different names that canonicalise to the same filename cannot
silently reuse one another's baseline. Unbound older engine state requires
explicit reviewed initialisation; the app already invalidates approval when its
engine changes. Keep old baselines until recovery is understood, never delete
them or add `--force` merely to clear an error.

Automated local/fake-server tests cover session ownership, token adoption,
modern-name ambiguity, metadata, upload cancellation, locks, strict checksum
comparison, task state, restore failure, schedules and option handling.
The engine's Linux CI includes race checks. Build and test outcomes are reported
separately from the following release gates:

| Gate | Required evidence |
| --- | --- |
| Live Proton compatibility | Disposable account tests, official-client read/overwrite, aliases and two-password accounts, long-lived rotation and multiple processes |
| Crypto/service evolution | Maintainer-supported compatibility for new Proton models; invalid signatures must keep failing |
| Android operation | Extend the passing API 35 disposable-emulator scenarios to physical devices, other advertised APIs/ABIs, real battery/Doze/quota conditions and third-party SAF clients |
| Resource and battery budgets | Controlled cold/warm measurements of memory, CPU, wakeups, native child counts, network and energy |
| Release distribution | Approved signing certificate, device gates and an explicit promotion decision |

Photos, Shared With Me, instant event-driven sync, automatic unknown-draft
recovery, desktop support and biometric-per-run unlocking are not advertised
as implemented. A successful build does not establish universal Proton
compatibility or safe deployment against irreplaceable production data.

## Build

Use JDK 17, Android SDK 36, NDK 29.0.14206865 and Go at least 1.27.2. Android
executables use bionic system DNS, CGO, PIE and 16 KiB load alignment at API 23.

```sh
./gradlew testOssDebugUnitTest lintOssDebug assembleOssDebug
```

Debug application ID is `de.schuelken.cloudbridge.debug`. It can coexist with
the release app and has separate storage and configuration. Debug APKs are
test artifacts, not an in-place signed update of an existing release install.

The reproducible acceptance driver is `scripts/Verify-Emulator.ps1`; it requires
an explicitly acknowledged disposable API 35 emulator and the debug app/test
APKs. It never permits a physical device target. Native listener checks and
controlled host comparisons are in `scripts/Verify-NativeListeners.ps1` and
`scripts/Measure-NativeBisync.ps1`. See [validation](BISYNC_VALIDATION.md) for exact
commands, scope and remaining external acceptance gates.

Release builds require the usual keystore inputs plus `CB_SIGNING_CERT_SHA256`
(or `certificateSha256` in git-ignored `keystore.properties`). The value is the
expected signing certificate SHA-256, not a password. CI uses the repository
variable of that name. A missing or mismatched identity fails the release build.

## A notification appears but the task seems idle

The editor now shows the current phase: checking settings, checking rclone flags,
connecting/scanning, or transferring. During a quiet engine phase it shows elapsed
time and, after 30 seconds without engine output, how long the engine has been
quiet. Scanning may take time before any bytes transfer; silence alone does not
establish a stalled process. Cancel remains available.

Android foreground startup and the local flag check each have a 30-second
limit. Stopping a timed-out child can add a cancellation grace period; another
transfer remains blocked until its exit is confirmed. A timeout produces a failed
attempt with an explanation. Actual cloud
transfers have no added overall deadline. Queued attempts explain known waits
for network, charging, quiet hours or another native operation; Android constraints
can also postpone work before the worker starts.

If a failure persists, include the app version, displayed phase, elapsed time and
redacted last-attempt message in an issue. Do not post credentials or raw config.
