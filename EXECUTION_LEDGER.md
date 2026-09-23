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

## 2026-09-24 — WP08 immutable preview-protocol pin and integrated debug build (PARTIAL)

CloudBridge implementation commit: `eb71b94fb563d75ce02c161530fe9ec594038642`.
Rareities/rclone remote head: `fe775a8b58cf217fdf4bd34f0975af1e4c19c1a0`, tree
`68df728c1eb3cd1e60340286cd99548e6d0d0452`. The GitHub commit is unsigned; no signature is
claimed. The pin remains on the local CloudBridge branch and has not yet been pushed or opened as
a PR.

1. **Objective:** adopt the independently tested WP08 native preview protocol at an immutable
   Rareities/rclone revision and verify the Android integration contains the actual native engine.
2. **Scope:** Gradle engine pin and identity fixtures, Windows Go executable resolution, fresh
   four-ABI native build, OSS debug APK, JVM tests, lint and Android-test source compilation.
3. **Out of scope:** app-owned preview worker/UI/persistence, initialization/recovery/backups,
   real-device or live-Proton acceptance, R8/release signing, PR/CI and release publication.
4. **Preconditions:** rclone commit `fe775a8…` is parented on published `d53551e…`; exact
   GitHub tree `68df728…` was fetched by the app's Gradle checkout. The native preview protocol
   passed independent Go tests/vet/build/cross-build and disposable CLI smoke as recorded in the
   rclone patch ledger.
5. **Design:** keep `https://github.com/Rareities/rclone.git` and pin the full SHA. Resolve Go
   from explicit `de.schuelken.cloudbridge.goExecutable`, then `GOROOT/bin`, then PATH; never
   select another repository or moving ref as fallback.
6. **Safety invariants:** profile/run fixtures name the same SHA as BuildConfig; native outputs
   and APKs remain generated artifacts; preview is not authorization to mutate; debug signing is
   never production provenance. Existing `.android/analytics.settings` remains untracked.
7. **Implementation:** commit `eb71b94` updates `gradle.properties`, the app pin fixtures and
   requirements traceability, and changes `rclone/build.gradle` to invoke the resolved Go
   executable. All four generated `librclone.so` files are from source SHA `fe775a8…`.
8. **Reuse:** the native `--preview-json` contract, existing app build integration and the
   existing immutable `d53551e…` engine lineage; no Android-side file-comparison algorithm was
   added.
9. **Retired:** CloudBridge's previous `d53551e…` pin is superseded. There is still no unpinned
   fallback. No Bisync execution or recovery path was enabled.
10. **Failure behavior:** Gradle continues to fail on a missing repository/ref; Go resolution
    uses only the configured executable, `GOROOT/bin`, or PATH. Any unknown preview result must
    remain unavailable/unknown in the still-pending app integration.
11. **Tests:** Windows Go 1.26.8, JDK 17.0.20.1, Gradle 8.13 and Android SDK 36. Fresh
    `:rclone:buildAll --no-daemon` PASS for arm64-v8a, armeabi-v7a, x86 and x86_64, printing
    exact source `fe775a8…` and rclone v1.76.0. The integrated command
    `:app:testOssDebugUnitTest :app:lintOssDebug :app:compileOssDebugAndroidTestJavaWithJavac
    :app:assembleOssDebug --no-daemon -Pkotlin.compiler.execution.strategy=in-process
    -x :rclone:buildAll` PASS: 14 suites/76 tests, 0 failures/errors, 1 Windows symlink skip;
    lint PASS with baseline (92 warnings, 2 errors and 428 warnings filtered; 76 stale entries);
    Android-test source compile PASS. Universal debug APK SHA-256 is
    `71F6EA499419D1928E50BD2F211126E1E4BD6952B3B5552E981FF095626614CE`; its verified debug
    certificate SHA-256 is `2dfd7565c808a6144fd1c7458b5dfe5fc04b31319f23055c8e4d0d80096273bb`.
    APK lists all four ABI libraries. `git diff --cached --check` passed before commit. Initial
    in-sandbox JDK/cache attempts failed on Windows real-path/cache ACL access; the final build
    passed with an isolated workspace Gradle home and host-access escalation, without changing
    the SDK or deleting caches.
12. **Acceptance:** pin and four-ABI debug integration PASS, but WP08 remains PARTIAL. The app
    does not yet persist or present the path-free summary or provide a time-bounded worker/UI;
    initialization, recovery, backup/restore and mutation-fault evidence remain open.
    Instrumentation execution, Galaxy S26 / One UI 8.5/9 (including actual API/firmware), and live
    Proton tests are **NOT RUN**. The APK is `de.schuelken.cloudbridge.debug`, version
    `1.0.1-DEBUG`, debug-signed. Existing release signing configuration still falls back to the
    debug key when production signing is absent; WP13 must close that blocker. No release
    readiness is claimed.
13. **Rollback:** restore the previous immutable `d53551e…` app pin and matching fixture values
    as one reviewed change; do not remove generated APK/native outputs as source cleanup, and do
    not downgrade existing Bisync state or discard recovery evidence.

**Next:** continue WP08 with strict summary parsing, persisted identity/freshness, durable
preview ownership and cancellation/process-death handling. Keep initialization disabled until
run-scoped preservation and rollback are proven.

## 2026-09-24 — WP08 strict app-side preview-summary parser (PARTIAL)

CloudBridge implementation commit: `f7eed3c16ecbde3945903e2f162d95e4f9750996`.

1. **Objective:** accept only a valid, path-free native preview summary after confirmed native
   process success and complete output drain.
2. **Scope:** a pure app-side v1 summary model/parser and unit coverage. No worker, UI, persistence,
   init/recovery, or execution behavior was enabled.
3. **Out of scope:** durable preview ownership/freshness, run wiring, scheduling, initialization,
   recovery, same-provider preservation/restore, real-device and provider acceptance.
4. **Preconditions:** CloudBridge pins Rareities/rclone
   `fe775a8b58cf217fdf4bd34f0975af1e4c19c1a0`; its native `--preview-json` contract and
   independent tests/build/Android arm64 cross-build are recorded in the rclone patch ledger.
5. **Design:** Jackson streaming parser with strict duplicate-key detection; exact eight-field
   allowlist; v1 only; known statuses; nonnegative integral 64-bit counters; `conflictsKnown`
   must remain false; `COMPLETE` requires zero reported errors; output is capped at 4 KiB.
6. **Safety invariants:** only counters/status are returned; paths or extra fields are rejected;
   failed, unconfirmed, truncated or malformed output is unavailable; `INCOMPLETE` stays visibly
   incomplete; even `COMPLETE` is review data, never authorization to mutate.
7. **Implementation:** commit `f7eed3c` adds `BisyncPreviewSummary.kt` and parser tests. Jackson
   Core was already a project dependency; no dependency changed. No raw native output or path is
   persisted or logged.
8. **Reuse:** existing Jackson Core dependency, immutable native protocol, and CloudBridge OSS
   JVM unit-test harness.
9. **Retired:** no permissive generic JSON-object coercion or unknown-field fallback is used for
   preview summaries.
10. **Failure behavior:** process failure/unconfirmed completion, truncation, empty/oversized
    output, unsupported version, duplicate keys, unknown/path-like keys, wrong types, negative,
    fractional or overflowing counters all produce a sanitized unavailable reason.
11. **Tests:** six new parser tests pass. Full `:app:testOssDebugUnitTest` passes: 82 tests,
    zero failures/errors, one existing Windows symlink-capability skip. `:app:lintOssDebug` passes
    with the existing baseline (92 warnings, 2 errors and 428 warnings filtered; 76 stale
    baseline entries). JDK 17.0.20.1 / Gradle 8.13 / SDK 36. This app-only rerun used the
    previously verified clean rclone cache at exact SHA `fe775a8…` and excluded checkout/native
    rebuild tasks; the fresh four-ABI build remains evidenced in the preceding checkpoint. The
    sandbox cache write failed, and the final test/lint run succeeded with host access using
    workspace-local Gradle and Android user homes. Diff checks passed before commit.
12. **Acceptance:** parser foundation PASS; WP08 remains PARTIAL. No native preview command is
    yet called by the app, and summary identity/freshness, durable run ownership, cancellation and
    process-death reconciliation, UI, init/recovery, backup/restore and fault-boundary proof remain
    open. Instrumentation execution, Galaxy S26 / One UI 8.5/9 (actual API/firmware), and live
    Proton acceptance are **NOT RUN**. No release readiness is claimed.
13. **Rollback:** revert only `f7eed3c`; it adds no schema or runtime call sites and cannot alter
    existing data. Do not revert earlier accepted-state preservation work or discard any recovery
    evidence.

**Next:** design durable preview identity/freshness and run ownership against the existing run
schema, then add the bounded cancellable native dry-run entry point and worker with tests for stale
profile/config/engine state, process cancellation and death. Keep initialization disabled until
run-scoped backups, rollback and fault boundaries are proven.

## 2026-09-24 — GitHub repository, PR and CI refresh

Read-only GitHub refresh confirms `Rareities/CloudBridge` master is still
`c492876258ca841232229249519abe92ff77c3a4`, with no implementation branch, PRs, or Actions runs.
The local CloudBridge branch remains unpublished.

`Rareities/rclone:codex/luna-engine` remains at `fe775a8b58cf217fdf4bd34f0975af1e4c19c1a0`;
its fork `master` is `1583cce1e28340e5d064ed955179f5f2b31e7757`. PR #1 remains closed/unmerged;
no Actions runs or commit status checks exist for the feature head. Comparing that head with the
fork's master still shows 180 commits ahead from the archive-import history, so that base is
unsuitable. Current upstream `rclone/rclone:master` is `90e67915c88d4adf244f1d5251088c339c8b8e23`;
comparing `Rareities:codex/luna-engine` to it reports 6 commits ahead, 1 behind, and 26 changed
files. This is a candidate upstream PR boundary, not approval to publish: review/rebase and split
scope remain pending. No PR was created during this refresh.

## 2026-09-24 — WP08 native isolated-preview baseline primitive (PARTIAL)

Rareities/rclone local source commit `81ac481705944ac125e2f8eeab823d78f6b1cfdb` (tree
`ada7e7df6b8c196957afd0da77142dfe64227327`), parent `fe775a8b58cf217fdf4bd34f0975af1e4c19c1a0`.
The separate native patch-ledger commit is `4cb77955699445ea16ea44aac6041169cb895192`.
These commits are local to `work/rclone-preview-state`; they are not yet published to GitHub and
CloudBridge intentionally remains pinned to the last published immutable engine SHA `fe775a8…`.

1. **Objective:** provide a native-safe way for an app-owned preview to use compatible accepted
   Bisync state in a fresh isolated workdir without updating the live profile listings.
2. **Scope:** rclone `CopyCompatibleState`, source-guarded byte-exact clone, CLI-only
   `--preview-state-from`, strict validation/docs/tests. The change is prepared independently from
   the archival `work/rclone-history` and its unrelated generated files.
3. **Out of scope:** CloudBridge worker/UI/persistence, initialization or mutation authorization,
   durable backups/restore/recovery, Proton/Samsung acceptance, GitHub PR publication, and release.
4. **Preconditions:** native state inspector and versioned path-free dry-run summary were reviewed;
   source parent is the currently published Rareities commit above.
5. **Design:** require a compatible source workdir and absent explicit destination. Hold the native
   source guard through inspection, bounded read and copy; validate the exact source byte streams,
   compare both listings using native comparison rules, reject overlaps (lexical and filesystem
   identity) and symlinked components, securely publish no-overwrite files, and verify destination
   bytes. Copy only the two accepted listing files, never lock/recovery/scratch artifacts.
6. **Safety invariants:** no endpoint writes, no change to accepted listing or lock-metadata bytes,
   no overwrite of existing destination, fail closed on unknown/interrupted/absent/incompatible or
   unsafe input. The source inspector may create/retain its persistent guard file. A process crash
   may leave an incomplete isolated UUID folder; it must never be reused. Preview remains
   non-authoritative and does not enable initialization.
7. **Implementation:** native source commit adds helper/API/CLI flag, strict parser reuse, rollback
   of only invocation-owned partial destination files, tests and user/developer documentation.
   The follow-up patch ledger records source commit and acceptance boundaries.
8. **Reuse:** existing native inspector, parser, comparison semantics and Bisync dry-run; no new
   dependency, app-side file comparison, or change to the existing CloudBridge engine pin.
9. **Retired:** no prior behavior removed; CLI preview options are not exposed as RC parameters.
10. **Failure behavior:** missing, busy, unsafe, incompatible or non-overlapping state failures
    happen before creating the isolated target; ordinary mid-copy failure removes only files/empty
    directory created by that invocation. App orchestration must never retry a partial UUID path.
11. **Tests:** Windows Go 1.26.8, short workspace-contained temp root: full `go test
    -mod=readonly ./cmd/bisync -count=1` PASS (37.268 s); focused clone/dry-run/flag tests PASS;
    `go vet -mod=readonly ./cmd/bisync` PASS; full `go build -buildvcs=false -mod=readonly ./...`
    PASS; `GOOS=android GOARCH=arm64 go build -mod=readonly ./cmd/bisync` PASS. Fresh CLI binary
    build/help/fail-closed option smoke PASS. Symlink fixture **SKIPPED** because Windows denied
    link creation due missing privilege. No dependency changed; only disposable local endpoints;
    no Proton network or Samsung hardware used.
12. **Acceptance:** native isolation primitive PARTIAL only; it is not yet in the published fork or
    an app APK. Remaining app work includes UUID run-owned workdirs, summary/mode/endpoint/policy
    presentation, persisted fingerprint/engine/compatibility/time, 15-minute freshness, cancellation
    and process-death handling, and fresh mutation-boundary revalidation. Initialization remains
    blocked pending run-scoped preservation, rollback and fault-injection evidence. Proton and
    Galaxy S26 / One UI 8.5/9 actual API/firmware are **NOT RUN**. No PR, release or readiness claim.
13. **Rollback:** revert only native source commit `81ac4817…`; retain prior inspector, preview
    summary, existing Bisync protections and live state. Do not alter current CloudBridge pin until
    the source ref is safely published and app integration has independently passed.

**Next:** continue WP08 by mapping app preview identity and owner lifecycle to the existing DB/run
model; preserve the app's current published pin until the new engine commit is available through a
verified Rareities branch. Do not enable initialization before the backup/restore/fault gates pass.

## 2026-09-24 - WP08 durable preview identity and owner checkpoint (PARTIAL)

1. **Objective:** durably bind a non-authoritative Bisync preview to the exact profile, endpoint,
   filter, comparison policy, accepted baseline and immutable engine identity while preventing
   overlapping preview owners.
2. **Scope:** app-only identity/freshness model, separate preview-operation repository, SQLite v14
   preview table and migration, JVM tests, and instrumentation-test source for durable ownership.
3. **Out of scope:** native worker/CLI integration, actual UUID workdir orchestration, UI/history,
   preview-to-mutation revalidation, initialization/recovery, same-provider preservation/restore,
   scheduling integration, external publication and release.
4. **Preconditions:** app still pins the published native SHA
   `fe775a8b58cf217fdf4bd34f0975af1e4c19c1a0`; its v1 summary parser and the local-only native
   isolated-baseline prototype are recorded in earlier WP08 entries. The native prototype remains
   unpublished and is not invoked by this app change.
5. **Design:** immutable hash-only identity covers UUID profile/revision/fingerprint, engine/state
   pin, both account/scope fingerprints, filter/comparison, delete limits, native-state class and
   accepted-baseline fingerprint. Queue/claim/finish are serialized through a separate durable
   owner table with owner-token generation, a partial unique active-owner index, strict aggregate
   summary constraints, stale identity handling and a 15-minute display-only freshness rule.
6. **Safety invariants:** preview identity contains no raw endpoint paths or credentials; summaries
   contain only bounded counters and never claim known conflicts. Preview does not mutate profile
   readiness or authorize any write. Unconfirmed process stop and process death retain a blocking
   recovery state; stale callbacks cannot finish a newer owner. The existing accepted baseline is
   never overwritten by this repository.
7. **Implementation:** new `BisyncPreviewIdentity`, operation/failure/freshness types and
   `BisyncPreviewRepository`; DB version 14 adds `bisync_preview_table`, active-owner/history
   indices and v13-to-v14 migration. The v12 migration assertion now follows the current schema
   version and a v13 profile-preservation fixture was added. Source remains uncommitted at this
   checkpoint; the user-owned untracked `.android/` directory was not touched or staged.
8. **Reuse:** existing profile/preflight identities, strict v1 path-free parser, SQLite ownership
   conventions, unique-index concurrency guard and current JVM/instrumentation test harness.
9. **Retired:** no normal `RunRepository` rows or legacy task direction are repurposed for preview;
   no preview status is interpreted as readiness or mutation authorization.
10. **Failure behavior:** changed profile/preflight becomes STALE; malformed owner/state cannot be
    claimed; failed/truncated parsing is unavailable; unconfirmed termination becomes
    RECOVERY_REQUIRED; process restart marks RUNNING as INTERRUPTED and continues to block another
    preview until a separately proven safe recovery path exists.
11. **Tests:** final `:app:testOssDebugUnitTest --no-daemon
    -Pkotlin.compiler.execution.strategy=in-process -x :rclone:buildAll` PASS: 88 tests reported,
    0 failures/errors, 1 existing Windows capability skip. `:app:compileOssDebugAndroidTestJavaWithJavac`
    PASS (test sources compile; not executed). `:app:lintOssDebug -x :rclone:buildAll` task PASS;
    report says 0 current errors and 94 warnings, with 2 errors/428 warnings suppressed by the
    existing baseline and 76 stale baseline entries. Two new `UseKtx` suggestions point to the
    explicit transaction boundaries in the preview repository. Exact table SQL expanded from
    source and exercised against in-memory SQLite: create/index/integrity PASS, duplicate active
    owner rejected, valid complete summary accepted, invalid summary/status row rejected. `git
    diff --check` PASS. No app APK was assembled against these new changes and native build was not
    rerun in this checkpoint.
12. **Acceptance:** durable identity/owner schema foundation PARTIAL. Preview has no app-owned
    worker or command path yet, so no preview can be launched through this feature. Instrumentation
    execution and Galaxy S26 / One UI 8.5/9 (actual API/firmware), live Proton, mutation-boundary,
    initialization/recovery, backup/restore, process-kill/reboot, and device-level migration tests
    are **NOT RUN**. No PR, CI, signing, release or release-readiness claim.
13. **Rollback:** revert only the pending preview-identity/repository/schema/test files and the
    v14 migration change as one bounded checkpoint; retain prior v13 accepted-state evidence and
    protections. Never remove accepted listings, user files or recovery evidence.

**Next:** implement a distinct durable preview worker/coordinator around the exact native CLI
contract, UUID-owned isolated workdir and owned-process drain/cancel lifecycle. Do not wire or pin
the local native `--preview-state-from` flag until its source boundary and publication path are
reviewed; do not enable initialization/recovery before preserved-version, exact-restore and
fault-boundary evidence passes.

## 2026-09-24 - WP08 explicit initialization preference and DB v15 migration (PARTIAL)

1. **Objective:** ensure an absent-state preview is bound to the user's explicit Bisync conflict
   preference and that v14 preview records cannot be treated as if they had such a preference.
2. **Scope:** CloudBridge identity/model, repository validation, SQLite schema v15 and the v14-to-v15
   migration, JVM coverage, and Android instrumentation-test source for running and queued legacy
   preview rows.
3. **Out of scope:** preview worker/native command/UI, endpoint snapshotting, mutation authorization,
   initialization/recovery execution, durable listing backups/restore, scheduler integration,
   release identity/signing, and external publication.
4. **Preconditions:** base CloudBridge commit `87c0c8c75cbb47f004fd10d8b122872858300db8` contains
   the separate v14 preview-owner table and identity foundation. App engine pin remains the
   published Rareities/rclone SHA `fe775a8b58cf217fdf4bd34f0975af1e4c19c1a0`; the isolated-state
   native prototype remains local and is not invoked by this app change.
5. **Design:** absent-state identity includes one of the six explicit rclone conflict policies and
   uses a distinct v2 digest; compatible-baseline identities retain the v1 digest and must have no
   initialization preference. The v15 schema requires policy for runnable absent-state rows and
   uses strict non-null-aware aggregate constraints for terminal summary shape. During v14 upgrade,
   absent-state RUNNING/INTERRUPTED/RECOVERY_REQUIRED rows become RECOVERY_REQUIRED with the owner
   generation incremented; other absent-state rows become STALE. No policy is inferred.
6. **Safety invariants:** never default an absent-state preview to path1 or another policy; never
   release a running owner while upgrading; invalidate its late callback generation; clear
   unusable summary values; keep a recovery hold for active legacy owners; retain the accepted
   Bisync baseline and all existing user data.
7. **Implementation:** CloudBridge commit
   `7daef0620af7e809ab0e39076145c82c7030bc2c` (base
   `87c0c8c75cbb47f004fd10d8b122872858300db8`) adds `BisyncPreviewResyncMode`, policy-aware
   fingerprints and queue/claim checks, the v15 mode column and fail-closed migration, stricter
   status/summary checks, JVM assertions, and v14 instrumentation fixture coverage for RUNNING and
   QUEUED absent-state rows. Only the six intended source/test files were staged; the user-owned
   untracked `.android/` directory was not touched or staged.
8. **Reuse:** existing profile/preflight fingerprints, v14 preview owner/history repository,
   immutable engine pin and current JVM/instrumentation test harness.
9. **Retired:** implicit absent-state preview identity without a bound resync preference; no legacy
   direction or normal run row was repurposed.
10. **Failure behavior:** an absent-state preview without explicit policy is rejected at identity
    creation, queue and claim; malformed stored policy fails closed. v14 active absent-state work
    remains recovery-blocked after migration; queued/terminal absent-state evidence becomes stale
    rather than runnable.
11. **Tests:** with JDK 17.0.20.1, Gradle 8.13 and installed Android SDK 36/build-tools 35.0.0,
    `:app:testOssDebugUnitTest`, `:app:compileOssDebugAndroidTestJavaWithJavac` and
    `:app:lintOssDebug` passed offline with `-x :rclone:buildAll`; 88 JVM tests, zero failures/errors,
    one existing Windows capability skip. Instrumentation sources compile; no instrumented device
    execution occurred. Lint reports 94 visible warnings; the existing baseline filters 2 errors
    and 428 warnings and has 76 stale entries. Source-generated table DDL/index checks in SQLite
    accepted explicit absent/PATH1 and complete-with-counters records, and rejected duplicate active
    ownership, absent-without-policy, and complete-without-counters. A separate v14-to-v15 SQLite
    fixture produced RUNNING to RECOVERY_REQUIRED (generation 1 to 2, no completion/policy) and
    QUEUED to STALE (completion set from update time, no policy). `git diff --check` passed. The initial
    sandboxed Java run could not read the SDK stub JAR; the same offline tasks passed with approved
    elevated process access. No APK was assembled and the native engine was not rebuilt in this
    checkpoint.
12. **Acceptance:** explicit-policy identity and schema/migration behavior pass source/JVM/SQLite
    checks; WP08 remains PARTIAL. The Android migration instrumentation is compile-only. No worker,
    CLI invocation, actual device database upgrade, preview UI, mutation-boundary revalidation,
    initialization/recovery, backup/restore or process-kill/reboot test is complete. Galaxy S26 /
    One UI 8.5/9 with actual API/firmware and live Proton remain **NOT RUN**. No PR, CI publication,
    release or release-readiness claim.
13. **Rollback:** revert only `7daef0620af7e809ab0e39076145c82c7030bc2c` to return from DB v15 to
    the v14 preview-owner foundation at `87c0c8c75cbb47f004fd10d8b122872858300db8`; retain all
    earlier accepted-baseline preservation work. No native state or remote data was touched.

**Next:** implement a distinct durable preview worker/coordinator around the exact native CLI
contract, UUID-owned isolated workdir and owned-process drain/cancel lifecycle. Keep the app on the
currently published engine SHA until the local native preview-state change has a reviewed,
reproducible publication path. Do not enable initialization/recovery before preserved-version,
exact-restore and fault-boundary evidence passes.

## 2026-09-24 - WP08 owned native preview command boundary (PARTIAL)

1. **Objective:** translate a claimed, fresh Bisync preview identity into a bounded native dry-run
   without allowing engine drift, implicit initialization policy, accepted-baseline mutation,
   endpoint overlap with scratch, or raw preview output to escape the owned execution boundary.
2. **Scope:** exact-pin command builder and capability registry, Rclone native-run adapter, private
   UUID workdir ownership/cleanup, durable preview owner-token rechecks, sanitized run result, and
   JVM argument tests.
3. **Out of scope:** WorkManager enqueue/worker, fresh preflight reconstruction, endpoint/filter
   snapshot handoff, preview UI, cancellation/reboot integration tests, initialization/recovery,
   preserved-version backup/restore, Proton/device acceptance, CI/PR/release.
4. **Preconditions:** CloudBridge v15 explicit-initialization identity/owner foundation is at
   `7f00c24`; app remains pinned to published rclone `fe775a8b58cf217fdf4bd34f0975af1e4c19c1a0`.
   The local native prototype `81ac481705944ac125e2f8eeab823d78f6b1cfdb` is unpublished and is
   not the app's configured engine.
5. **Design:** capability registry permits path-free preview JSON only for the exact published
   engine SHA and grants `--preview-state-from` only to the exact local clone-capable SHA. Commands
   always use dry-run, path-free JSON, unique absolute workdir, identity-bound compare/delete
   limits, validated filters and no `--force`. Absent state requires the identity's explicit
   resync mode. Compatible state requires a clone-capable pin and the established profile workdir;
   current app pin therefore rejects that case before native launch. Rclone rechecks RUNNING owner
   token/generation/identity immediately before launch, uses existing endpoint claims and owned
   cancellable execution, caps stdout at 4 KiB, parses only in memory, and emits a sanitized result.
6. **Safety invariants:** raw endpoint paths and native output are not persisted or logged; scratch
   is private, unique, contained and rejected if it overlaps a local endpoint; cleanup never
   follows symlinks or escapes the preview root; scratch is retained if process stop is unconfirmed;
   scratch cleanup failure suppresses an otherwise valid summary. Unknown pins, legacy checksum mode,
   unresolved state, stale owner, invalid filters and implicit absent-state policy fail closed.
7. **Implementation:** CloudBridge commit `2f26ebc` (base `7f00c24`) adds
   `BisyncPreviewCommandBuilder`, exact-SHA capability checks, the Rclone owner-bound native run
   adapter, bounded parsing/cleanup result reasons, and exact argument/fail-closed JVM tests.
   The user-owned untracked `.android/` directory was not staged or changed.
8. **Reuse:** v15 durable preview identity/repository, native v1 path-free summary parser, existing
   `launchClaimed` endpoint resource lease, `NativeExecutionHandle`, bounded cancellation/drain
   runner, read-only endpoint mapper and local accepted-state inspector.
9. **Retired:** no command is assembled through the ordinary sync path; no `--force`, implicit
   resync mode, accepted-state copy on the published pin, or log of raw native output is permitted.
10. **Failure behavior:** engine/capability, owner, identity, endpoint, filter and request failures
    return a path-free unavailable reason before launch; failed/truncated native output is not
    summarized; unconfirmed exit preserves scratch and caller must keep recovery ownership; a
    confirmed but failed scratch deletion converts the result to unavailable.
11. **Tests:** offline JDK 17.0.20.1 / Gradle 8.13 / Android SDK 36 run passed:
    `:app:testOssDebugUnitTest` (92 tests, 0 failures/errors, 1 existing Windows capability skip),
    `:app:compileOssDebugAndroidTestJavaWithJavac` (sources compile only), and `:app:lintOssDebug`
    (task PASS; 94 visible warnings; existing baseline filters 2 errors and 428 warnings, with 76
    stale baseline entries). `git diff --check` passed before commit. Gradle ran offline with
    `-x :rclone:buildAll`; Android-test sources were not executed on a device. No native rebuild or
    APK assembly in this checkpoint.
12. **Acceptance:** the exact argv builder and native adapter compile, and builder tests prove the
    published-pin absent-state flags, explicit mode, delete caps, filter encoding, no `--force`,
    compatible-state fail-closed behavior, and exact clone-pin behavior. WP08 remains PARTIAL: no
    WorkManager caller exists yet, so there is no app feature path to launch or view a preview; no
    fresh preflight/identity revalidation in a worker, result UI, mutation-boundary check,
    initialization/recovery, backup/restore, process-kill/reboot, or device migration proof. Galaxy
    S26 / One UI 8.5/9 (actual API/firmware) and live Proton remain **NOT RUN**. No APK, PR, CI
    publication, signing, release or release-readiness claim.
13. **Rollback:** revert only `2f26ebc` to return to the v15 identity/owner state at `7f00c24`;
    preserve the earlier schema and native state evidence. This change did not touch remote data or
    accepted Bisync listings.

**Next at that checkpoint:** implement durable WorkManager enqueue/worker coordination. This was
implemented in the checkpoint immediately below; add a user-facing preview path only after its safety
and freshness boundaries are established. Do not add UI authorization for initialization until
preservation/restore and fault-boundary gates pass. Continue to keep the local native clone SHA
unpublished/unpinned until its separate review.

## 2026-09-24 - CloudBridge WP08 durable preview scheduler/worker (PARTIAL)

1. **Objective:** connect the path-free durable preview owner to WorkManager, rebuild current
   read-only evidence before claim, and execute only the existing owner-bound dry-run adapter.
2. **Scope:** preview scheduler and unique work request; worker foreground/cancel lifecycle; current
   profile/task/filter snapshot reconstruction; fresh preflight and exact identity claim; sanitized
   terminal result persistence; recent persisted-preflight admission checks; instrumentation source
   coverage for queue admission and terminal owner transitions.
3. **Out of scope:** user-facing launch/review UI, result freshness display, mutation-boundary
   revalidation, actual Bisync mutation, initialization/recovery, preserved-version backup/restore,
   compatible-state execution on a publishable engine, Proton/device acceptance, PR/CI/release.
4. **Preconditions:** exact app pin remains published Rareities/rclone
   `fe775a8b58cf217fdf4bd34f0975af1e4c19c1a0`; owner-bound command adapter is `2f26ebc`; preview
   identity includes explicit absent-state policy. Local native clone-capable follow-up remains
   unpublished and is not included in the app pin.
5. **Design:** `BisyncPreviewWorkScheduler` persists an owner before dispatch and uses unique
   WorkManager work with a connected-network constraint. The worker reopens the exact profile/task,
   re-runs read-only preflight, reconstructs the transient raw endpoint/filter snapshot in memory,
   builds and claims the identity, rechecks current profile state, runs `Rclone.runBisyncPreview`,
   and stores only the sanitized parser result after confirmed process stop. Queue admission requires
   a recent successful persisted preflight row; compatible state must match every accepted baseline
   field, while absent state requires matching successful ABSENT/INITIALIZATION_REQUIRED evidence,
   no accepted baseline, and explicit initialization mode.
6. **Safety invariants:** no raw endpoint or native output is placed in WorkManager data or preview
   history; queued IDs/tokens are owner-scoped; stale/expired/blocked preflight evidence is rejected;
   current published engine capability remains authoritative; cancellation only finalizes the exact
   queued owner and a running native owner still requires confirmed process stop; preview never
   authorizes a write, deletion, initialization or recovery.
7. **Implementation:** CloudBridge commit
   `cd3fd57cf37728675f25f8e5d2ec7371aaee66fd` (base
   `97ec846a156b7e14c9f2e766680b91674adf244c`) adds the WorkManager scheduler/worker, transient
   preflight execution snapshot, queue freshness/baseline validation, exact-owner queued terminal
   transitions, and instrumentation test source. The user-owned untracked `.android/` directory was
   not staged or changed.
8. **Reuse:** v15 `BisyncPreviewRepository`, `BisyncPreflightCoordinator`/policy, identity
   fingerprints, existing endpoint resource claims, pinned CLI builder, owned native execution,
   notifications and WorkManager dependencies.
9. **Retired:** identity-only queue admission without persisted preflight evidence; no app-surface
   inference from preview status; no WorkManager data containing endpoint paths or filters; no
   automatic retry of an identity mismatch; no sync/init/recovery capability is enabled here.
10. **Failure behavior:** stale/missing/mismatched preflight and profile evidence prevent launch;
    dispatch/preflight failure finalizes only the exact queued owner; cancellation before claim is
    terminal only for that owner; uncertain native stop remains a conservative recovery hold; failed
    storage finalization leaves the durable owner blocking rather than reporting success.
11. **Tests:** offline JDK 17.0.20.1 / Gradle 8.13 / Android SDK 36 run passed
    `:app:testOssDebugUnitTest` (92 tests, 0 failures/errors, 1 existing Windows capability skip),
    `:app:compileOssDebugAndroidTestJavaWithJavac` including instrumentation Kotlin/Java source
    compilation, and `:app:lintOssDebug` (94 visible warnings; existing baseline filters 2 errors and
    428 warnings, 76 stale entries). `git diff --check` passed. Android instrumentation was not
    executed; ADB could not start its local server in this environment. The run used
    `-x :rclone:buildAll`; no native rebuild or APK assembly was performed.
12. **Acceptance:** scheduler/worker source and core Android compilation pass; persisted evidence
    checks fail closed by construction and have instrumented regression source, but that source has
    not run on a device. WP08 remains PARTIAL: no user-facing launch/review screen or mutation-boundary
    revalidation; compatible-state preview fails closed on the published app pin; no init/recovery,
    preservation/restore or process-kill/reboot runtime evidence. Galaxy S26 / One UI 8.5/9 (actual
    API/firmware), Samsung instrumentation and live Proton are **NOT RUN**. No APK, PR, CI
    publication, signing or release claim.
13. **Rollback:** revert only `cd3fd57cf37728675f25f8e5d2ec7371aaee66fd` to
    `97ec846a156b7e14c9f2e766680b91674adf244c`; retain the exact-pin command adapter, DB v15 identity
    migration and earlier accepted-baseline preservation work. No remote data or native Bisync
    state was touched.

**Next:** add a user-mediated preview request/review surface that cannot treat preview completion as
mutation authorization; keep initialization and recovery unavailable pending proven backups, exact
restore and fault-boundary evidence. Test instrumentation and runtime process/cancellation behavior
when an emulator or the specified acceptance device is available. Preserve the published app pin
until the compatible-state native feature has an independently reviewed/publication path.

## 2026-09-24 - CloudBridge C03/WP04 task-mode preservation (PARTIAL)

1. **Objective:** close the task-editor path that silently mapped legacy Bisync and unknown direction
   values to local-to-remote sync when opening or saving a task.
2. **Scope:** editor spinner selection, save mapping, user-visible explanation, and pure mapping
   regression tests for legacy directions 5/6, unknown values, invalid positions, and explicit
   supported replacements.
3. **Out of scope:** full C03 audit of import/migration and all task launch paths; adding unsupported
   Bisync execution, initialization, or recovery; user-facing preview launch/review; translation
   publication; device/provider acceptance.
4. **Preconditions:** verified base `4d4bdca04b54b61050ef1eb2c66bf04e08829cea`; legacy directions 5/6
   remain absent from the ordinary editor choices; the activity persists the selected spinner mode.
5. **Design:** add a visible placeholder for any existing direction not represented by the editor.
   Keeping it selected preserves the exact saved integer during unrelated edits; only an explicit
   supported selection replaces it. Invalid selection resolution returns no direction, never a
   local-to-remote default.
6. **Safety invariants:** never coerce directions 5/6, missing/default 0, negative, or future unknown
   values to one-way sync; preserve existing task data unless the user deliberately changes mode;
   unsupported mode stays blocked by the existing worker capability checks.
7. **Implementation:** CloudBridge commit
   `27043677e0777f7078f2dd771789ac1a9d4da45b` (base
   `4d4bdca04b54b61050ef1eb2c66bf04e08829cea`) updates `TaskActivity`, direction mapping, default
   strings, and adds `SyncDirectionObjectTest`.
8. **Reuse:** existing task persistence and supported direction list; no DB schema or task format
   changes.
9. **Retired:** fallback to spinner item zero for unsupported saved directions and fallback to
   local-to-remote for invalid spinner positions.
10. **Failure behavior:** an unsupported saved mode remains visibly identified and is written back
    unchanged while other fields are edited; malformed spinner selection without a saved value
    blocks saving with an explanation.
11. **Tests:** Windows JDK 21.0.8 / Gradle 8.13 / installed Android SDK run passed
    `:app:testOssDebugUnitTest` (95 tests: 94 passed, 1 existing platform-capability skip),
    `:app:compileOssDebugAndroidTestJavaWithJavac`, `:app:lintOssDebug`, and `git diff --check`.
    Lint reports 97 non-baseline warnings, including 2 new MissingTranslation warnings for the
    English strings to be submitted through Weblate/Crowdin; 2 errors and 428 warnings are filtered
    by the existing baseline, with 76 stale baseline entries. Android instrumentation was compiled
    but not executed; Galaxy S26 and Proton access remain **NOT RUN**.
12. **Acceptance:** this concrete editor downgrade is fixed and regression-tested. C03/WP04 remain
    **PARTIAL** until imports, migrations, and every launch path are freshly audited against fixtures.
    The overall WP08 preview UX and all signing/provenance/release gates remain open; no APK/PR/release
    is claimed.
13. **Rollback/next:** revert only `27043677e0777f7078f2dd771789ac1a9d4da45b`; no task records or
    provider/native state were modified by tests. Next continue the durable user-facing Bisync
    preview request/review path; keep mutation, initialization, and recovery unavailable.
