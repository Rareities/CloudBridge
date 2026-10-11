# Integration results, 11 October 2026

Status: tested development candidate, not a production release. This supersedes
the first-phase evidence in `BISYNC_VALIDATION.md`. The delivery manifest records
the application commit and exact APK hashes. The packaged engine is
`719ed66b9e7e6a76b9bfe3fe1ae194f89ea5ccc9`; subsequent engine commits only extend
the portable acceptance script. No personal Proton credentials were used.

## Improvements in this integration phase

Configuration-session admission now excludes incompatible RC and CLI work.
Failed startup releases its permit; a live child retains ownership until actual
exit and cleanup. Confirmed RC results are claimed once and cannot be replaced
by a simultaneous shutdown failure. Provider streaming waits for native exit;
RC starts lazily, retires after idle grace and restarts off the main callback.

Ready-task previews preserve the baseline, use private listing/identity copies
and expose timestamped reports with filenames. Queue requests are bound to task
revisions. The editor preserves new tasks across recreation, retains unavailable
remotes for explicit correction and validates current-authority folder URIs.
Task execution resolves alias/crypt/cache wrappers from configuration instead of
a lossy backend integer, including ordinary and cloud-to-cloud sync/copy callers.

Engine locks cover reversed endpoint pairs and actual listing basenames. A
durable identity record binds listings to exact ordered endpoints, preventing
canonical filename collisions from reusing another pair's baseline. Only a
successful explicitly requested resync binds a new identity. Unbound old state
requires reviewed initialisation; the app never repairs it with automatic force.
The Proton bridge rejects partial/ambiguous directory results and preserves
real reader cleanup. Listener policy, DNS/proxy formatting, redaction,
notification privacy and native provenance verification were tightened.

## Executed checks

| Check | Result | Scope |
| --- | --- | --- |
| Host Android tests | 129 tests in 33 suites; zero failures, errors or skips | Native encryption, state/restore, task editing, session, RC races and existing regressions |
| Root lint | Passed with unchanged baselines | App: 179 warnings; six errors and 428 warnings still filtered by its existing baseline |
| Clean build | Four ABI debug APKs, universal and test APK built | App 1.0.3; JDK 17 and local Go 1.27.1; engine v1.76.0 development label |
| APK verification | All five application APKs passed | Signatures, embedded engine identity, native byte hashes and ELF architectures |
| API 35 x86_64 emulator | Eleven distinct cases across nine explicit phases passed | Native bisync, actual WorkManager/charging/cancellation, provider, GUI, restart and encrypted crash recovery |
| Native CLI acceptance | Eighteen subprocess steps across seven disposable cases passed on Windows | Two-way edits, same-size and delete/modify conflicts, thresholds, missing roots/sentinels, concurrent and forced-stop recovery |
| Listener security | Forty checks; zero failures | Authentication, methods, traversal, log canaries, TLS and rejection of untrusted certificates |
| Engine GitHub CI | Runs 38115655071 and 38115655068 passed | Linux package, companion/fake-server, race, static and complete subprocess checks |

The GUI case uses the actual editor, WorkManager and packaged executable, with
no substituted worker, foreground updater or remote-discovery implementation.
It creates a task, previews, recreates the activity, approves initialisation,
previews a ready baseline, displays filenames, transfers both directions,
rejects `--force` and creates a schedule. A repeated GUI-only run corrected the
screenshot timing to capture settled frames; production APKs were unchanged.
Its separate test-APK hash is retained alongside the full-suite evidence.

The other emulator phases test revision rejection, same-size conflict and
ordinary/excessive deletion, native-pipe cancellation, substituted-binary
refusal, real charging transitions, RC idle retirement, document-provider
mutations, force-stop/reopen scheduling and interrupted encrypted record recovery.

## Artifacts and efficiency

Fresh packaging removed stale space from incremental APK outputs. ARM64 is
40,780,962 bytes; x86_64 is 44,348,256 bytes; universal is 134,568,610 bytes.
Debug uses `de.schuelken.cloudbridge.debug` with separate application storage.
These packages are not signed production updates.

RC idle retirement is exercised on the emulator, but no device energy saving is
claimed. Reproducible host timing measurements are retained separately with their
exact inputs; they are not Android/Proton-network or thermal acceptance results.

## External gates remain open

Live disposable-Proton/official-client interoperability, actual long-lived refresh
and revocation, account/crypto migration matrices, physical API/ABI and 16 KiB
page-size devices, genuine foreground-service/quota limits, Private DNS/VPN/custom
trust behaviour and controlled battery measurements remain unverified.

Photos, Shared With Me, instant event-driven sync, remote revision UI, desktop,
optional presets and biometric-per-run unlocking are not claimed as implemented.
See [INTEGRATION_ACCEPTANCE.md](INTEGRATION_ACCEPTANCE.md) for exact reproduction,
plan comparison and live/device sequences. Both PRs remain draft; these tests
do not authorise a merge or production release.
