# End-to-end candidate acceptance

This is the integration acceptance procedure for the `feat/proton-bisync`
development candidate. It supplements the 9 October 2026 master plan and the
historical first implementation report. It does not certify a production release.
Use only disposable data. No personal Proton credentials are needed for the
automated host, fake-server or emulator suites.

## Reproduce automated checks

Use the application and engine feature branches, JDK 17, PowerShell 7, Android
SDK/build tools 36 and the Go version required by `gradle.properties`. Read the
engine commit from that file; do not replace it with a moving branch.

```powershell
# From the engine checkout, build a host CLI with real VCS provenance.
go build -mod=readonly -buildvcs=true -tags noselfupdate -o <host-rclone> .
go test ./backend/protondrive ./backend/internxt ./cmd/bisync/... -count=1
./scripts/verify-bisync-integration.ps1 -Rclone <host-rclone> -WorkRoot <new-empty-directory>

# From CloudBridge. The environment variable enables the native encryption test.
$env:CLOUDBRIDGE_TEST_RCLONE = '<absolute-host-rclone-path>'
./gradlew testOssDebugUnitTest lint assembleOssDebug assembleOssDebugAndroidTest
./scripts/Verify-Apk.ps1 -SdkDirectory <android-sdk>
./scripts/Verify-NativeListeners.ps1 -Binary <host-rclone> -Evidence <new-empty-directory>

# This driver rejects physical devices and non-API-35 targets.
./scripts/Verify-Emulator.ps1 -Adb <adb> -Serial emulator-5580 `
  -AppApk <x86_64-debug-apk> -TestApk <debug-androidTest-apk> `
  -DisposableEmulator -OutputDirectory <new-empty-directory>
```

The emulator driver installs only the separate debug application. Its fixtures
augment or stage the disposable debug configuration and must not be pointed at
an emulator holding valuable accounts or files. It records the exact APK hashes,
explicit instrumentation results and UI screenshots. ADB exit zero is never
interpreted as an instrumentation pass. Battery simulation is reset on exit.

The native CLI suite exercises previews, initialisation, both directions,
conflicting bytes, deletion thresholds, unavailable roots, missing/restored
sentinels, concurrent reversed paths, forced termination and stale-lock refusal.
Both availability markers have the same fixed timestamp: the sentinel test is
about access checking, not local nanosecond timestamp reconciliation.

The Android suite exercises the production worker with the packaged executable,
the actual WorkManager scheduler and charging transition, foreground cancellation,
configuration-session exclusion, authenticated RC copy/move and idle restart,
public document-provider create/rename/read/write/move/copy/delete, force-stop
and reopen with a missed schedule, and encrypted configuration/Keystore-record
recovery across an interrupted pair swap. A complete GUI scenario creates a task,
previews, recreates the activity, approves initialisation, previews a ready
baseline, displays affected filenames, runs two-way changes, rejects `--force`
and creates a schedule through the actual task and schedule editors.

Host tests independently cover revision-bound queued tasks, missing remotes,
encoded folder selection, cancellation and failure outcomes, partial restore,
shortcut capabilities, advanced options, DNS/proxy formatting, listener policy,
unknown RC response fields and deterministic startup/completion races.

## Comparison with the master plan

| Plan area | Implemented candidate behaviour | Acceptance boundary |
| --- | --- | --- |
| DEP-001/002 | Immutable fork pin, four native ABIs, embedded module/provenance records, build-time and packaged-byte checks | All four ABIs build; actual runtime coverage is the API 35 x86_64 emulator |
| PD-001/002/003/004/007 | Session-bound callbacks, guarded token adoption, authenticated name fallback, strict plaintext metadata, rejected partial/ambiguous listings and reader cleanup | Fake-server/negative/race tests; live official-client and long-lived refresh remain external |
| BS-001 through BS-006 | Stable job identity, private baselines, raw ordered endpoint binding, pair/basename locks, explicit approval, conflict preservation, thresholds, cancellation and recovery | Native and Android local fixtures; no automatic resync or unsafe metadata fallback |
| BS-007/009 and UX-001 | Supported option/value flags, engine validation, timestamped preview viewer with filenames, run/cancel/history and recreation-safe task editing | Actual GUI-to-engine workflow; general screen-reader/device/font matrix remains a release gate |
| BS-008, CB-005/006 | Shared schedules, timezone/DST calculations, quiet hours, constraints, missed-run coalescing, revision validation and bounded follow-ups | Host calculations plus actual Android WorkManager, charging, cancellation and restart |
| CB-001/002/004/007 | True child outcomes, exactly-once RC result claims, native-exit cleanup barrier, durable encrypted restore and truthful provider mutations | Failure injection, native processes, Android provider and restart fixtures |
| SEC/PRIV | Native encrypted config, Keystore wrapping, redaction, private bounded history, capability-scoped shortcuts, authenticated TLS LAN policy, signing guards | Automated security tests; hardware key invalidation, VPN/Private DNS and physical-client matrices remain external |
| PWR/PERF | Demand-driven RC, observed idle retirement, default-off health probes, conservative native admission, throttled progress and reproducible host measurements | No device energy or general battery-saving claim |
| DEP-003 | Untrusted candidate tests separated from trusted publication, SHA-pinned actions and bounded bundle validation | Draft-only intake; live master-only publisher is not run before an authorised merge |

Optional presets and conditional Photos, Shared With Me, cloud-to-cloud/SAF
bisync, remote revision browsing and desktop support are not promoted as
implemented or validated. Existing ordinary copy/sync/cloud-to-cloud operations
and document access remain available. The original Views and SQLite architecture
is retained; no new synchronisation algorithm or cryptography was introduced.

## Remaining live Proton acceptance

Use a dedicated disposable account with an ordinary Drive test folder and an
independent manifest of SHA-256 file contents. Record app/engine commit, account
mode, official-client version and test time. Never import a personal account into
this fixture. Run the following sequence separately for single-password,
two-password, 2FA and supported custom-domain/alias configurations:

1. Create files through the official client before configuring the app. Include
   empty files, mixed Unicode and punctuation, nested/empty directories, same-size
   differing content, many small files and a bounded large file.
2. Preview and approve a local/Drive baseline. Confirm that preview changes no
   user files. Open downloaded/uploaded files through both the app and official
   client; compare exact bytes and confirm existing revision history survives an
   overwrite.
3. Change files independently on both sides, including a simultaneous edit and
   a delete-versus-modify conflict. Preserve both expected content versions.
   Exceed the deletion percentage and revoke one root/sentinel in separate runs;
   require failure without target deletion or baseline promotion.
4. Interrupt listing, upload and finalisation separately. Cancel, terminate and
   reopen the app. Require an honest cancelled/recovery state, no orphan mutation,
   no false success, and no automatic resync. Review recovery explicitly.
5. Exercise two aliases to the same account and an official client concurrently.
   Continue across an actual refresh interval, then revoke the test session.
   Compatible rotations must preserve the current configuration, unrelated/stale
   credentials must never be adopted, and revocation must require user action
   without a password-login storm.
6. Repeat after the relevant official-client/crypto migration. Any bad signature,
   missing plaintext hash/size, incomplete listing or ambiguous authenticated
   name must fail. Never approve a signature bypass as a workaround.

Retain redacted outcomes and manifests, not raw credentials or token values.
Account creation, CAPTCHA and authentication are user-controlled prerequisites.

## Physical Android and resource acceptance

Test the final APK on representative low/high-memory Android devices, including
the advertised minimum API, current API, relevant 32-bit ABI and 16 KiB page-size
configuration. Record the actual device and APK hashes. Repeat the emulator
workflows using a real SAF client, display rotation, large fonts and a screen
reader. Exercise permission revocation, disk-full, OS process death and genuine
Keystore invalidation with a recoverable encrypted backup.

Record system job/alarm/foreground-service and wake-lock traces through charging,
metered/unmetered transitions, airplane mode, Doze, reboot, force-stop/reopen and
the applicable foreground-service/quota deadline. No wake lock is an exemption
from platform restrictions. A denied background start must remain an honest
deferred/error result, never successful synchronisation.

For battery work, compare exact old/new inputs using the same datasets, network,
thermal conditions and controlled idle/screen state. Separate cold/warm runs,
repeat observations and retain variability. Measure CPU, native RSS/PSS, child
count/lifetime, wakeups/locks, network/authentication calls and energy. A Windows
wall-clock comparison is not Android battery evidence. The optional host harness
is `scripts/Measure-NativeBisync.ps1 -OldBinary <old> -NewBinary <new>`.

Production promotion additionally requires the approved release certificate and
an explicit merge/release decision. Debug APKs use a separate package and storage;
they are not an in-place update of a production installation.
