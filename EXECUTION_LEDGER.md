# CloudBridge + rclone execution ledger

This ledger records implementation evidence for `CloudBridge-rclone-Luna-Master-Handoff.md`.
It is maintained separately from the source checkout while the projectless task is being
bootstrapped, and is intended to move into the reviewed repository documentation once a
real Git checkout is available.

## Standing instructions

- The complete handoff at `work/CloudBridge-rclone-Luna-Master-Handoff.md` is authoritative.
- Work one bounded package at a time; WP05 is the next package after the completed WP04 entry.
- Preserve Bisync, Proton Drive, scheduling, Obsidian and useful CloudBridge functionality.
- Fix defects at their owning layer and test rclone independently before app integration.
- Missing device or live Proton access is `NOT RUN`, never a pass.
- Use disposable test data only. `Proton:RoundSync-Test` is not permission to delete an existing directory.
- The final app integration must use Rareities/rclone at an immutable, reproducible revision;
  a moving branch or silent fallback to `thies2005/rclone` is not acceptable.

## 2026-09-23 — WP00 baseline refresh

### Source identity

| Component | Remote | Branch | Current head evidence | Local source |
|---|---|---|---|---|
| CloudBridge | `https://github.com/Rareities/CloudBridge.git` | `master` | `c492876258ca841232229249519abe92ff77c3a4` from GitHub branch API | `work/CloudBridge` extracted from the master archive |
| rclone | `https://github.com/Rareities/rclone.git` | `master` | `1583cce1e28340e5d064ed955179f5f2b31e7757` from GitHub branch API | `work/rclone` extracted from the master archive |

Archive SHA-256 values:

- CloudBridge master archive: `C849E8C5307E58727AA4CB497B8DD4383191FC9994BE4E6FC5A04ED7D96B2324`
- rclone master archive: `9CE48D71455F76CECEC498DE0102E11358FDEA5CCEAEA842D08C6F5555B17208`
- handoff: `7179ECAFD7D142D478DD46A0495AC1D4D2C57F431E70703F13B7D77359D791B3`

GitHub refresh on 2026-09-22/23 returned no open or closed PR entries and no workflow runs
for either Rareities repository. This is current connector evidence, not proof that CI is
configured or that the source builds.

The upstream rclone master moved beyond the handoff snapshot: `rclone/rclone` is now
`1e92520076ccc319fdae29e6fdc6a75bd523b5a2`. GitHub comparison of Rareities `1583cce…` to
that upstream head reports 170 upstream commits with no commits unique to the Rareities side
(`merge_base` is `1583cce…`). This supports a possible fast-forward review, but does not
authorize blindly replacing the fork or pushing remote history. The current upstream and
Rareities `go.mod` files both require Go 1.26.0.

The bundled Git executable has no `git-remote-https` helper. Direct archive download required
approved network access. The archives did not contain upstream `.git` history, so each source
tree was initialized locally from the verified archive and given an evidence-only baseline
commit; those local commits are not substitutes for the remote commit SHAs above:

- CloudBridge local import: `800bbc19cf529beffb84ea8336dd27fc68116e13`
- rclone local import: `b9ba7bb3bdd75ee01643c6d1dae3024fcd6cba61`

No user changes were discarded. Do not push these archive-import commits as if they were
upstream history.

### Current app integration

`work/CloudBridge/gradle.properties` currently contains:

- `de.schuelken.cloudbridge.rCloneRepoUrl=https://github.com/Rareities/rclone.git`
- `de.schuelken.cloudbridge.rCloneRef=1583cce1e28340e5d064ed955179f5f2b31e7757`
- app Go requirement `1.26.0`
- rclone version `1.76.0`
- NDK `29.0.14206865`, compiler/toolchain API `33`, min/compile/target SDK `23/36/36`

`work/rclone/go.mod` requires Go `1.26.0`. Updating the app pin to Rareities/rclone therefore
requires a deliberate toolchain/build compatibility change and independent engine validation;
it must not be hidden behind a moving ref or a fallback URL.

The local integration pin is now recorded in CloudBridge commit `98044da`:

- repository: `https://github.com/Rareities/rclone.git`
- immutable ref: `1583cce1e28340e5d064ed955179f5f2b31e7757`
- declared engine version: `1.76.0`
- declared minimum Go version: `1.26.0`
- missing repository/ref properties now fail the Gradle rclone configuration instead of
  silently falling back to an unpinned upstream source

This is a source/configuration pin, not integrated Android acceptance. The app's native
artifact provenance and integrated behavior remain `NOT RUN` because the Android NDK,
`local.properties`, and a clean fetch of the pinned repository are not available in this
workspace.

### Environment and test gates

| Gate | Result | Evidence / consequence |
|---|---|---|
| Read repository instructions | PASS | CloudBridge and rclone `AGENTS.md` read in full before source changes |
| Refresh repositories, PRs and CI | PASS | GitHub API evidence recorded above |
| Source archive acquisition | PASS with limitation | Immutable branch SHA and archive hash recorded; no local Git history |
| Go toolchain | PASS | Official Go 1.26.8 archive; SHA-256 `B92C3B2ADAE85A11BA71FE7216DAF0D84E82AF4C8AB6C5625807F28622043A59`; satisfies rclone `go 1.26.0` |
| JDK / Gradle launcher | PASS | Temurin JDK 17.0.20.1 archive; local SHA-256 `E53A79C3C3D86865BD7E787903884331068E71321714FFD44F145785AFFC7CB0`; Gradle wrapper 8.13 runs successfully |
| Standalone rclone build | PASS with limitation | `go build -buildvcs=false ./...`; normal VCS stamping is unavailable because the source is an archive import |
| rclone sync/operations tests | PASS | `go test ./fs/sync ./fs/operations` |
| rclone Proton/Internxt tests | PASS | `go test ./backend/protondrive ./backend/internxt` |
| rclone broad test sweep | INCOMPLETE / environment failures | `go test ./...` reached source tests but was stopped after Windows test-server scripts were missing, symlink privilege tests failed, and WebDAV range behavior failed; not a clean pass |
| CloudBridge -> Rareities/rclone configuration pin | PASS with limitation | Commit `98044da` uses the immutable Rareities ref and fail-closed missing-property checks; `:rclone:tasks` and `:rclone:properties` pass under Gradle 8.13/JDK 17 and print the exact URL/ref/version; native compilation is NOT RUN |
| Android unit/lint/debug build | PARTIAL | CloudBridge 36 JVM unit tests and lint pass under JDK 17/Gradle 8.13; the debug APK/native build remains NOT RUN because the NDK and `local.properties` are unavailable |
| Release/R8/signing/ APK inspection | NOT RUN | No compatible Android build toolchain or signing evidence |
| Samsung Galaxy S26 / One UI acceptance | NOT RUN | No acceptance device access in this environment |
| Live Proton Drive disposable-area tests | NOT RUN | No Proton credentials or approved disposable remote area |

### WP00 acceptance status

Baseline identity, current app pin, repository instructions, CI/PR refresh and gap list are
recorded in the local evidence commits above. Standalone engine build and focused safety,
Proton and Internxt tests now have evidence, and the Gradle configuration resolves the exact
Rareities URL/ref/version under JDK 17. WP00 build/test acceptance remains incomplete because
the Android NDK, acceptance device/provider access and full upstream Git metadata are
unavailable. The app pin is already changed locally in the pre-existing `98044da` commit; this
WP01 update does not change it. Native artifact provenance and integrated behavior are not
claimed. WP01 engine reconciliation follows below.

## 2026-09-23 — WP01 engine reconciliation

### Reconciliation evidence

The fresh GitHub comparison is recorded in local evidence commits `30ba9ba` (CloudBridge)
and `19d72e8` (rclone). Rareities/rclone master is
`1583cce1e28340e5d064ed955179f5f2b31e7757`; upstream `rclone/rclone` master is
`1e92520076ccc319fdae29e6fdc6a75bd523b5a2`. The comparison reports `ahead_by=170`,
`behind_by=0`, `total_commits=170`, and merge base equal to the Rareities head. No fork-only
commits are reported. Both `go.mod` files require Go `1.26.0`.

The authoritative rclone source remains the verified Rareities master archive, represented by
the local evidence baseline `b9ba7bb3bdd75ee01643c6d1dae3024fcd6cba61`. The local
`19d72e8` commit is documentation only; it is not a substitute for the remote Rareities
commit and no rclone source patch was added. Existing workspace caches were used with the
verified Go 1.26.8 toolchain; no duplicate checkout or cache was created.

### Independent engine validation

Against the unchanged Rareities source, with `GOTOOLCHAIN=local`, `GOWORK=off`, the existing
workspace module/build caches, and `-mod=readonly`:

- `go version` — PASS: `go1.26.8 windows/amd64`.
- `go build -buildvcs=false -mod=readonly ./...` — PASS, exit 0.
- `go test -mod=readonly ./fs/sync ./fs/operations` — PASS, exit 0 (Go reported cached results).
- `go test -mod=readonly ./backend/protondrive ./backend/internxt` — PASS, exit 0 (Go reported
  cached results).

The earlier broad `go test ./...` result remains `INCOMPLETE / environment failures` as
recorded above: missing Windows test-server scripts, symlink privilege failures, and a
WebDAV range behavior failure. Live Proton, Samsung acceptance, Android integration, and
remote CI remain `NOT RUN`.

### Bounded decision

No safe rclone engine source change is justified by the current evidence. The candidate is a
large unreviewed fast-forward rather than a targeted fix, the archive checkout has no full
upstream Git history for a reviewable local fast-forward, no fork-only patch or reproducible
defect was identified, and the unchanged engine passes the required independent build and
focused safety/provider gates. The 170 upstream commits are therefore not blindly ported,
cherry-picked, or silently substituted.

The CloudBridge checkout already contains the exact Rareities URL/ref in `gradle.properties`
from the pre-existing `98044da` commit. No further URL/ref change was made in WP01. The
configuration pin is not integrated Android acceptance: native compilation, remote CI, device
acceptance, and live Proton testing remain open or `NOT RUN` as recorded above. Gradle
configuration evaluation is now independently evidenced; it does not prove native artifact
provenance or app behavior.

### WP01 status

WP01 is **COMPLETE for the bounded reconciliation decision**: current Rareities/rclone is
validated and retained without a source change, with provenance, tests, residual risks and
the app-integration boundary recorded. This is not a claim that the 170 upstream commits,
Android integration, live Proton workflow, acceptance device, or full test suite are complete.
WP02 secure execution inputs and diagnostics follows below.

## 2026-09-23 — WP02 secure execution inputs and diagnostics

### Scoped implementation

CloudBridge commit `f3bd473` implements the bounded WP02 input and diagnostic hardening
scope. `LogRedactor` is now the shared sink for formatted application logs, file logs, sync
logs and rclone stderr. It redacts configured/authentication values, bearer tokens,
`content://` URIs and absolute paths, and bounds diagnostic text at 16 KiB. Sync-log growth
is capped at 1 MiB. The audited configuration, provider, worker and update paths no longer
send their rclone/config payloads or exception details through raw logging sinks.

The exported shortcut route now requires the exact sync action, a typed positive task ID and
a per-task capability token. Tokens are issued when pinned shortcuts are created and checked
before a task is queued; invalid or legacy shortcuts fail closed. Existing shortcuts created
before this capability was added must be recreated, which is an intentional safe-compatibility
boundary.

### WP02 verification

| Gate | Result | Evidence |
|---|---|---|
| CloudBridge unit tests | PASS | `:app:testOssDebugUnitTest --no-daemon -Pkotlin.compiler.execution.strategy=in-process -x :rclone:buildAll`; 22 tests, exit 0 |
| CloudBridge lint | PASS with pre-existing findings | `:app:lintOssDebug --no-daemon -Pkotlin.compiler.execution.strategy=in-process -x :rclone:buildAll`; task passed; existing warnings/baseline findings remain |
| Diff hygiene | PASS | `git diff --check` passed before commit |
| Native Android artifact | NOT RUN | Android NDK and `local.properties` are unavailable; no APK or native rclone provenance was claimed |
| Samsung / live Proton acceptance | NOT RUN | No Galaxy S26 device or approved disposable Proton area/credentials are available |

### WP02 acceptance status

WP02 is **COMPLETE for the audited CloudBridge shortcut and diagnostic sinks**. The unit and
lint gates pass, and the source commit is persisted. This does not claim that every process
launch/input path has been audited or that process lifetime, cancellation, output draining
and result truth are complete; those broader execution concerns remain owned by WP05 and the
later scheduling/result packages. Native compilation, device acceptance and live Proton
workflow remain `NOT RUN`.

## 2026-09-23 — WP03 config and database recoverability

### Scoped implementation

CloudBridge commit `3ab1d6b` implements the bounded WP03 recoverability scope. Database and
preference imports now parse and validate the complete bounded payload before mutation,
including optional legacy arrays, typed/ranged preference values and cross-record references.
Database replacement is one SQLite transaction; imported IDs are never reused and filter,
follow-up and trigger references are remapped to the fresh row IDs. A failed insert rolls the
transaction back instead of leaving a partially imported task set.

ZIP backup import now stages and validates the config before changing any store, snapshots the
existing database/preferences/config, and restores those snapshots if a later store update
fails. Config entries and JSON are bounded, staged files stay inside the private app directory,
and temporary files are cleaned on missing-entry and extraction failures. The old plaintext
decrypt-over-config behavior was removed: a validated password is checked through rclone and
stored only as AES-GCM ciphertext wrapped by an Android Keystore key. Keystore/key-loss errors
leave the encrypted config and ciphertext intact and require explicit recovery. Rclone and
RcloneRcd include the recovered password in their process environment without adding it to
backup JSON or diagnostics.

### WP03 verification

| Gate | Result | Evidence |
|---|---|---|
| CloudBridge unit tests | PASS | `:app:testOssDebugUnitTest --no-daemon -Pkotlin.compiler.execution.strategy=in-process -x :rclone:buildAll`; 30 tests, 0 failures/errors, exit 0 |
| CloudBridge lint | PASS with pre-existing findings | `:app:lintOssDebug --no-daemon -Pkotlin.compiler.execution.strategy=in-process -x :rclone:buildAll`; task passed; 91 warnings and 6 baseline-filtered errors were reported, with no task failure |
| Import validation coverage | PASS (bounded) | Malformed/oversized imports, duplicate IDs, unknown references, legacy theme forms and invalid preference ranges are covered by `ImporterTest` and `SharedPreferencesBackupTest` |
| Diff hygiene | PASS | `git diff --check` passed before commit |
| Native Android artifact | NOT RUN | Android NDK and `local.properties` are unavailable; no APK or native rclone provenance was claimed |
| Crash/disk-full fault injection and live Keystore | NOT RUN | No device or fault-injection harness is available in this workspace |
| Samsung / live Proton acceptance | NOT RUN | No Galaxy S26 device or approved disposable Proton area/credentials are available |

### WP03 acceptance status

WP03 is **COMPLETE for the bounded import/rollback and production secret-storage
implementation**. The source and tests are persisted, but full crash/disk-full fault injection,
live Android Keystore behavior, native compilation, device acceptance and live Proton workflow
remain `NOT RUN`. Importing an encrypted config whose password differs from the currently
cached password remains an explicit unlock/recovery boundary, not a silent downgrade.

## 2026-09-23 — WP04 authoritative profiles and run state

### Scoped implementation

CloudBridge commit `acc4f5c` adds a versioned profile/run state layer without replacing the
legacy task UI in one unsafe migration. Legacy numeric tasks are mapped to stable UUID profile
rows with semantic revision, explicit mode, endpoint/settings snapshot, SHA-256 fingerprint,
engine pin and separate readiness. The first migration is deterministic; a retired profile's
UUID cannot be silently reused after a delete/recreate cycle. Legacy Bisync directions 5/6 and
unknown directions become repair-required rather than being coerced into one-way sync.

Run rows capture the requested mode, profile revision/fingerprint, endpoint/settings snapshot,
engine pin, requested/due/start/finish times, owner token/generation, cancellation flag and
nullable result counters. A partial unique index prevents more than one queued/preflight/running
owner for a profile. Profile edits and deletes invalidate active ownership in the same SQLite
transaction; a worker claim rechecks revision/fingerprint/readiness before native launch. App
startup reconciles preflight/running rows conservatively to interrupted/recovery state.

Task create/edit/delete and full backup import update the profile ledger transactionally. The
WorkManager adapter now queues a durable run ID and owner token, records confirmed native exit
outcomes, returns failure for failed syncs, and refuses stale or repair-required claims. Older
WorkManager requests without the new fields use a compatibility adapter that creates a durable
run before claiming it.

### WP04 verification

| Gate | Result | Evidence |
|---|---|---|
| CloudBridge unit tests | PASS | `:app:testOssDebugUnitTest --no-daemon -Pkotlin.compiler.execution.strategy=in-process -x :rclone:buildAll`; 36 tests, 0 failures/errors, exit 0 |
| CloudBridge lint | PASS with pre-existing findings | `:app:lintOssDebug --no-daemon -Pkotlin.compiler.execution.strategy=in-process -x :rclone:buildAll`; task passed; 98 warnings and 6 baseline-filtered errors were reported |
| Profile/run invariants | PASS (model) | Profile/run state tests cover stable legacy mapping, mode non-coercion, fingerprint changes, fail-closed unknown states and explicit active/terminal states |
| Transactional legacy writes | IMPLEMENTED / device DB test pending | Task writes, import replacement, profile invalidation and run claim/finish use SQLite transactions; Android instrumentation/fault-injection execution was unavailable |
| Native Android artifact | NOT RUN | Android NDK and `local.properties` are unavailable; no APK or native rclone provenance was claimed |
| Samsung / live Proton acceptance | NOT RUN | No Galaxy S26 device or approved disposable Proton area/credentials are available |

### WP04 residuals and acceptance status

WP04 is **COMPLETE for the bounded profile/run ownership implementation**, but not full
application acceptance. Ephemeral file-explorer work still uses its compatibility path, UI
readiness presentation is not yet migrated, and the complete native lifetime/stream/cancel
boundary remains WP05. Android database migration, process-death, duplicate-dispatch and
terminal-write fault tests require instrumentation or a device. Bisync execution remains
intentionally blocked until its native preflight/guard package proves safe semantics.

## 2026-09-23 — WP05 native lifetime, first operation-family slice

CloudBridge commit `a3c87f0` is an **in-progress WP05 slice, not package acceptance**. A
`NativeExecutionHandle` now owns dual-pipe bounded draining, cancellation, timeout, confirmed
exit/reap, single terminal outcome and release of attached resources only after reap. Late
confirmed exit releases resources even if an earlier bounded wait was `UNCONFIRMED`; the run
outcome remains conservatively unconfirmed. Sync and ephemeral workers, file-open download,
streaming and thumbnail servers, and the RCD process now use the handle. Sync reports nonzero
native exit as failure and marks a profile recovery-required if exit cannot be confirmed. RCD
shutdown no longer releases its transfer locks before a confirmed stop.

The migration deliberately uses owned adapters over existing `Rclone` process-returning
methods. Listing, hashing, interactive config, OAuth/reconnect, Internxt reauth and several
direct `Rclone` helpers still own raw `Process` objects. The same-operation dual-owner
boundary must be removed family by family before WP05 can pass. Unconfirmed ephemeral or
serving exits lack durable cross-operation coordination; this is an open safety defect, not
evidence of WP05 completion. Concurrent stop/finish, process-death and lifecycle rotation
still need instrumentation. The next slice must audit every raw `Process` caller and preserve
its interactive pipe semantics while moving it under one owner.

### WP05 slice verification and source refresh

| Gate | Result | Evidence |
|---|---|---|
| CloudBridge JVM tests | PASS | `:app:testOssDebugUnitTest --offline --no-daemon -Pkotlin.compiler.execution.strategy=in-process -x :rclone:buildAll`; 41 tests, exit 0 |
| CloudBridge lint | PASS with existing baseline | `:app:lintOssDebug` in the same Gradle run; 98 warnings and 6 baseline-filtered errors, task exit 0 |
| Native-handle edge tests | PASS (JVM model) | Full stderr pipe, cancel/reap/resource order, bounded unconfirmed exit, late reap and no-deadline wait |
| Diff hygiene | PASS | `git diff --check` passed before source commit |
| Repository heads | UNCHANGED at refresh | `Rareities/CloudBridge` master `c492876258ca841232229249519abe92ff77c3a4`; `Rareities/rclone` master `1583cce1e28340e5d064ed955179f5f2b31e7757` |
| Open PRs / CI | NONE OBSERVED | GitHub connector returned no open user PRs, commit statuses or PR-triggered workflow runs on either head; this is not a CI pass |
| Native Android artifact | NOT RUN | NDK and `local.properties` unavailable; `-x :rclone:buildAll` skips native compilation |
| Galaxy S26 / live Proton | NOT RUN | No device, firmware/API observation, credentials or verified disposable test area available |

Two sandboxed Gradle retries failed because a Gradle worker was denied access to a cached
Datastore JAR; the identical offline test/lint command passed outside that sandbox. These
were environment failures, not passing runs. No repository was pushed and no PR created.

### WP05 continuation: metadata commands and fault coverage

CloudBridge commit `c67488d` moves `link`, `md5sum`, `sha1sum` and `--version` text commands
to one bounded native handle that drains stdout/stderr concurrently. The prior `waitFor()`
before stdout read could deadlock on full output. The forced-kill path now reflects the
public `Process` API rather than an inaccessible concrete Android process class. JVM tests
cover forced-kill timeout, concurrent stop/finish sharing one terminal result, and failed
launch. The complete unit suite is **44 PASS**; Android lint still passes with 98 warnings
and the 6 baseline-filtered errors. This is another bounded slice, **not WP05 acceptance**.

CloudBridge commit `416a237` removes a listing-path debug log of the entire native process
environment. A password containing spaces could leave a suffix visible after regex-based
redaction, so process environments must not be logged at all. This one-line source change
passed `git diff --check`; a post-change Gradle run passed 44 JVM tests and lint (98 warnings,
6 baseline-filtered errors). No live credential was used.

The original CloudBridge checkout has an archive-derived root (`800bbc19`). Its package
commits were replayed onto `codex/luna-implementation`, which descends from the real GitHub
master `c4928762`; the original checkout remains rollback evidence. The rclone WP01 decision
ledger was likewise replayed onto `codex/luna-engine`, descended from Rareities/rclone
`1583cce1`. The two expected modify/delete conflicts were resolved by adding the local
execution/requirements ledgers, which do not exist on either remote master. Other than
line-ending/executable-bit differences in four scripts, the CloudBridge application tree
matches the archive checkout. No branch was pushed and no PR created; do not force-push the
archive-derived history. Commit IDs above refer to the history-preserving branches.

The history-preserving CloudBridge branch independently passed
`:app:testOssDebugUnitTest :app:lintOssDebug --offline --no-daemon
-Pkotlin.compiler.execution.strategy=in-process -x :rclone:buildAll` (44 JVM tests;
lint task passed with 85 warnings and 6 baseline-filtered errors). `git diff --check
origin/master...HEAD` passed. Native rclone compilation, debug/release APKs, R8, Galaxy
firmware/API acceptance and live Proton remain `NOT RUN`. This is a reviewable local branch,
not a completed application or authorization to push incomplete WP05 work.

### WP05 continuation: listing/config drains and stop/restart safety

CloudBridge commit `23b973e` moves directory listing and cached config-dump reads to a
bounded text capture that drains both native pipes concurrently. Listing output is limited
to 16 MiB and config JSON to 4 MiB; over-limit, incomplete-drain, timeout and cancelled
results fail closed. Local/alias `lsjson` exit code 6 remains the explicit compatibility
exception only when output is complete. The handle now refuses to report success if its
output pump did not finish within a bounded grace period. JVM tests cover a full stdout
pipe and a late output callback; these do not substitute for large-listing device tests.

The same commit serializes ephemeral launch against stop requests, preventing a worker
stopped before process assignment from starting a later transfer. RCD shutdown now attempts
reap off the service main thread. It persists an exit-unconfirmed guard before teardown,
retains the guard across service recreation, refuses a second RCD launch, and clears the
guard only after confirmed exit. A late reap can be observed on a retry. A code review
identified the pre-existing/new stop-race and service-recreation risks. Follow-up review
confirmed those three fixes but found that an app-process death can leave the persisted RCD
guard blocked indefinitely. This is an intentional fail-closed state until a recovery path
can prove the old native process has ended; automatic unguarded clearing is prohibited.
Android lifecycle instrumentation and an explicit verified recovery flow remain open gates.

Verification on `codex/luna-implementation`: **46 JVM tests PASS**, `:app:lintOssDebug`
**PASS** with its existing baseline, and `git diff --check` **PASS**. The history-preserving
`codex/luna-engine` branch independently passed `go build -buildvcs=false -mod=readonly
./...` and focused sync/operations/Proton/Internxt tests. Native APK, R8, Galaxy S26 and
live Proton tests remain **NOT RUN**. Interactive config, OAuth/reconnect, binary streaming,
other direct `Process` helpers, config lease, cross-operation locking and device lifecycle
tests remain WP05 work; do not advance to WP06 or claim WP05 acceptance.

CloudBridge commit `714a4e0` additionally moves durable `markRunning` before native sync
launch, serializes both sync and ephemeral launch with stop requests, and makes receiver
registration/unregistration idempotent across stop-before-start and connectivity callbacks.
The post-change branch again passed **46 JVM tests** and Android lint (85 warnings, 6
baseline-filtered errors). WorkManager/device stop-before-launch and process-death tests
remain **NOT RUN**; the JVM suite cannot prove those Android lifecycle interleavings.

CloudBridge commit `73577c2` migrates noninteractive `about`, `config dump` and encrypted
config password validation to the owned native handle. JSON outputs are bounded; password
validation drains output without persisting or logging config plaintext. It removes the
independent raw-process/drainer thread pair from `decryptConfig`. The branch again passed
**46 JVM tests** and Android lint (85 warnings, 6 baseline-filtered errors). This does not
cover interactive config creation/update, OAuth/reconnect or binary pipe streaming, and no
live Keystore/Proton validation was run.

CloudBridge commit `a4aed7c` moves the `lsd` directory probe under the same owned
native lifetime. It drains stdout/stderr concurrently but retains only a bounded error
category needed by the session guardian, not raw provider stderr. The existing network
versus non-network classification remains explicit and has a JVM regression test. The
post-change branch passed **47 JVM tests** and Android lint (85 warnings, 6
baseline-filtered errors). Session-guardian behavior on live Proton, timeout and process
death remains **NOT RUN**.

### WP05 execution milestone — 2026-09-23

CloudBridge commit `0337d85` migrates interactive config creation/update, OAuth/reconnect,
metadata/mutation helpers, settings diagnostics and VCP streaming to the shared
`NativeExecutionHandle`. Interactive pipes have exclusive ownership and are handed back to
bounded drainers before reap; upload/download pipes are owned and cancellation-aware. OAuth's
fixed-port reservation now stops and confirms reap of the previous process before launching
the next attempt. Reconnect cleanup waits for the runner and process. Prompt/password/input
transcripts and raw process environment logging remain excluded. Raw `Process` construction
and lifecycle methods are now confined to private Rclone launch plumbing and the central
handle; unrelated `android.os.Process` calls are process self-diagnostics/termination.

Validation: on 2026-09-23, `:app:testOssDebugUnitTest :app:lintOssDebug --no-daemon
-Pkotlin.compiler.execution.strategy=in-process` passed; the XML report contains 53 tests,
0 failures and 0 errors across 11 suites. Lint's configured baseline continues to filter
pre-existing findings; no new unfiltered error was reported. `:rclone:buildAll` was up to
date in that incremental run; a prior full build compiled arm64-v8a, armeabi-v7a, x86 and
x86_64. Standalone Go build, focused sync/operations/Proton/Internxt tests, Proton tests
repeated 100 times and `go vet` passed on `codex/luna-engine`.

The provisional universal debug APK contains all four ABIs and SHA-256
`CA9DD6D6B413359A5A4E0CBA1F64DA2BD4993CD6FECA2BE3288ADBDDC9FC6A27`. It is debug-signed and
embeds rclone base pin `1583cce1e28340e5d064ed955179f5f2b31e7757`; it is not a release
candidate. No local release keystore/signing environment is configured. No Android device,
AVD or system image is present, so lifecycle instrumentation and Galaxy S26 / One UI
acceptance are `NOT RUN`. Live Proton/official-client tests are also `NOT RUN`; no Proton
credentials or data were used. An unconfirmed RCD exit intentionally remains guarded, but
post-app-process-death recovery is still open.

WP05 implementation is sufficiently migrated to proceed to WP06, while lifecycle/recovery
and real-device acceptance remain explicit open gates. Do not treat Gradle `UP-TO-DATE` as a
fresh test execution; 53/53 is from the last executed full test result.

## 2026-09-23 — WP06 endpoint identity and durable conflict ownership

**Implementation commit:** `6ba86b6` (`Add durable endpoint conflict claims`).

### Package record (13-field format)

1. **Objective:** one app-side, durable conflict boundary for native rclone commands, RCD and
   config transactions.
2. **Scope:** app process/RC launch paths, backup import, SQLite claims and endpoint identity.
3. **Out of scope:** backend protocol/cache changes, device acceptance, Bisync UI, auto-recovery.
4. **Preconditions:** WP04 repositories and WP05 `NativeExecutionHandle` are in place;
   CloudBridge still pins immutable Rareities/rclone base `1583cce…`.
5. **Design:** segment-aware SHA-256 path identity, canonical local path, conservative global
   fallback, SQLite transaction claims, exact config-content fingerprint plus post-claim
   revalidation before operation start.
6. **Safety invariants:** no plaintext path persistence; ambiguous roots serialize globally;
   durable claims do not auto-expire; uncertain process/RCD completion never releases ownership;
   config import holds ownership through rollback.
7. **Implementation:** database version 10→11 adds `resource_claims`; Rclone and RcloneRcd
   acquire before process/request, async RCD jobs hold to finished status, transport/5xx/unknown
   failures retain claims, unclaimed native launch is globally quarantined.
8. **Existing code reused:** WP05 native owner, RCD job handlers and config snapshot/restore.
9. **Code retired:** no feature-local lock substitutes; process creation remains behind the
   native owner integration.
10. **Failure behaviour:** unknown/conflicting scope fails closed; config snapshot race aborts
    before operation; failed release leaves the claim row for recovery.
11. **Tests:** `:app:testOssDebugUnitTest :app:lintOssDebug
    :app:compileOssDebugAndroidTestJavaWithJavac --no-daemon
    '-Pkotlin.compiler.execution.strategy=in-process' -x :rclone:buildAll` PASS on 2026-09-23.
    13 JVM suites/62 tests, 0 failures/errors, 1 Windows symlink skip; lint PASS with existing
    baseline (99 warnings; baseline filtered 2 errors/438 warnings/1 hint; 65 stale entries).
    Instrumentation compile was UP-TO-DATE; no device execution. `git diff --check` PASS.
12. **Acceptance status:** implementation milestone complete; full concurrency, v10→11 migration,
    process-death recovery, removable-storage and Galaxy S26 tests remain NOT RUN. Native task
    `:rclone:buildAll` was excluded; no current-WP06 APK/native provenance is claimed.
13. **Rollback point:** bounded package rollback only; do not delete persisted claims; DB v11
    downgrade is unsupported.

The 2026-09-23 GitHub refresh still reports CloudBridge master `c4928762`, rclone master
`1583cce1`, no open PRs, and zero recent master workflow runs (not a CI pass). Upstream
`rclone/rclone` master moved to `cfb90e3` on 2026-09-22; it is 174 commits ahead of the fork
base. Review and decide that immutable candidate before native WP07 work; no blind fast-forward.

## Next work

Begin WP07: independently review current Bisync listing completion, lock ownership and stats
aggregation; specify/test the positive absolute and percentage deletion guards before any app
surface. Reconcile the refreshed upstream candidate first. Revisit WP05 lifecycle and WP06
schema/concurrency/RCD-recovery gates later; Galaxy S26, instrumentation and live Proton remain
NOT RUN. Keep the immutable Rareities/rclone pin and use only a verified unique disposable
Proton test area.

## 2026-09-23 — WP01 app-pin adoption and native debug verification

**Implementation commit:** pending; app ref update and evidence are committed together.

### Package record (13-field format)

1. **Objective:** adopt the independently validated Rareities/rclone WP01 result in CloudBridge
   and verify the Android-native integration before app WP07 work.
2. **Scope:** immutable Gradle rclone ref, Go toolchain compatibility, all native ABIs, OSS
   debug APK, JVM unit tests and lint.
3. **Out of scope:** release signing/publication, device or emulator acceptance, live Proton.
4. **Preconditions:** engine branch `codex/luna-engine` published at
   `ec863fdcd9e1ce0d13357f791d5528aab10bf0ec`; GitHub API tree
   `42870c91fd19fd6a7eb2d2ac199a5414bb7b5dc6` equals tested local `ccd9f64` tree exactly.
5. **Design:** retain `https://github.com/Rareities/rclone.git`, pin the immutable published
   commit, and make no floating-branch or unpinned fallback available.
6. **Safety invariants:** source identity is commit-pinned; the local build mirror is accepted
   only after exact tree equality; generated binaries stay out of source commits; debug signing
   is not release provenance.
7. **Implementation:** `gradle.properties` now pins
   `ec863fdcd9e1ce0d13357f791d5528aab10bf0ec`. The validation build used a local source mirror
   at `ccd9f64`, whose tree exactly matches the published remote commit. Temporary build-only
   `go`/`git` executable-path overrides are not in tracked production build files.
8. **Existing code reused:** the app's existing immutable rclone dependency properties and
   four-ABI Gradle native build tasks.
9. **Code retired:** CloudBridge's prior engine ref `1583cce1e28340e5d064ed955179f5f2b31e7757`.
10. **Failure behaviour:** a missing/mismatched immutable source pin remains a build failure;
    no substitute archive or floating branch is accepted. Sandbox-only JDK real-path denial was
    isolated; the authorized build ran outside the sandbox with a path-scoped Git safe-directory
    setting for the temporary clone.
11. **Tests:** on app source commit `4f228a6`, JDK 21.0.8, Go 1.26.8 and Android SDK build-tools
    35.0.0: `:app:assembleOssDebug :app:testOssDebugUnitTest :app:lintOssDebug` PASS; `:rclone:buildAll`
    compiled all four ABIs. JVM XML: 13 suites, 62 tests, 0 failures, 0 errors, 1 skipped for
    Windows symlink capability. Lint task PASS with its configured baseline: 99 warnings shown,
    baseline filtered 2 errors/438 warnings/1 hint; 65 baseline findings are stale. SDK XML-v4
    versus SDK-tooling-v3 and deprecated BuildConfig warnings remain visible. Four per-ABI debug
    APKs and universal APK were produced; universal SHA-256 is
    `C78683CCB981986374879E6F3FE94A580B786DC7899E49023EF625F8927BE7E6` (134,490,595 bytes).
    The APK is debug-signed, contains native rclone and is not a release artifact. Device
    instrumentation and Galaxy S26 acceptance are **NOT RUN**.
12. **Acceptance status:** engine pin/native compile/JVM/lint integration milestone PASS; WP01
    provenance is exact at the source-tree level. The published API-created engine tip is unsigned;
    this, debug-only signing, no CI evidence, and unavailable device/Proton tests are not release
    acceptance. WP07 app implementation remains next.
13. **Rollback point:** revert only the pin/evidence commit to restore the old engine commit;
    keep the validated engine branch and do not rewrite either default branch.

## 2026-09-23 — WP07 fail-closed Bisync preflight milestone

**Implementation commit:** `76a71b4` (`Add fail-closed Bisync preflight gates`).

### Package record (13-field format)

1. **Objective:** establish an immutable-profile-bound, read-only Bisync preflight and durable
   fail-closed result before any preview, initialization, recovery or execution surface exists.
2. **Scope:** strict bounded local/remote listing probes, endpoint/account/root identity,
   selected-filter validation/fingerprinting, comparison capability, config-race detection,
   persistent readiness/reason, engine identity, and additive database v11→v12 migration.
3. **Out of scope:** native state inspection implementation (the probe seam defaults to
   `UNKNOWN`), preview/init/recovery/UI, enabling legacy modes 5/6, and live provider/device
   acceptance. Unsupported provider identities deliberately remain blocked.
4. **Preconditions:** WP01–WP06 implementation commits are present; CloudBridge pins
   `https://github.com/Rareities/rclone.git` at immutable commit
   `ec863fdcd9e1ce0d13357f791d5528aab10bf0ec`, whose tree matches the independently tested local
   engine tree. No moving branch or upstream fallback is used.
5. **Design:** Kotlin validates immutable profile semantics and privacy-safe identities;
   rclone performs the actual bounded, cancellable `lsjson` probes and owns native Bisync
   comparison/deletion behavior. Empty remote path means provider root; SQL NULL endpoint is
   preserved as invalid. Only a compatible native state plus an exact accepted baseline can
   become ready.
6. **Safety invariants:** no mutation in preflight; failed/truncated JSON never becomes an empty
   tree; no exit-code-6 exception or `--ignore-errors`; no overlapping/unknown scope, force,
   auto-resync or silent legacy migration. A missing formerly accepted native state is recovery,
   not fresh initialization. Absolute 25-item aggregate guard and 10% per-path native guard stay
   enforced before mutation; absolute guard cannot be bypassed with `--force`.
7. **Implementation:** database version 12 adds hash-only `bisync_preflight_table`; title-only
   edits do not revise profile semantics, filter content changes do, and filter update/delete
   refresh linked profiles transactionally. `EngineIdentity` includes full pinned SHA. Scans
   reject traversal, duplicate/malformed paths, partial/truncated output and inaccessible roots;
   cancellation reaches the owned process. Legacy migration without explicit confirmation is
   durably blocked. Ready-baseline writes are checked against the exact current profile,
   endpoint, filter, comparison and engine snapshot. NULL endpoint rows remain invalid rather
   than becoming root paths.
8. **Existing code reused:** WP05 `NativeExecutionHandle`, WP06 endpoint claims/config snapshot,
   profile/run repositories, strict process output drain, and the pinned engine's native guard.
9. **Code retired:** profile/run engine version-only identities were replaced with the immutable
   repo+SHA identity; preflight does not reuse browser listing's partial-result/exit-6 behavior.
10. **Failure behaviour:** persist a typed reason and block; unknown native state, ambiguous
    volume/account, unlisted provider identity, missing filter, config race, unsupported
    comparison and path traversal cannot proceed. No fallback initialization or data cleanup.
11. **Tests:** forced full CloudBridge validation on JDK 21/Go 1.26.8/SDK build-tools 35:
    `:app:testOssDebugUnitTest :app:compileOssDebugAndroidTestJavaWithJavac :app:lintOssDebug`
    PASS; 14 suites, 75 tests, 0 failures/errors, 1 Windows symlink skip. Instrumentation source
    compilation PASS, but no instrumentation execution. Lint task PASS with configured
    baseline; report has 92 warnings and 2 hints, existing baseline suppresses 428 warnings and
    2 errors, and 76 baseline entries are stale. `git diff --check` PASS. Standalone pinned
    engine targeted `cmd/bisync` tests PASS, including the integration test proving the aggregate
    cap stops opposite-side deletion propagation even with `--force`; four other boundary/default/
    RC input tests also PASS. Full `go test ./cmd/bisync` was stopped after >100 CPU-seconds with
    no result and is recorded **INCOMPLETE**, not passed. No current-WP07 APK is claimed.
12. **Acceptance status:** implementation milestone is committed and safe to proceed to WP08,
    but full WP07 acceptance remains open: instrumented database migration/durable-state tests,
    the native state inspector, Galaxy S26/One UI and live Proton are **NOT RUN**. Because the
    inspector defaults to `UNKNOWN`, Bisync stays unavailable; no release readiness is implied.
    Account-identity mapping currently supports only explicitly known stable locators, so other
    providers fail closed pending the provider-capability matrix.
13. **Rollback point:** keep the migration additive and do not drop accepted baselines or
    downgrade the database. DB v12 downgrade/old-app rollback is unsupported and must be tested
    with the migration/rollback package before any release; preserve a known-good DB/artifact.

### Next package

Proceed to WP08 with isolated native Bisync state inspection, non-mutating preview, explicit
initialization/recovery actions, and preservation/restore fault boundaries. Keep automatic
execution disabled until native-state compatibility, both-populated/one-empty/conflict cases,
exact retained bytes and migration rollback are proven. WP09 provider/backend work still needs
standalone evidence; device and Proton acceptance stay **NOT RUN**.

## 2026-09-23 — WP08 partial native-state evidence persistence

**CloudBridge implementation commit:** `f920905ffc5a819f404d3e9b564a4f7825050d38`
(`Persist native Bisync recovery evidence`).

### Package record (13-field format)

1. **Objective:** connect the read-only native Bisync state inspector to fail-closed app
   preflight and durably retain sanitized native state and recovery-list validation evidence.
2. **Scope:** app command invocation and response validation; explicit `INTERRUPTED` state;
   additive v12→v13 SQLite columns; conservative migration defaults; evidence round-trip and
   policy tests; immutable rclone pin refresh to the published engine commit.
3. **Out of scope:** preview UI, initialization, recovery execution, run-scoped app backup
   coordinator, user-facing restore, lock/process-kill fault injection, DB downgrade support,
   and live provider/device acceptance. `recoveryListingsValid` is diagnostic evidence only.
4. **Preconditions:** WP07 app commit `76a71b4` and native WP08 state inspector are present.
   CloudBridge now pins `https://github.com/Rareities/rclone.git` at
   `d53551e1722305268c6072263f11066f1278a4a0`; remote tree
   `98c402c28fda112c56b543b3104841435fb8cdf6` equals the locally validated source tree.
5. **Design:** app invokes `bisync --inspect-state` only for the established profile UUID
   workdir; absent/unavailable/invalid paths and malformed/unconfirmed process output remain
   `UNKNOWN`. Status/reason/listing-validity are stored separately from the accepted baseline.
   Existing v12 rows migrate to `UNKNOWN / STATE_NOT_RECORDED / false`; fresh schema uses the
   same additive columns. Only `INTERRUPTED` may retain `recoveryListingsValid=true`, and that
   value never changes readiness or authorizes recovery.
6. **Safety invariants:** inspection is non-mutating; no legacy listing rename, restore, resync,
   initialization, or purge occurs. Unrecognized status/reason is sanitized and blocked.
   Missing profile workdir is not proof of absent state. No existing Bisync execution path is
   enabled by this slice.
7. **Implementation:** committed coordinator/native probe wiring, strict response mapping,
   DB version 13 and v12→v13 `ALTER TABLE` migration, persisted state/reason/validation flag,
   constructor compatibility, and unit/instrumentation regression coverage. App pin and
   profile fixtures use the immutable `d53551e…` ref.
8. **Existing code reused:** WP05 bounded confirmed-exit native runner and cancellation; WP06
   stable profile UUID; WP07 preflight, immutable baseline policy, SQLite repository and native
   state CLI; existing SQLite transaction/migration owner.
9. **Code retired:** the app's previous engine pin `ec863fdcd9e1ce0d13357f791d5528aab10bf0ec`
   is replaced by the immutable tree-verified `d53551e…` pin. The coordinator's placeholder
   state probe is replaced by the native read-only probe; its exception path remains fail-closed.
10. **Failure behaviour:** failed, truncated, oversized, unsupported-version or unconfirmed
    output returns unknown and blocks preflight. Old rows cannot appear initialized or
    recoverable merely because columns were added. Invalid diagnostic strings are stored as a
    constant category, never raw paths/errors.
11. **Tests:** latest app run on JDK 21 / Gradle 8.13 / Android SDK 35 passed
    `:app:testOssDebugUnitTest`,
    `:app:compileOssDebugAndroidTestJavaWithJavac`, and `:app:lintOssDebug` with
    `-x :rclone:buildAll`; XML reports show 14 suites, 76 tests, 0 failures, 0 errors, 1
    Windows symlink-capability skip. Android-test Java compilation includes the v12 migration
    and persistence round-trip cases, but no device/AVD exists to execute them. Lint task passed
    using the configured baseline: 92 warnings reported, 2 errors and 428 warnings filtered,
    76 stale baseline entries. `git diff --cached --check` passed. An earlier full integrated
    run against the same published rclone SHA passed `:rclone:buildAll` for all four ABIs; the
    post-v13 rerun skipped rebuilding that unchanged engine. Full standalone `./cmd/bisync`
    tests, `go vet`, and Android/arm64 build for the native inspector passed as recorded in
    `PATCH_LEDGER.md`. No current-slice APK/R8/release build is claimed.
12. **Acceptance status:** this is a WP08 partial foundation, not package acceptance. Native
    state inspection and durable evidence compile/test at source level; preview, initialization,
    recovery UI/coordinator, durable run-scoped backups, mutation-boundary fault tests, migration
    rollback and end-to-end data-preservation acceptance remain open. Galaxy S26 / One UI 8.5/9,
    actual instrumentation, live Proton, and Proton disposable-scope verification are
    **NOT RUN**. No release readiness is implied.
13. **Rollback point:** revert only CloudBridge commit `f920905` to remove the app integration
    and v13 columns from source history; keep the published native inspector unless separately
    reviewed. Do not downgrade an installed v13 database or remove state/backups. No migration
    downgrade or old-app rollback has been proven.

### WP08 continuation

Keep WP08 active. Next implement a fresh, time-bounded non-mutating preview with explicit
known/unknown result fields, then preserved initialization/recovery actions and a durable
run-scoped backup coordinator; add permission/disk/network/cancellation/process-death tests.
Maintain fail-closed readiness and do not surface an action as recoverable until native state,
app ownership, exact backup bytes, and rollback boundaries are tested. The supplementary WP14
migration requirements remain cross-cutting; the workspace master numbering is unchanged.

## 2026-09-23 — WP08 preview-state and reinitialization-preservation checkpoint (PARTIAL)

CloudBridge source commit: `d888297b0e84ae3532215c89ce710c0213d905ba`.
Rareities/rclone source commit: `175c3508193eb13be1b5d8c39b8b26475bf4c58c` on
`codex/luna-engine`. These are local commits; the new rclone source commit is not yet published
or pinned by CloudBridge.

1. **Objective:** ensure a successful native dry-run leaves durable Bisync state inspectable and
   ensure explicit reinitialization does not discard the last accepted app identity baseline.
2. **Scope:** `InspectState` classification of native `*.lst-dry*` scratch outputs and lock states;
   byte-preservation regressions for first-run and existing-state previews; app repository reset
   semantics and a confirmed/unconfirmed baseline-retention instrumentation regression.
3. **Out of scope:** app preview UI/worker, automatic sync, initialization/recovery execution,
   run-scoped remote backups, restore/fault injection, DB downgrade, live Proton, device and
   release acceptance.
4. **Preconditions:** WP08 read-only inspector is at `a2eec9f17e9624a8ed78afeccf520c5716270b1f`;
   CloudBridge still pins published immutable rclone ref
   `d53551e1722305268c6072263f11066f1278a4a0` pending safe publication of this follow-up.
5. **Design:** `.lst-dry*` are non-authoritative dry-run scratch listings and do not replace the
   accepted `.lst` pair. Ignore those scratch suffixes during inspection while retaining fail-
   closed handling for a live native guard, stale active lock metadata, `.lst-new` and `.lst-err`.
   A reinitialization request changes readiness/reason but retains the accepted baseline for
   review and rollback.
6. **Safety invariants:** previews do not change either root or canonical accepted listings;
   a first-run dry-run remains ABSENT, not initialized. Unconfirmed reset throws without state
   change. No recovery/restore is authorized by the diagnostic state or baseline.
7. **Implementation:** native state commit `175c350` adjusts dry-run artifact classification and
   adds exact root/listing-byte tests for absent and compatible state, including same-size,
   same-mtime, different-byte input under checksum comparison. App commit `d888297` preserves
   accepted baseline columns across an explicit reset and marks app/preflight readiness as
   `INITIALIZATION_REQUIRED`.
8. **Reuse:** native `InspectState`, existing native owner guard/lock metadata and Bisync dry-run;
   existing app profile identity, transactional SQLite repository and instrumentation fixture.
9. **Retired:** the prior inspector behavior treated successful dry-run scratch outputs as
   unresolved/interrupted state. The app reset path no longer deletes the accepted baseline.
10. **Failure behaviour:** active or stale native ownership and true `.lst-new`/`.lst-err`
    artifacts remain blocked; unknown state remains fail-closed. Without explicit confirmation,
    the app changes neither readiness nor accepted identity.
11. **Tests:** full `go test ./cmd/bisync -count=1` PASS (40.573 s), `go vet ./cmd/bisync`
    PASS, `GOOS=android GOARCH=arm64 go build -mod=readonly ./cmd/bisync` PASS. CloudBridge
    `:app:testOssDebugUnitTest` PASS (14 suites, 76 tests, 0 failures/errors, 1 Windows
    symlink-capability skip); `:app:lintOssDebug` PASS with baseline (92 warnings, 2 errors and
    428 warnings filtered; 76 stale baseline entries); final instrumentation source compilation
    PASS. Instrumentation execution is NOT RUN. Diff checks PASS. No new dependencies.
12. **Acceptance:** this checkpoint is PARTIAL. The new instrumentation assertions compile but
    have not executed on Android. ADB could not initialize its sandbox user-state directory;
    Galaxy S26 / One UI 8.5/9, actual Android API/firmware and Proton disposable-area checks
    remain **NOT RUN**. App preview, backup provisioning, recovery/restore, process-kill
    boundaries, migration rollback, integrated build against `175c350`, APK/R8, signing, PR/CI
    and release evidence remain open. No release readiness is claimed.
13. **Rollback:** revert only `175c350` and/or `d888297`; retain the prior native inspector and
    DB v13 migration. Never delete old baselines, native listings, backups or recovery evidence.

**Next:** implement the app-owned, time-bounded preview coordinator/worker using native dry-run,
fresh identity/config checks and bounded path-free summary output. Keep initialization disabled
until durable same-provider backups, rollback and fault boundaries are implemented and tested.
Refresh the remote fork/CI before publication; the branch has not been pushed or opened as a PR.
