# CloudBridge + rclone execution ledger

This ledger records implementation evidence for `CloudBridge-rclone-Luna-Master-Handoff.md`.
This tracked copy is the app-specific evidence ledger. The current task workspace also keeps a
separate cross-repository ledger outside this repository; it is not a portable repository file.

## Standing instructions

- The user-attached complete master handoff, revision 2026-09-22 (SHA-256 recorded in the cross-repository task ledger), is authoritative.
- Work one bounded package at a time and follow the current package dependencies in section 7.
  WP08 and WP09 remain partial; WP10 has a partial app implementation and is not accepted.
- Preserve Bisync, Proton Drive, scheduling, Obsidian and useful CloudBridge functionality.
- Fix defects at their owning layer and test rclone independently before app integration.
- Missing device or live Proton access is `NOT RUN`, never a pass.
- Use disposable test data only. `Proton:RoundSync-Test` is not permission to delete an existing directory.
- The final app integration must use Rareities/rclone at an immutable, reproducible revision;
  a moving branch or silent fallback to `thies2005/rclone` is not acceptable.
- Older dated GitHub snapshots below retain their original observations. The 2026-09-25
  refresh supersedes broad “no workflow runs/releases” wording: the connector returned no
  combined status checks and no PR-triggered runs for checked heads; push/manual runs and a
  complete release inventory were not verified.

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

WP00 is **PARTIAL**. Baseline identity, current app pin, repository instructions, CI/PR refresh and gap list are
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

WP02 is **PARTIAL for the master package**: the bounded CloudBridge shortcut and diagnostic
sink implementation is present, and its available unit/lint gates passed. The source commit
is persisted. This does not claim that every process
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

WP03 is **PARTIAL for the master package**. The bounded import/rollback and production
secret-storage implementation is present, but full crash/disk-full fault injection,
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

WP04 is **PARTIAL for the master package**. The bounded profile/run ownership implementation
is present, but this is not full application acceptance. Ephemeral file-explorer work still uses its compatibility path, UI
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
and real-device acceptance remain explicit open gates; master WP05 acceptance remains
**PARTIAL** until all retained lifecycle and failure-boundary tests are evidenced. Do not treat Gradle `UP-TO-DATE` as a
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
These commits are local to a separate task-owned rclone preview-state worktree; they are not yet published to GitHub and
CloudBridge intentionally remains pinned to the last published immutable engine SHA `fe775a8…`.

1. **Objective:** provide a native-safe way for an app-owned preview to use compatible accepted
   Bisync state in a fresh isolated workdir without updating the live profile listings.
2. **Scope:** rclone `CopyCompatibleState`, source-guarded byte-exact clone, CLI-only
   `--preview-state-from`, strict validation/docs/tests. The change is prepared independently from
   a separate archival rclone-history checkout and its unrelated generated files.
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
    Lint reports 97 non-baseline warnings, including 3 new MissingTranslation warnings for the
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

## 2026-09-24 - CloudBridge WP08 user-mediated preview request/review (PARTIAL)

1. **Objective:** expose the durable Bisync dry-run owner through a user-mediated request and
   path-free review/history surface without enabling sync, initialization, recovery, or apply.
2. **Scope:** internal task-menu entry and non-exported activity; explicit absent-state policy
   choice; bounded deletion thresholds; durable path-free admission request; active-owner/history
   queries; connected-network WorkManager preflight admission; exact-owner queued resume; profile
   work tags; fail-closed engine capability gate; path-free history counts/status.
3. **Out of scope:** mutation authorization/revalidation at a write boundary, initializing a
   baseline, recovery/restore, compatible-state enablement on the currently published app pin,
   translated resource publication, device/provider acceptance, APK/PR/release.
4. **Preconditions:** verified base a5c085c2f9b59e43cc0f457e87de42102b04ce13; existing DB v15
   identity/owner, exact-pin preview command, preflight policy/coordinator, process ownership and
   startup reconciliation are retained. Current app engine pin remains
   fe775a8b58cf217fdf4bd34f0975af1e4c19c1a0.
5. **Design:** admission stores only profile UUID/revision/fingerprint and explicit review/policy
   fields in WorkManager; raw endpoints, filters and native output remain transient. User must
   choose one absent-state policy; the preference is bound to identity only if state is proved
   absent. UI labels counts as display-only and recent history as staleable; no preview result
   authorizes a write.
6. **Safety invariants:** only directions 5/6 expose preview; ordinary Start Task is hidden for
   those modes; no fallback to weaker engine/state capability; exact owner token/identity remains
   required; queued/interrupted/recovery owners block a second request; uncertain process stop
   remains a hold; WorkManager payload key set is regression-tested to exclude endpoints; no
   remote/local mutation or Proton test data was used.
7. **Implementation:** CloudBridge commit
   d009c1e58781e4c07030bbbbb7d2f0373e711874 (base
   a5c085c2f9b59e43cc0f457e87de42102b04ce13) adds the user-mediated activity, admission
   scheduler/worker, profile active/history queries and tags, capability gate, UI resources and
   regression/instrumentation test source. Earlier task-mode preservation commit remains
   27043677e0777f7078f2dd771789ac1a9d4da45b. User-owned .android/ remains unstaged and
   untouched.
8. **Reuse:** DB v15 preview identity/repository, persisted fresh-preflight evidence, existing
   read-only preflight coordinator, pinned native preview CLI, owner-scoped cancellation, WorkManager
   foreground policy, existing notifications and task menu patterns.
9. **Retired:** no user-facing path from Bisync task to a durable review screen; no path-free
   admission payload regression; no automatic absent-state preference; no capability downgrade.
10. **Failure behavior:** stale/missing/changed profile or preflight, unsupported engine/state,
    dispatch failure and invalid policy stop before native preview; only enum reason codes and
    opaque owner IDs are surfaced; interrupted or unconfirmed owners stay blocked. No error path
    starts normal sync or authorizes apply.
11. **Tests:** Windows JDK 21.0.8 / Gradle 8.13 / Android SDK 36 offline run passed
    :app:testOssDebugUnitTest (97 tests: 96 passed, 1 existing platform-capability skip, 0
    failures/errors), :app:compileOssDebugAndroidTestJavaWithJavac, :app:lintOssDebug, and
    git diff --check. Lint reports 143 visible warnings; the existing baseline filters 2 errors
    and 428 warnings, with 76 stale entries. Forty-five preview UI strings plus two task-mode
    strings are currently default-English only and await Weblate/Crowdin; no localized resource was
    hand-edited. adb devices -l returned no attached devices, so Android instrumentation was
    compiled but NOT RUN. Galaxy S26/One UI actual API/firmware, Samsung acceptance and live
    Proton remain NOT RUN. No app APK/native rebuild, PR, CI publication, signing or release.
12. **Acceptance:** the user-mediated preview request/review slice is implemented and JVM/source
    checks pass. WP08 remains PARTIAL: no initialization, recovery/restore, apply or mutation
    boundary; compatible-state preview still fails closed on the published engine pin; UI/runtime,
    fault-boundary and instrumented behavior lack device evidence. Release gates remain closed.
13. **Rollback/next:** revert only d009c1e58781e4c07030bbbbb7d2f0373e711874; retain the earlier
    scheduler/worker, DB v15, native protocol and C03 preservation commits. No remote or native
    Bisync state was touched. Next continue the handoff sequence by independently validating the
    clone-capable Rareities/rclone revision and its Proton changes, then integrate only a reviewed
    immutable fork commit in CloudBridge. Follow with mutation-boundary and recovery/restore work
    strictly at the owning layer; submit strings through the supported translation workflow; keep
    Samsung/Proton NOT RUN and do not open a release until all acceptance/provenance/signing gates
    pass.

## 2026-09-24 - WP08 preview history freshness bound to native preflight (PARTIAL)

1. **Objective:** make preview-history freshness conservative when a later persisted preflight
   finds that the native Bisync state or accepted baseline has changed.
2. **Scope:** pure history freshness policy, activity label mapping, JVM tests, and
   instrumentation-source compatibility.
3. **Out of scope:** mutation/apply authorization, initialization, recovery/restore, DB changes,
   engine pin changes, translation publication, device/provider acceptance, PR/release.
4. **Preconditions:** the DB v15 preview identity/owner store and persisted preflight status are
   authoritative; current history labels are expressly display-only.
5. **Design:** require a successful current identity/time check plus a persisted current preflight
   that still matches the operation. For compatible state, compare the complete accepted baseline
   (profile, engine, endpoint scope/account fingerprints, filters, comparison, state version, and
   baseline fingerprint). For absent state, require state still absent, initialization-required,
   and no accepted baseline. Missing evidence has its own non-fresh label.
6. **Safety invariants:** never interpret freshness as permission to mutate; never treat missing or
   unknown state as fresh; do not persist paths or endpoint data.
7. **Implementation:** CloudBridge commit `eb7c449a7089f2cf1cdcccb0779fef2499aafff7` changes
   five app files: `BisyncPreviewOperation.kt`,
   `BisyncPreviewActivity.kt`, default-English `strings.xml`, JVM freshness regressions, and the
   repository instrumentation test expectation. Follow-up commit `900f8e1` adds endpoint-scope
   and absent-state baseline mismatch assertions. No schema or rclone pin change. The native build
   used the pre-existing app cache after verifying it resolved exactly to the pinned Rareities
   commit `fe775a8b58cf217fdf4bd34f0975af1e4c19c1a0`; `rclone:buildAll` rebuilt all four ABIs.
   `rclone:checkoutRclone` was excluded because the host Git runtime cannot fetch over HTTPS.
8. **Reuse:** `BisyncPreflightStatus`, its accepted baseline, current profile identity, and the
   existing 15-minute display-only window.
9. **Retired:** no execution path or persisted state retired; latest native-state disagreement can
   no longer retain a “recent for display” label.
10. **Failure behavior:** a mismatch returns `NATIVE_STATE_CHANGED`; absent preflight/baseline
    returns `NATIVE_STATE_UNVERIFIED`. Neither leads to normal sync or apply.
11. **Tests:** Windows JDK 21.0.8 / Gradle 8.13 / Android SDK 36, offline: focused regression
    passed again after commit `900f8e1` added endpoint-scope and absent-state baseline mismatch
    assertions; `:rclone:buildAll` passed for arm, arm64, x86, x86_64 from the verified pinned local
    source cache; full `:app:testOssDebugUnitTest` passed (99 tests: 98 passed, 1 existing
    platform-capability skip, 0 failures/errors); `:app:compileOssDebugAndroidTestJavaWithJavac`
    passed; `:app:lintOssDebug` passed with 145 visible warnings, 2 errors and 428 warnings filtered
    by the existing baseline, and 76 stale baseline entries; `git diff --check` passed. Two new
    default-English strings await Weblate/Crowdin. Android instrumentation execution, Galaxy S26
    actual API/firmware acceptance, and live Proton tests remain **NOT RUN**.
12. **Acceptance:** this history-display freshness gap is fixed and verified on JVM/build/lint.
    WP08 remains PARTIAL: no mutation boundary, initialization, recovery/restore, device/fault
    acceptance, compatible-state feature on the published pin, APK, PR, signing, CI publication, or
    release evidence.
13. **Rollback/next:** revert only the WP08 preview-history freshness commit; no profile or native
    Bisync state was touched. Continue with preservation/restore and mutation-boundary evidence at
    the owning layer; do not expose initialization/apply before fault-boundary gates pass. Keep
    Samsung/Proton NOT RUN and release gates closed.

## 2026-09-24 - WP08 ordinary worker direction gate (PARTIAL)

1. **Objective:** ensure Bisync and unknown direction values cannot enter the ordinary sync worker.
2. **Scope:** centralize the ordinary-worker direction allowlist in `SyncDirectionObject`, reuse it
   from `SyncWorker`, and add direct regressions for supported, Bisync, and invalid values.
3. **Out of scope:** preview execution, native/apply authorization, initialization/recovery, schema,
   localization, device/provider tests, engine pin changes, PR or release.
4. **Preconditions:** legacy `SyncWorker` handles only the six regular one-way/copy directions;
   Bisync modes 5/6 must remain on the separate reviewed preview path.
5. **Design:** derive worker support from the canonical spinner direction mapping instead of a second
   hand-maintained list. Unsupported and unknown values fail closed.
6. **Safety invariants:** the normal worker never executes Bisync; invalid directions do not fall
   through to a default sync; this helper is not an authorization to initialize or mutate state.
7. **Implementation:** three app files changed: add
   `SyncDirectionObject.isRegularSyncWorkerDirectionSupported`, call it before `SyncWorker` starts,
   and test that every spinner-supported direction is accepted while both Bisync values and 0, -1,
   and 99 are rejected. Source implementation is commit `25f2199`; this entry is its documentation follow-up.
8. **Reuse:** `SPINNER_TO_DIRECTION`/`spinnerPositionForDirection` remain the single source for
   supported task modes; `SyncWorker` still emits its existing unsupported-direction failure.
9. **Retired:** removed the worker's duplicated six-value guard; no task formats, worker behavior for
   the six supported directions, or persisted state was otherwise changed.
10. **Failure behavior:** an unrecognized or Bisync direction exits through
    `FAILURE_REASON.UNSUPPORTED_DIRECTION` before starting rclone.
11. **Tests:** forced focused run of `:app:testOssDebugUnitTest --tests
    ca.pkay.rcloneexplorer.Items.SyncDirectionObjectTest --rerun-tasks --offline` passed (4 tests,
    0 failures/errors/skips). The full offline verification command completed: four-ABI
    `:rclone:buildAll`, `:app:compileOssDebugAndroidTestJavaWithJavac`, and `:app:lintOssDebug`
    passed; the full unit-test task was up-to-date in that invocation. `git diff --check` passed.
    The full suite's previously recorded executed result at this same change set is 100 tests (99
    passed, 1 existing platform-capability skip). Instrumentation execution, Samsung acceptance,
    and Proton live testing remain **NOT RUN**.
12. **Acceptance:** the ordinary worker's direction gate is centralized and directly regression-
    tested. WP08 remains PARTIAL: no initialization, recovery/restore, mutation boundary, device
    fault-injection evidence, or compatible-state preview on the currently published app pin.
13. **Rollback/next:** revert the source commit recorded by the follow-up if this gate regresses;
    no user or native Bisync data was touched. Continue WP08 preservation/restore and mutation-boundary
    proof without exposing initialization or apply; keep Samsung/Proton NOT RUN and release gates shut.

## 2026-09-24 - CloudBridge WP10 schedule and session-guardian slice (PARTIAL)

1. **Objective:** correct local schedule occurrence selection after the configured time, and stop
   the session guardian from treating arbitrary non-network provider failures as proof of expired
   credentials.
2. **Scope:** pure next-enabled-weekday calculation; schedule re-arming after wall-clock/time-zone
   changes; receiver null/action safety; explicit credential-provider eligibility; typed and
   sanitized probe failure categories; focused regression coverage.
3. **Out of scope:** replacing the full trigger/AlarmManager architecture; durable requested/actual
   run-time state, global dispatch ownership, bounded coalescing, foreground promotion, missed-run
   policy, Android quota/permission behavior, notification-denial behavior, or device acceptance.
4. **Preconditions:** existing trigger storage remains authoritative; weekday bits are Monday=0 to
   Sunday=6; schedule `time` is minutes after midnight; interval triggers remain unchanged by
   wall-clock/time-zone re-arming.
5. **Design:** compute the first strictly-future occurrence on an enabled local weekday; cancel the
   prior schedule alarm before replacing it; requeue schedule triggers only on time/time-zone
   changes; admit OAuth providers or explicit Proton Drive/Internxt capabilities only when the
   corresponding stored credential exists; classify native stderr into bounded categories without
   persisting provider text.
6. **Safety invariants:** no alarm is scheduled for an invalid minute or empty/invalid weekday mask;
   time changes do not restart interval timers; an unknown failure never requests re-authentication;
   raw provider output and credentials are not surfaced in the probe result; no claim is made that
   Android delivered an alarm or background execution on time.
7. **Implementation:** CloudBridge source commit
   `34a23eb3631ee47fd63cb1321d9f72620a609cc5` (`fix(android): correct schedules and guardian
   classification`), parent `d1290cd`. It updates trigger scheduling and broadcast
   handling, adds `ScheduleTimeCalculator`, provider gating and typed failure classification, and
   adds JVM regression source. The user-owned untracked `.android/` directory was not staged or
   modified.
8. **Reuse:** current trigger DB and PendingIntent identity, AlarmManager mode preference, existing
   remote OAuth capability mapping, `launchClaimed` native process ownership, and existing guardian
   notification path. No schema migration or dependency change.
9. **Retired:** same-minute/every-day scheduling shortcut; re-arming interval triggers on a wall-clock
   update; token-shaped config alone as authorization to probe an arbitrary provider; raw/generic
   non-network probe failure as an expired-session notification.
10. **Failure behavior:** invalid schedule configuration cancels the previous alarm and stops;
    network/rate-limit/integrity/unknown probe results are non-auth failures; only positively
    classified authentication errors send the existing session-expired notification. Classification
    is bounded string evidence from rclone stderr, not a structured backend API guarantee.
11. **Tests:** `:app:compileOssDebugKotlin` passed on JDK 21.0.8 / Gradle 8.13 / Android SDK 36
    offline with `-Pkotlin.compiler.execution.strategy=in-process -x :rclone:buildAll
    -x :safdav:compileDebugJavaWithJavac`. The new Java classifier compiled independently with
    JDK 21; a temporary JVM smoke run passed 20 schedule/provider/classification scenarios, and
    its temporary source scripts were removed. `git diff --cached --check` passed before commit.
    The full `:app:testOssDebugUnitTest` task was attempted but is **BLOCKED/NOT PASSED** by
    `AccessDeniedException` while closing Android SDK/cached JAR zip files (also reproduced under
    JDK 17 and JDK 21); Android Java compilation is consequently unverified. Samsung alarm/doze/
    force-stop/time-zone behavior and live provider round trips are **NOT RUN**.
12. **Acceptance:** this schedule-calculation and guardian-notification slice is implemented and
    source/Kotlin/smoke checked. WP10 remains **PARTIAL** until authoritative dispatch, unique run
    claims, coalescing, requested-vs-actual timestamps, foreground/permission/quota handling,
    missed-run behavior, and runtime evidence are audited and verified. WP08/WP09 remain PARTIAL;
    Galaxy S26 / One UI 8.5/9 with actual API/firmware and live Proton remain NOT RUN. No APK,
    PR, signing, or release evidence.
13. **Rollback/next:** revert only `34a23eb` to `d1290cd`; no schedules, task data, remote data,
    or native Bisync state were modified by tests. Resume the earliest incomplete prerequisite
    package from the global ledger; continue the remaining WP08 state-preservation/mutation-boundary
    work and WP09 dependency decision before treating WP10 as accepted. Keep release gates closed.

## 2026-09-24 - WP08 preview foreground-promotion cancellation (PARTIAL)

1. **Objective:** ensure a Bisync preview or preview-admission worker cannot leave an unconfirmed foreground-promotion request running after timeout or interruption.
2. **Scope:** source commit `755454e35838384e1b161053cc4fa7b15469ad1e` on `codex/luna-implementation`; shared promotion helper, preview/admission workers, and focused helper regression source. No data or engine pin changed.
3. **Out of scope:** initialization/apply authorization, native backup/restore lifecycle, Proton/device acceptance, PR, APK, signing or release.
4. **Preconditions:** both workers already awaited `setForegroundAsync` before preflight/native execution; the shared helper already canceled on timeout/interruption for ordinary sync.
5. **Design:** use the bounded shared helper that cancels on timeout/interruption, restores interrupt status, and raises failure into the workers' existing conservative failure paths.
6. **Safety invariants:** no preview/native work proceeds unless promotion is confirmed; admission failure remains sanitized; preview failure remains unavailable/interrupted conservatively; no listing mutation is authorized.
7. **Implementation:** added `requireForegroundPromotion`, replaced raw timed `.get()` calls in both preview workers, and added regression source for timeout cancellation and platform-cause preservation.
8. **Reuse:** existing `awaitForegroundPromotion`, worker failure handling, and durable preview repository.
9. **Retired/decision:** raw waits did not cancel their futures after timeout/interruption; timeout duration and foreground-service policy are unchanged.
10. **Failure behavior:** timeout/platform failure enters the existing catch path; timeout/interruption cancels the future; interrupt status is restored; preview does not authorize mutation.
11. **Tests:** forced offline `:app:compileOssDebugKotlin` passed on JDK 21.0.8 / Gradle 8.13 / SDK 36 with in-process Kotlin and rclone/SAF Java compilation exclusions. `git diff --check` passed before commit. Focused unit-test execution is **BLOCKED/NOT PASSED**: the initial attempt could not start Go for `:rclone:checkoutRclone`; excluding native checkout/build reached Java compilation, which failed on `AccessDeniedException` for cached `androidx.arch.core:core-common:2.2.0` and subsequent missing Java symbols. No focused/full unit tests ran. Instrumentation, actual Galaxy S26 API/firmware, and live Proton are **NOT RUN**.
12. **Acceptance:** Kotlin source compiles, but the new JVM regressions have not executed and Java/test compilation remains blocked. WP08 stays **PARTIAL**; no restore, mutation, provider, or device acceptance is claimed. WP09/WP10 and release gates remain open.
13. **Rollback/next:** revert only source commit `755454e35838384e1b161053cc4fa7b15469ad1e` if review identifies an unsafe worker transition. `.android/` remains untouched. Continue WP08 run-scoped preservation/explicit restore and remaining native failure boundaries; keep initialization/apply and release closed. Samsung actual API/firmware and Proton remain **NOT RUN**.

## 2026-09-24 - CloudBridge WP10 durable one-off dispatch and foreground gate (PARTIAL)

1. **Objective:** bring File Explorer's one-off "sync this folder" path under durable run ownership and require confirmed Android foreground promotion before native execution.
2. **Scope:** source commit `115cd94b2dec78841c85f211a721f0e581799b5c` on `codex/luna-implementation`; ephemeral profile identity, transactional run admission, guarded WorkManager dispatch, `SyncWorker` claim/promotion/failure handling, and focused tests. Published rclone pin remains `fe775a8b58cf217fdf4bd34f0975af1e4c19c1a0`.
3. **Out of scope:** global dispatcher/outbox, schedule and Obsidian coalescing, exact requested/actual time policy, Android quota/notification-denial/device matrix, app-owned Bisync backup/restore, Proton, PR, APK, signing or release.
4. **Preconditions:** WP04-06 durable profile/run/resource ownership is present; `FileExplorerFragment` uses `SyncManager.queueEphemeral`; `.android/` is user-owned and excluded.
5. **Design:** identify equivalent one-off requests by semantic profile fingerprint; atomically persist profile and run; pass run ID/token in WorkManager input; claim once; await the initial `setForegroundAsync` future for up to ten seconds before entering `handleTask`.
6. **Safety invariants:** duplicate active profile requests do not execute concurrently; no random legacy task-ID mutation; only the exact still-QUEUED owner may be deferred/blocked on dispatch or invalid input; a worker that failed to claim cannot finish the actual owner's state; failed/unavailable/timed-out promotion does not start native sync; receiver is unregistered in `finally`.
7. **Implementation:** stable UUID from semantic fingerprint; `ProfileStore.upsertEphemeralTask`; `RunRepository.queueEphemeralTask` and compare-and-set queued finalization; `SyncManager` guards Data construction and WorkManager enqueue; `SyncWorker` reads owner metadata before parsing, blocks only a still-queued unstartable request, requires durable claim, tracks local claim ownership, creates API-appropriate `ForegroundInfo`, awaits promotion, records a deferred pre-native failure and always unregisters its receiver.
8. **Reuse:** existing profile/run repository, semantic fingerprint, WorkManager data/tags, foreground notification implementation, and File Explorer one-off sync workflow.
9. **Retired/decision:** removed `Random().nextLong()` assignment to `Task.id`. Equivalent requests are currently rejected while active; one-follow-up retention and user-visible duplicate feedback remain open WP10 behavior.
10. **Failure behavior:** admission rejection does not enqueue; payload/build/enqueue exception attempts a QUEUED-only DEFERRED transition; missing/invalid payload attempts QUEUED-only BLOCKED; stale/non-owner callbacks leave PREFLIGHT/RUNNING unchanged; FGS unavailable or unconfirmed is DEFERRED before native execution.
11. **Tests:** `:app:compileOssDebugKotlin` **PASS** on JDK 21.0.8 / Gradle 8.13 / Android SDK 36 using offline dependencies with Java/JNI-adjacent compilation exclusions. Runtime probe **PASS** for future success, platform failure cause, timeout/cancel and interruption restoration; javac also emitted the known ZipFS `AccessDeniedException` while closing the cached stdlib archive after generating the probe class. Standard Java/test compilation is **BLOCKED/NOT PASSED** by `AccessDeniedException` on cached `androidx.arch.core:core-common:2.2.0`; Kotlin-only test compilation with Java compilation excluded then lacked existing Java symbols. `git diff --cached --check` **PASS**. Instrumentation test **NOT RUN** (`adb devices -l` empty); Galaxy S26 actual API/firmware and live Proton **NOT RUN**.
12. **Acceptance:** only this WP10 slice is implemented. Full schedule/outbox ownership, one-follow-up coalescing, requested/actual timestamps, permission/quota/doze/force-stop/reboot behavior, guardian policy and Galaxy acceptance remain open. WP10, WP08 and WP09 remain **PARTIAL**; release gates remain closed.
13. **Rollback/next:** revert only `115cd94b2dec78841c85f211a721f0e581799b5c` if phase/ownership review finds a regression. No remote/provider/native data, pin, PR, APK, signing or release state changed; `.android/` remains untouched. Resume WP08 preservation/publication faults and run-scoped backup/restore, then WP09 independent Proton/dependency work, before continuing WP10.

## 2026-09-24 - WP08 app backup-root reuse constraint (PARTIAL)

1. **Objective:** translate new native evidence about backup-directory reuse into CloudBridge's required apply/recovery safety contract.
2. **Scope:** design/audit checkpoint only; no CloudBridge source, engine pin, task/profile database, user data, provider, or Android state changed.
3. **Out of scope:** selecting/creating endpoint paths, durable manifest implementation, apply/recovery UI, remote provider acceptance, live Proton, Galaxy S26 acceptance, PR, APK, signing, or release.
4. **Preconditions:** CloudBridge `codex/luna-implementation` remains at `5cb478bba439ce27842f5e1fa0f59dce313b62bd`; app pin remains `fe775a8b58cf217fdf4bd34f0975af1e4c19c1a0`; rclone source-test commit `577d2d6c6a2e366f1d6f3a52fa036dfdab3d45e6` is local and independently verified.
5. **Design:** each mutation attempt must obtain a new durable run ID and a fresh pair of endpoint-specific backup roots. Reuse after uncertain completion, run retry, collision, or any pre-existing root must be rejected; the mapping must remain available for explicit restore across process restart.
6. **Safety invariants:** native backup roots are outside their corresponding synchronized roots and use the same endpoint/backend; no mutation begins until both roots are reserved, collision-checked, and their run-owned mapping is durable. Never infer a backup is unused because preview/cache state is gone.
7. **Implementation:** no app implementation yet. Source audit confirms the current preview command exposes no backup/apply/recovery options; `getBisyncRemoteAccountFingerprint` intentionally rejects local, crypt, alias, cache, and path-alias remotes, while only stable ProtonDrive/Internxt/Drime identities can currently pass the remote account-fingerprint gate. This is not proof of their backup-path semantics.
8. **Reuse:** durable run IDs, endpoint claims, existing secret-free endpoint fingerprints, native `--backup-dir1/2`, and the fresh-per-run requirement evidenced by local native tests; follow-up rclone commit `01e1d0460ae686a5e2324ea19ea2a3bf4b09f6d4` verifies the overwrite hazard for both flags.
9. **Retired/decision:** do not store a mutable shared backup directory per profile. Do not promote aliases/wrappers or unsupported providers by guessing their path mapping; root endpoints with no safe sibling and unverified same-backend/out-of-root behavior must remain blocked.
10. **Failure behavior:** inability to reserve both fresh roots, persist a restore mapping, verify endpoint identity, or prove same-backend/non-overlap semantics blocks initialization/apply. Keep existing preview-only UI and recovery-required states.
11. **Tests:** Path1 and Path2 backup-root reuse subtests passed 100/100 repetitions each; full Bisync and ProtonDrive package tests passed (27.334s / 0.704s), plus vet, all-package build, gofmt and diff check on Go 1.26.8 Windows/amd64. CloudBridge full app JVM tests remain **BLOCKED/NOT PASSED** at JDK ZipFS `AccessDeniedException`; emulator/instrumentation has no connected device. Samsung actual API/firmware and live Proton remain **NOT RUN**.
12. **Acceptance:** the collision/reuse hazard is now established for local native storage, but the app contract and executable feature are not implemented. WP08 remains **PARTIAL** and initialization/apply/recovery remain disabled; WP09 and release gates remain open.
13. **Rollback/next:** no source rollback is needed. Implement the fail-closed endpoint-specific planner/reservation and durable run-owned manifest with testable restart/restore transitions; first prove local sibling and supported remote sibling semantics independently, then wire native mutation only after tests and app compilation can execute. Preserve `.android/`; do not push, open a PR, build a release APK, sign, or publish from this partial evidence.

## 2026-09-24 - CloudBridge Java compile retry under Android Studio JBR 25 (NOT PASSED)

1. **Objective:** retry the blocked Android Java/test compilation under a distinct installed JVM after JDK 21 ZipFS failures.
2. **Scope:** build-environment validation only; no CloudBridge source, dependency, version pin, SDK installation, or provider data changed.
3. **Out of scope:** APK packaging, unit/instrumentation execution, emulator/device acceptance, Galaxy S26, Proton, PR, signing, or release.
4. **Preconditions:** CloudBridge branch `codex/luna-implementation` at `1edf355c9b80876daef424c6feff260a24022785`; Gradle 8.13 and SDK paths already configured; task-local Android user home; offline dependencies.
5. **Design:** confirm Gradle launcher compatibility with the bundled Android Studio JBR, then run only `:safdav:compileDebugJavaWithJavac`.
6. **Safety invariants:** keep all source unchanged, do not clear/replace caches, do not change SDK permissions/settings, and leave user-owned `.android/` untouched.
7. **Implementation:** none. JBR 25.0.3 launched Gradle successfully; Java compilation emitted an internal javac diagnostic and failed while ZipFS closed Android SDK `core-for-system-modules.jar` / `core-lambda-stubs.jar`, reporting `AccessDeniedException`.
8. **Reuse:** installed Android Studio JBR, Gradle 8.13, SDK 36, existing task-local Android user-home path.
9. **Retired/decision:** a different JVM did not clear the access boundary; do not keep cycling JDKs or widen permissions without a specific writable-mirror/CI plan.
10. **Failure behavior:** Gradle treats the Java compile task as failed despite source diagnostics; no downstream unit-test task is considered executed or passed.
11. **Tests:** `gradlew --offline --no-daemon --version` under JBR25 **PASS**; `:safdav:compileDebugJavaWithJavac` **FAIL/NOT PASSED** on ZipFS `AccessDeniedException`; full app unit tests **NOT RUN**. No connected Android device is available; Samsung actual API/firmware and live Proton are **NOT RUN**.
12. **Acceptance:** app Java/test compilation remains blocked, so WP08/WP09/WP10 and release acceptance remain partial; no APK or release artifact was produced.
13. **Rollback/next:** no rollback needed. Continue native/backend and pure app work; revisit Java tests in controlled CI or after establishing an explicitly scoped writable SDK/dependency mirror. No PR, signing, release, or pin change; `.android/` remains untouched.

## 2026-09-24 - WP08 Bisync backup-root claim parsing (PARTIAL)

1. **Objective:** ensure an app-launched native Bisync process claims both explicit backup roots together with its two sync endpoints, and make ambiguous argv fail closed instead of reserving the wrong paths.
2. **Scope:** CloudBridge `codex/luna-implementation`, source parent `7baf237bda62ea8904eb15b495c759108b14097e`; source commit `5cbceb5578806ac115e0195e951a0dd88b82f258`. No rclone source, engine pin, DB schema, user data, or provider state changed.
3. **Out of scope:** creating/reserving run-owned backup directories, durable restore mapping, apply/recovery UI, path non-overlap/same-backend validation, Proton/SAF/remote proof, WP09/10 completion, PR, APK, signing, and release.
4. **Preconditions:** WP08 remains active and mutation remains preview-only. Existing pre-launch coordinator/resource claims are in place. The WP14 migration addendum was read fully and remains a cross-cutting migration gate after its stated prerequisites; it does not replace master package numbering. `.android/` is user-owned and excluded.
5. **Design:** parse only known value-taking options before positional endpoints; if a non-equals unknown option makes arity ambiguous, classify globally. Parse both `--backup-dir1/2` forms; reject missing, empty, duplicate, option-shaped, malformed, or ambiguous values to a global claim. Stop backup-option scanning at `--`.
6. **Safety invariants:** claims cover every known mutable root for the process lifetime; no guessed endpoint from an option value; unknown syntax serializes globally; paths remain hashed in durable claims; backup-root path policy and mutation authorization remain separately gated.
7. **Implementation:** adds `parsePositionalTargets` and hardens `parseBisyncBackupDirTargets` in `EndpointConflictCoordinator.kt`; the coordinator adds backup endpoint resources to the same pre-launch claim. A read-only subagent review found the `--filter-from` positional-shift risk and requested durable-claim coverage; both are addressed with known filter-file options, global fallback for unknown option arity, parser tests, and instrumentation contention tests for both roots and unknown-option fallback.
8. **Reuse:** existing command coordinator, `EndpointResource`, and transactional `ResourceClaimRepository`; existing rclone native evidence that backup-root reuse can replace older preserved bytes.
9. **Retired/decision:** removed best-effort skipping of unknown pre-endpoint flags, which could classify the filter file and one sync endpoint while missing the real other endpoint. Do not treat these claims as a run-owned manifest or proof that a backup destination is safe.
10. **Failure behavior:** malformed or ambiguous command syntax acquires a global claim; a conflicting operation is rejected before its process launches. Native command/provider failures continue through existing truthful process outcomes.
11. **Tests:** offline `:app:compileOssDebugKotlin` **PASS** on Temurin JDK 21.0.8 / Gradle 8.13 / SDK 36, with Kotlin in-process and rclone/SAF/app Java compile exclusions. `git diff --cached --check` **PASS**. Unit/instrumentation compilation **NOT PASSED**: Gradle stopped at `:safdav:compileDebugJavaWithJavac` with `AccessDeniedException` on SDK `build-tools/35.0.0/core-lambda-stubs.jar`; new parser/contention tests did not execute. ADB inventory **NOT RUN** because the sandboxed adb process failed before enumeration (`Cannot mkdir '\.android': Permission denied`). No rclone test was rerun because this slice changes only app code. Samsung actual API/firmware and live Proton remain **NOT RUN**.
12. **Acceptance:** parser source compiles and regression-test source covers endpoint extraction, both durable backup claims, and global fallback, but the tests did not execute. This slice is not WP08 acceptance: there is still no durable run-owned backup manifest/restore flow, path capability proof, or provider/device evidence; initialization/apply remains unavailable, WP08 partial, and release gates closed.
13. **Rollback/next:** revert only `5cbceb5578806ac115e0195e951a0dd88b82f258` if later executable tests expose a parser/claim regression. Next WP08 work: define endpoint-specific fresh backup-root/path constraints and atomic run-owned manifest/resource reservation; test restart/restore and the remaining native backup failure cuts. Keep app pin `fe775a8b58cf217fdf4bd34f0975af1e4c19c1a0`; no PR, APK, signature, or release changed. User-owned `.android/` remains the only untracked checkout path.

## 2026-09-24 - WP13/WP14 release automation and signing fail-closed fix (PARTIAL)

1. **Objective:** prevent an unattended master push from publishing an unaccepted beta and prevent release variants from silently using the debug signing key.
2. **Scope:** CloudBridge `app/build.gradle` and `.github/workflows/android.yml`; source commit `80d1732` on `codex/luna-implementation`. No DB schema, app identity, dependency, rclone pin, provider data, or endpoint behavior changed.
3. **Out of scope:** creating a new public release workflow, producing or signing a release APK/AAB, certificate continuity proof, migration/downgrade support, release provenance manifest, merging/publishing, or device/provider acceptance.
4. **Preconditions:** refreshed public CloudBridge default branch is `master` at `c492876258ca841232229249519abe92ff77c3a4`, with no open PR, workflow runs, or status checks observed. No local `keystore.properties`, complete `CB_*` signing environment, or production keystore is available. WP13/WP14 migration and acceptance gates remain open.
5. **Design:** normal push/PR/manual CI runs only OSS debug unit tests and debug packaging with `contents: read`; the workflow has no release/publish action. Release variants always select `signingConfigs.release`; packaging/sign/bundle tasks depend on a verifier requiring a readable keystore and all credentials.
6. **Safety invariants:** never fall back to Android debug signing for a release variant; no automatic public release; secrets are not logged or written by this workflow; production signing and publication remain disabled until the complete acceptance/provenance gate exists.
7. **Implementation:** removed master-push beta release/versioning and secret-bearing release steps, added PR CI, reduced token permissions to read-only, and retained only the unit-test/debug-APK CI path. Added `verifyProductionReleaseSigning` and corrected the stale automatic-beta comment.
8. **Existing code reused:** current Gradle release signing properties/environment variables, pinned GitHub checkout/setup actions, existing OSS debug variants, Gradle unit-test task.
9. **Code retired/decision:** retired `releaseKeyConfigured ? signingConfigs.release : signingConfigs.debug` and the `Create beta release` step. A future release workflow must be separately added after the handoff gates and artifact-provenance checks are implemented.
10. **Failure behavior:** missing/unreadable keystore or any signing value makes the release verifier fail before package/sign/bundle tasks; debug variants remain independent. CI never publishes a release.
11. **Tests:** JDK 21.0.8 / Gradle 8.13 / SDK 36 `:app:compileOssDebugKotlin` **PASS** with native and Java compile tasks excluded. `:app:verifyProductionReleaseSigning` returned the expected failure with no keystore/credentials; `:app:packageOssRelease --dry-run` showed the verifier dependency. Static workflow/signing assertions and `git diff --check` **PASS**. `:app:testOssDebugUnitTest` is **NOT PASSED**: it stopped before tests at `:safdav:compileDebugJavaWithJavac` with `AccessDeniedException` on a Gradle-transformed dependency JAR. No actionlint/YAML parser, GitHub Actions run, APK rebuild, or instrumentation run was available.
12. **Acceptance:** this closes the unsafe auto-publish/debug-signer fallback, not WP13/WP14. Database v8-to-v15 realistic migration/interruption/downgrade and prior-certificate continuity are unproven; WP08 durable backups/restore is partial; the old APK is debug-signed, not a candidate. Galaxy S26 actual API/firmware and live Proton remain **NOT RUN**. No release readiness is claimed.
13. **Rollback/next:** revert only `80d1732` if review finds a regression, but do not restore automatic publication or debug-signing fallback. Continue WP08 first; then close WP09 and preceding acceptance prerequisites. Before any release, implement/test the realistic migration and signer-continuity matrix, an evidence/provenance manifest, and a separately approved release path. `.android/` remains user-owned and untouched.

## 2026-09-24 - WP08 versioned read-only endpoint snapshot decoder (PARTIAL)

1. **Objective:** establish a strict, versioned, read-only decoding and capability-classification boundary for persisted profile endpoints before designing backup placement.
2. **Scope:** CloudBridge source/test commit `3406d2a27065657bbb94289095fffde3dddf7b40` on `codex/luna-implementation`; added `EndpointSnapshotCodec`, conservative shape classifier, and focused JVM regression source. No existing profile data or execution flow changed.
3. **Out of scope:** generating backup paths, creating provider directories, acquiring durable run-owned backup reservations/manifests, restore/restart recovery, enabling Bisync initialization/apply, rclone changes, Proton/device acceptance, PR, APK, signing, or release.
4. **Preconditions:** WP08 remains partial and apply/recovery unavailable. The existing mapper stores schema-v1 endpoint fields as UTF-8 byte-length-prefixed values and keeps the schema in profile settings. The persisted `remoteId` is an app config name, not immutable provider account identity. User-owned `.android/` stays excluded.
5. **Design:** parse exact UTF-8 byte lengths with strict malformed-input, size, field-count, duplicate-key, tag-layout, and schema checks; reject unknown versions. Classify only required endpoint shapes for the selected mode. The report is descriptive and cannot approve backup placement.
6. **Safety invariants:** no database/filesystem/provider/native-process access; no endpoint values in classifier reasons or `toString`; malformed, future, wrapped, SAF, or unknown forms fail closed; local mount/root and remote account/path proof remain mandatory; backup placement is always false.
7. **Implementation:** added `Database/EndpointSnapshot.kt` with schema-v1 decoding, redacted snapshot string forms, supported-profile-mode endpoint classification, and explicit local/runtime, remote-config/runtime, SAF, wrapper, missing, unknown-type, unknown-version, and unknown-mode states. Added `EndpointSnapshotCodecTest.kt` for UTF-8/delimiter roundtrip, truncation, future schema/version, malformed settings, unsupported endpoint shapes, and redaction.
8. **Existing code reused:** `LegacyProfileMapper.PROFILE_SCHEMA_VERSION`, its length-prefixed endpoint/settings encoding, `ProfileMode`, and current `RemoteItem` backend identifiers. No version bump or rewrite of existing profile identity/fingerprint was made.
9. **Retired/decision:** no existing code retired. Kept the current wire format and fingerprint stable; this decoder is an isolated foundation, not connected to previews, execution, or backup path generation.
10. **Failure behavior:** malformed settings/endpoints return typed failure without raw values; future schema/snapshot, unknown mode/type, SAF, and wrapped remote remain non-authorizing; required blank endpoints are reported missing.
11. **Tests:** JDK 21.0.8 / Gradle 8.13 / Android SDK 36 offline `:app:compileOssDebugKotlin` **PASS** with Kotlin in-process and native/SAF/app Java compile exclusions; `git diff --cached --check` **PASS** before source commit. Focused JVM JUnit task is **NOT RUN**: `:safdav:compileDebugJavaWithJavac` fails opening SDK `build-tools/35.0.0/core-lambda-stubs.jar` with `AccessDeniedException`, including after scoped SDK read access. Kotlin-only test compilation with Java tasks excluded fails on missing pre-existing Java symbols (`FilterEntry`, `RemoteItem`, and related classes), so it does not validate or execute tests. No instrumentation, Galaxy S26 API/firmware, Proton, Go, or rclone tests ran in this slice.
12. **Acceptance:** decoder/classifier source compiles, but focused regression methods remain unexecuted. This does not close WP08: provider-specific identity/path proof, collision-free fresh backup roots, durable run-bound intent/manifest, partial-write and kill-boundary tests, exact-byte restore and restart recovery remain open. Initialization/apply stays unavailable; WP09/WP10 and release gates remain partial/open.
13. **Rollback/next:** revert only `3406d2a27065657bbb94289095fffde3dddf7b40` if executable review/tests find a defect. Next WP08 slice must validate endpoint-specific root/mount/account capabilities, then design durable run-owned reservation/restore state before any native mutation path; resolve a usable JDK/SDK build path for actual JVM tests. Preserve `.android/`; no pin, external repository, PR, APK, signature, or release changed.

## 2026-09-24 - WP08 canonical endpoint separator correction (PARTIAL)

1. **Objective:** fix the schema-v1 decoder so it accepts the actual canonical profile encoding while continuing to reject malformed delimiters.
2. **Scope:** source fix commit `d95ea063dce4c124e05bb1125db6a2508975cc6d` on `codex/luna-implementation`; five-line parser correction in `EndpointSnapshot.kt`. The test already added by `3406d2a27065657bbb94289095fffde3dddf7b40` uses the real mapper and delimiter-bearing values.
3. **Out of scope:** profile format changes, fingerprint/identity changes, backup path generation, manifest/reservation, restore, mutation authorization, Proton/device work, PR, APK, signing, or release.
4. **Preconditions:** a read-only independent review found a P1: the mapper joins length-prefixed fields with `|`, while the initial decoder did not consume that separator and therefore rejected every canonical snapshot. No JUnit/runtime result had passed.
5. **Design:** consume exactly one canonical `|` between complete length-delimited UTF-8 payloads; reject absent, unexpected, or trailing separators. Embedded `|` remains ordinary payload data because byte length determines the boundary.
6. **Safety invariants:** parser remains read-only, bounded, strict UTF-8, and fail-closed; no path is logged or returned in failure details; classifier backup-placement result remains hard false.
7. **Implementation:** after decoding each field payload, verify the next byte is `|` when bytes remain, advance one byte, and reject a terminal separator.
8. **Existing code reused:** `LegacyProfileMapper` canonical serializer and the existing mapper-backed Unicode/delimiter regression source.
9. **Retired/decision:** no schema or wire change; the old decoder behavior was defective and is superseded by this correction. Do not revert this fix independently while retaining the decoder.
10. **Failure behavior:** missing/incorrect/trailing separators return `MALFORMED_SETTINGS` or `MALFORMED_ENDPOINT_SNAPSHOT` according to which encoded input is malformed.
11. **Tests:** JDK 21.0.8 / Gradle 8.13 / SDK 36 offline `:app:compileOssDebugKotlin` **PASS** after the fix, with native/SAF/app Java compilation excluded. Focused JVM JUnit remains **NOT RUN** because SAF javac fails before tests with SDK `core-lambda-stubs.jar` `AccessDeniedException`; direct Java probe compilation also failed before execution on inaccessible cached Kotlin stdlib/project classes. No runtime pass is claimed.
12. **Acceptance:** the concrete parser defect is corrected and source compiles, but regression methods have not executed; WP08 remains **PARTIAL**, initialization/apply stays unavailable, and backup/restore/provider/device/Proton gates remain open or **NOT RUN**.
13. **Rollback/next:** keep fix `d95ea063dce4c124e05bb1125db6a2508975cc6d` with the decoder. Re-run the mapper-backed JUnit regression when SDK/JDK archive access works, then continue endpoint-specific identity/mount/path proof and durable run-owned backup/restore design. No remote, pin, provider data, `.android/`, PR, APK, signing, or release changed.

## 2026-09-25 - WP08/WP10 app JVM test and Java-linkage correction (PARTIAL)

1. **Objective:** execute the app JVM suite after restoring Android Java compilation, and ensure the future-schema regression actually mutates the canonical settings snapshot.
2. **Scope:** CloudBridge source/test commit `70b2375f6d8cce4d65433b9658f7e0a6dcbf1809` on `codex/luna-implementation`; adds the missing `ScheduleTimeCalculator` import in `TriggerService.java` (WP10 Java-to-Kotlin service linkage) and corrects the delimiter-bearing schema mutation in `EndpointSnapshotCodecTest` (WP08 decoder test quality). No production rclone, pin, database, provider, or credential behavior changed.
3. **Out of scope:** scheduler redesign/coalescing/missed-run recovery, preview mutation authorization, backup reservation/restore, native Gradle build, APK packaging, instrumentation, Galaxy S26 acceptance, live Proton, PR, signing, or release.
4. **Preconditions:** parent CloudBridge HEAD `cbafa3d322a795f109aed86afcb161b0fab7744f`; user-owned untracked `.android/` was preserved. Used local JDK 21.0.8, Gradle 8.13, installed Android SDK with a task-local build-tools 36 override, an isolated workspace Gradle home, and offline dependencies. No ADB/provider test access was assumed.
5. **Design:** use the existing Kotlin `object`'s `@JvmStatic` API from Java with its explicit package import. Change schema bytes using the actual canonical `6:schema|1:1` sequence and assert the replacement differs from the original before decoding.
6. **Safety invariants:** only local compilation/JVM tests ran; `:rclone:checkoutRclone` and `:rclone:buildAll` were excluded. No network fetch, Android device, Proton account, app/provider data, production signing key, or release artifact was used.
7. **Implementation:** source commit fixes the Java symbol resolution. The test correction now changes schema version 1 to 9 through the canonical delimiter and guards against a future no-op replacement.
8. **Reuse:** existing `ScheduleTimeCalculator.nextOccurrence` Java-static API, `EndpointSnapshotCodec`, mapper-produced canonical profile encoding, and `:app:testOssDebugUnitTest` suite.
9. **Retired/decision:** no product behavior retired. The earlier malformed future-schema fixture was ineffective; its failed assertion was a test defect, not a decoder defect. Keep preview/apply/recovery disabled under existing WP08 gates.
10. **Failure behavior:** malformed/truncated endpoints and unsupported schema versions remain non-authorizing; Java compilation must fail visibly if schedule service linkage regresses. Keystore/config data is never cleared by this change.
11. **Tests:** full offline `:app:testOssDebugUnitTest` **PASS**: 24 suites, 130 tests, 1 platform-capability skip, 0 failures/errors. The run compiled app Kotlin, app Java (including `TriggerService.java`), and unit-test Java/Kotlin. Focused `EndpointSnapshotCodecTest` also **PASS**. `git diff --check` **PASS**. JDK 21.0.8 / Gradle 8.13 / SDK 36 task-local override; native rclone checkout/build excluded. APK, lint, instrumentation and GitHub Actions **NOT RUN**.
12. **Acceptance:** this closes two narrow compile/test defects only. WP08, WP09 and WP10 remain **PARTIAL**; native engine integration, single persisted scheduler policy, Samsung actual model/API/firmware, and live Proton remain **NOT RUN**. No release gate is satisfied by JVM tests alone.
13. **Rollback/next:** revert only `70b2375f6d8cce4d65433b9658f7e0a6dcbf1809` if later tests demonstrate a real regression; do not remove the explicit Java import while its call remains. Resume WP08 path-specific backup placement/manifest/restart/restore and WP09 independent Proton closure before full WP10. No pin, remote, PR, APK, signature, or release changed; preserve `.android/`.

## 2026-09-25 - WP13 documentation/source-claim correction slice (PARTIAL)

1. **Objective:** align the current fork's docs with verified source behavior and stop implying untested ABI/device/provider/release support.
2. **Scope:** CloudBridge contributor/user/security/build documentation and one updater comment; no runtime updater behavior or URL changes.
3. **Out of scope:** completing WP13 production delivery, updating the updater owner, publishing a PR/release/APK, changing signing identity, Samsung/Proton testing, or claiming WP12/WP14 acceptance.
4. **Preconditions:** read the complete 2026-09-22 master through Appendix D and the separate WP14 addendum; verify relevant updater, manifest, engine launch, config storage and current docs in source; obtain an independent read-only documentation review. The reviewer made no changes.
5. **Design:** prefer explicit source status, accurate ownership and local/fork-specific links; keep unsupported behaviors marked partial/not run and keep init/apply/recovery unavailable.
6. **Safety invariants:** no unsupported privacy/security promise; no debug APK presented as release; no live-provider/device claim; `.android/` is user-owned and excluded from staging; updater remains notification-only and defaults off.
7. **Implementation:** correct the JNI claim to subprocess execution via `Runtime.exec`; rename “Privacy policy” to “Privacy review status”; fix WP00/WP01 status granularity and sibling-worktree references; qualify ABI/API and serving claims; point SAF docs to the fork; align README/feature matrix and Rareities/rclone links; add updater finding SR-08; correct the worker's stale weekly comment to 14 days. Edited docs include README, REQUIREMENTS_MATRIX, FEATURE_SURVIVAL_MATRIX, EXECUTION_LEDGER, TEST_REPORT, SECURITY_REVIEW, internals/privacy/index/build/security/contribution/audit docs and related status files.
8. **Reuse:** existing source evidence for `Runtime.exec`, manifest export settings, updater preference/work interval/URLs, credential-storage ADR and preview-only Bisync behavior.
9. **Retired/decision:** retired stale claims/links only; did not remove supported user features or retarget the updater prematurely. Master handoff defines WP00-WP15; the addendum's WP00-WP16 phrase is recorded as a numbering inconsistency, not a new package.
10. **Failure behavior:** unsupported or unverified features remain clearly marked; update notifications still target upstream and are explicitly not to be relied on for Rareities releases until the tested WP13 retargeting change.
11. **Tests:** read-only source/doc cross-check completed; stale-pattern `rg` checks show no remaining JNI description, “Privacy policy” nav label, WP00-WP16 package claim outside the explanatory mismatch note, false Tasker claim, unsupported “runs on all ABIs” statement, or upstream SAF link. Every enumerated local Markdown/HTML target exists. `git diff --cached --check` **PASS**; no app/runtime tests were required for prose/comment-only changes. Samsung/Proton/runtime/release **NOT RUN**.
12. **Acceptance:** documentation slice committed locally as `a35e0b323626a3e544986619acd010c3fa079369`; WP13 remains **PARTIAL**, and WP12 feature regressions plus WP14 migration/device/provider acceptance remain open. The optional updater is still misdirected to upstream until a separate tested change.
13. **Rollback/next:** revert only `a35e0b323626a3e544986619acd010c3fa079369` if a verified source fact changes; preserve the prior evidence history. Continue WP08 endpoint-specific placement and restore work in package order. No PR/APK/signing/release was produced.

## 2026-09-25 - WP08 pure backup-placement shape evaluator (PARTIAL)

1. **Objective:** add a read-only policy boundary for evaluating whether two proposed run backup roots have resolved identities, match their protected endpoint accounts, and remain outside both sync roots and each other.
2. **Scope:** CloudBridge source/test commit `2557a835918ad8c3d245d0875abcdcaa6be64561` on `codex/luna-implementation`; adds `BisyncBackupPlacement.kt` and `BisyncBackupPlacementTest.kt`. No schema, config, profile, provider, or native execution behavior changed.
3. **Out of scope:** selecting defaults, generating paths, creating/probing/reserving directories, durable run manifests, restart reconciliation, restore, mutation-boundary checks, enabling Bisync initialization/apply/recovery, device/provider acceptance, PR, APK, signing, or release.
4. **Preconditions:** read the full WP08 master requirements and supplementary addendum. A read-only endpoint audit confirmed placement is not authorized by the current API, SAF/wrapper identities remain unresolved, resource claims are not reservations, and inspection is not recovery. Existing `.android/` remains user-owned and excluded.
5. **Design:** compare credential-free 64-hex account identities and hashed `BisyncEndpointScope` values; require each backup candidate to match its corresponding endpoint identity and not overlap either protected sync scope or the other candidate. Return only a typed issue, never a path.
6. **Safety invariants:** no filesystem/provider/database/native access; unresolved account/scope, identity mismatch, sync-root overlap, candidate overlap, SAF, and wrappers fail closed through unresolved evidence. Even a structurally valid result has `mutationPermitted == false`; no caller is connected to the evaluator.
7. **Implementation:** introduced hash-only backup-candidate evidence, typed structural issues, and a pure pair evaluator. Four focused tests cover a valid-but-nonauthorizing shape, unresolved identity, account mismatch, own and cross-endpoint overlap, backup-pair overlap, and overlapping/unresolved sync roots.
8. **Reuse:** existing `BisyncEndpointEvidence`, `BisyncEndpointScope`, and conservative segment-prefix `overlaps` behavior; no duplicate path normalization or raw path persistence.
9. **Retired/decision:** no existing feature retired. This is shape validation only, not authorization, provider durability, emptiness/collision proof, reservation, or recovery; do not wire it to execution as a green light.
10. **Failure behavior:** every non-resolved or unsafe shape returns a typed fail-closed issue without exposing endpoint data; shape-valid returns `SHAPE_VALID_REQUIRES_DURABLE_RESERVATION`, not an allowed/mutation state.
11. **Tests:** Gradle `:app:compileOssDebugKotlin` completed on JDK 21.0.8 / Gradle 8.13 / SDK 36; the full `:app:testOssDebugUnitTest` invocation did **NOT PASS** because Java compilation failed before JUnit on ZipFS `AccessDeniedException` for Android SDK 35/36 core jars. Independently compiled the focused test source with Kotlin K2 compiler 1.9.22 and ran JUnitCore 4.13.2 against the compiled app Kotlin classes: **4 tests PASS**. The earlier 130-test Gradle pass applies to source commit `70b2375`, not this change. Native checkout/build, instrumentation, Galaxy S26 and Proton remain **NOT RUN**. Staged `git diff --cached --check` passed.
12. **Acceptance:** this closes only the pure structural-policy slice. WP08 remains **PARTIAL / BLOCKING**: unique fresh root reservation, durable run-owned manifest, restart reconciliation, exact restore, provider write/read proof and mutation-boundary revalidation remain absent. Initialization/apply/recovery stays unavailable.
13. **Rollback/next:** revert only `2557a835918ad8c3d245d0875abcdcaa6be64561` if review exposes a policy defect. Next implement a durable run-owned reservation/manifest without weakening the hard false mutation permission; resolve deterministic SDK Java test execution and complete native fault/restart/restore evidence. No pin, remote, PR, APK, signature, or release changed; preserve `.android/`.

## 2026-09-25 - WP09 Proton API Bridge upload-worker cancellation candidate (PARTIAL)

1. **Objective:** prevent Proton block-upload workers from leaking or continuing sibling uploads after semaphore cancellation or the first failed block.
2. **Scope:** isolated local `github.com/rclone/Proton-API-Bridge` v1.0.5 candidate, base commit `9d772d08d663a66cedbcbef759dd764cce7c8def`; source/test files `file_upload.go` and `file_upload_cancel_test.go` in task worktree `Proton-API-Bridge-wp09-cancel`.
3. **Out of scope:** changing Rareities/rclone or CloudBridge dependency pins, publishing a library revision, app integration, live Proton operations, device acceptance, PR/APK/signing/release.
4. **Preconditions:** WP01 standalone baseline; v1.0.5 source was materialized from the local module cache, whose ZIP hash matches the v1.0.5 checksum recorded in Rareities/rclone `go.sum`; dependencies and tests ran offline.
5. **Design:** fan out block uploads with one buffered result per worker, first-error cancellation, a post-acquire cancellation check, exact permit release for successful acquisitions, and a wait until every worker exits.
6. **Safety invariants:** failed acquisition never releases a permit or starts upload; acquired permits release exactly once; first upload error cancels siblings before release; the caller does not return while workers remain active; local tests touch no credentials or remote data.
7. **Implementation:** `uploadAndCollectBlockData` now uses `uploadBlocks`. Deterministic tests cover cancellation arriving during a real weighted-semaphore wait, a permit made available after sibling cancellation, and an in-flight sibling upload that must exit before the batch returns.
8. **Reuse:** existing weighted semaphore and block-upload callback; no protocol/dependency API or persisted state changed.
9. **Retired/decision:** replaced fail-fast behavior that did not guarantee sibling cancellation/joining. Independent review found no code correctness issue, but identified missing tests for cancellation during a real wait and an active sibling; those tests were added and rerun. Candidate remains unpromoted: its partial/promisor worktree index is unusable and a Git object is missing; do not stage deletion/re-add churn or infer a reproducible commit.
10. **Failure behavior:** acquisition failure returns without release/upload; failed upload cancels the shared context; a sibling acquire that succeeds concurrently observes cancellation before upload; all workers are drained/joined before returning the first error.
11. **Tests:** Go 1.26.8, `GOPROXY=off`, `GOSUMDB=off`, `GOWORK=off`, `CGO_ENABLED=0`, task-local module/build/test caches; Proton test credentials unset in the test subprocess. `go test -mod=readonly -count=100 -run '^TestUploadBlocks' .` **PASS**; `go vet -mod=readonly .` **PASS**; full `go test -mod=readonly -count=1 ./...` **PASS**, with credential-dependent Proton integration tests skipped; scoped `go fmt file_upload.go file_upload_cancel_test.go` **PASS**. Broad `go fmt ./...` **FAILED** with Access Denied opening existing `constants.go`; this is not a formatter pass. Race test **NOT RUN** (CGO disabled/no GCC); live Proton **NOT RUN**.
12. **Acceptance:** upload-worker cancellation/join behavior is tested locally, but WP09 remains **PARTIAL**. Candidate has no reproducible source commit, integrated rclone tests for this patch are not established, and live disposable-vault/official-client interoperability remains **NOT RUN**. No dependency pin changed.
13. **Rollback/next:** retain only as a local candidate; rework if review finds a concurrency defect. Re-establish a complete clean v1.0.5 Git checkout, apply the minimal reviewed patch, rerun library and exact-rclone integration suites, then select only an immutable reviewed dependency revision. Continue WP08 as the active safety blocker; no app mutation path is enabled.

## 2026-09-25 - GitHub repository/PR/CI refresh

- Read-only refresh reconfirmed `Rareities/CloudBridge` master at `c492876258ca841232229249519abe92ff77c3a4` and `Rareities/rclone` master at `1583cce1e28340e5d064ed955179f5f2b31e7757`. No open PRs exist in either repository.
- CloudBridge master, rclone master, and app-pinned rclone commit `fe775a8b58cf217fdf4bd34f0975af1e4c19c1a0` report no combined status checks and no PR-triggered workflow runs through the available GitHub connector.
- Rareities/rclone PR #1 (`bisync/proton: harden state handling and add preview protocol`) is closed unmerged; its selected base would include 180 unrelated commits. Do not reopen or replace it without first establishing a narrow reviewable base/diff. No push, PR, CI dispatch, or release mutation was performed.

## 2026-09-25 - WP12 VCP child-name validation slice (PARTIAL)

1. **Objective:** constrain create/rename/copy/move target child names to one safe document component without silently changing valid literal backslash or Unicode names.
2. **Scope:** CloudBridge source/test commit `29504e3` on `codex/luna-implementation`, covering `DocumentChildNamePolicy.java`, `VirtualContentProvider.java`, and both focused test classes; no database, engine, permission grant, provider credential, or remote data changes.
3. **Out of scope:** full WP12 feature-survival matrix, caller/grant authorization, canonical validation of every parent document-ID segment, SAF cross-app/device acceptance, provider mutation tests, WP11 UI completion, release/PR/APK.
4. **Preconditions:** read the master WP12 and current `AGENTS.md`; source review confirmed create already maps `/` to `_`, while rename previously appended caller text directly to the target document ID. Independent review found a rename collision ambiguity and duplicate test declaration; both were addressed.
5. **Design:** preserve create's established slash-to-underscore normalization; require a single component for rename and copy/move target composition; reject null, empty, exact `.`/`..`, `/` in strict target names, and NUL; preserve literal backslash and Unicode.
6. **Safety invariants:** name validation runs before directory creation, rename RCD acquisition, and copy/move source lookup; invalid public inputs become `FileNotFoundException` with a non-sensitive validation cause; target composition appends to but does not rewrite its supplied parent ID.
7. **Implementation:** introduced a pure policy class used by `VirtualContentProvider`; rename rejects `/` rather than flattening it, avoiding an unverified existing-destination collision; copy/move now compute and validate the target ID before `getFileItem`; added policy and public-boundary regression sources.
8. **Reuse:** existing document-ID target helpers, `getChildName`, and the established create slash-normalization behavior.
9. **Retired/decision:** no feature removed. Literal backslashes remain supported as names because `/` is the document-ID path separator on Android; do not assume Windows separator semantics. Create keeps compatibility mapping; rename does not silently rename a path-like string to a potentially conflicting sibling.
10. **Failure behavior:** invalid user names return `FileNotFoundException` before remote calls; unsafe source leaf names fail before copy/move source lookup. Pure helper rejects invalid components via `IllegalArgumentException` for caller translation.
11. **Tests:** pure policy class and focused JUnit test compiled with JDK 21.0.8; JUnitCore 4.13.2 reports **5 PASS**. `javac` printed a ZipFS `AccessDeniedException` while closing the JUnit archive after emitting classes; JUnit ran the emitted classes successfully. Targeted Gradle `:app:testOssDebugUnitTest --tests ca.pkay.rcloneexplorer.VirtualContentProviderTest` **BLOCKED/NOT PASSED**: Kotlin compilation completed, Java compilation failed on cached `viewbinding-8.13.2-api.jar` Access Denied plus dependent missing-symbol diagnostics before tests. `git diff --check` **PASS**. The prior 130-test result is from an older source commit; it is not applied here. Samsung/Proton/SAF external-client tests **NOT RUN**.
12. **Acceptance:** this is a bounded WP12 input-validation slice only. WP12 remains **PARTIAL**; full feature regressions, collision semantics, caller/grant scope, canonical parent/document-ID validation, and the app's public provider test methods remain unverified. WP11 remains gated on stable WP07-WP10 interfaces.
13. **Rollback/next:** revert only source commit `29504e3` if later executable tests reveal a regression; retain the failed Gradle evidence. Next obtain a clean app JVM run, then continue the WP12 survival matrix without claiming SAF security acceptance. Preserve `.android/`; no engine pin, PR, APK, signing, or release changed.

## 2026-09-25 - WP13 fork-owned updater identity and bounded release scan (PARTIAL)

1. **Objective:** make optional update notifications and project links follow the Rareities/CloudBridge fork while keeping version/channel selection deterministic and notification-only.
2. **Scope:** CloudBridge local source/documentation commit `d715e16` on `codex/luna-implementation`; central release policy, bounded release pagination, malformed-entry handling, About repository link, localized labels, and corresponding user/security/readiness documentation. Engine pin, database, and provider data are unchanged.
3. **Out of scope:** completing WP13 production delivery, automatic download/install, creating a public release, verifying a production signer, integrated HTTP/notification/device tests, PR, APK, signing, or release.
4. **Preconditions:** WP13 source/docs review confirmed the optional checker defaults off, runs on the existing 14-day connected-network schedule, and had upstream update URLs. The 2026-09-25 GitHub refresh found no verified Rareities/CloudBridge release candidate. Core package acceptance remains incomplete, so this is a bounded fix and not WP13 completion.
5. **Design:** centralize Rareities/CloudBridge release identity; parse strict SemVer; require GitHub prerelease metadata to agree with the tag; keep stable installs on stable releases; page through at most five pages of 100 and preserve current preference state if the result is malformed, fails, or remains truncated.
6. **Safety invariants:** preference remains opt-in/default-off; notifications only, no installer path; incomplete/failed scans do not overwrite saved update state; no debug signer, production secrets, user data, or native pin changes; `.android/` excluded from staging.
7. **Implementation:** `UpdateReleasePolicy` owns fork URLs and SemVer ordering; `UpdateWorker` skips malformed release items and fails closed on HTTP/JSON errors and a full fifth page; `AppUpdateNotification` uses the Rareities latest-release link; About's dead bug-report action now points to the fork repository, with labels localized in English, German, Catalan, and Simplified Chinese. Docs state the updater/release limitations.
8. **Reuse:** existing preference, WorkManager schedule, notification channel, ignored-version behavior, and notification UI; no replacement update framework or install permission added.
9. **Retired/decision:** removed obsolete upstream release destinations and the dead issue-tracker action while retaining original-maintainer attribution. No fork release is assumed to exist; the updater remains partial until a real release and integrated behavior are verified.
10. **Failure behavior:** HTTP error, empty/malformed response, malformed JSON, or page-cap exhaustion leaves saved update state unchanged; malformed list members are skipped. A complete valid list with no eligible newer release records the installed version as before.
11. **Tests:** the pure JDK 21.0.8/JUnitCore 4.13.2 URL/SemVer suite reports **9 PASS** on the unchanged policy/test snapshot; XML parsing for default/DE/CA/ZH strings and `content_about.xml`, fork-link/stale-upstream scans, and `git diff --check` passed. `:app:compileOssDebugKotlin` passed on JDK 21.0.8 / Gradle 8.13 / SDK 36 with native/rclone Java tasks excluded. The focused Gradle unit task was **BLOCKED/NOT PASSED** before JUnit at Java compilation with cached `viewbinding-8.13.2-api.jar` `AccessDeniedException`. A fresh standalone re-run for this checkpoint also failed before JUnit because javac could not write `UpdateReleasePolicy$ReleaseCandidate.class`; this is recorded as blocked, not a pass. Worker HTTP/preference/notification-intent tests, current full Android Java/JUnit, device behavior, Samsung actual API/firmware, and live Proton remain **NOT RUN**.
12. **Acceptance:** fork URLs, strict release selection, bounded pagination, and About destination are implemented locally, but WP13 remains **PARTIAL / BLOCKING**. Integrated worker behavior, opt-out/managed-store paths, an actual fork release, CI, ABI/native artifact verification, dependency/security review, signer continuity, provenance, Galaxy, and Proton acceptance remain open.
13. **Rollback/next:** revert only source commit `d715e16` if a later integrated review reveals regression; docs/evidence are tracked separately. Continue active WP08 fresh endpoint-specific backup reservation, durable run-owned manifest, restart reconciliation, exact restore, and mutation-boundary gates. No pin/remote/PR/APK/signing/release changed; keep init/apply/recovery disabled and preserve `.android/`.

## 2026-09-25 - WP13 selected-release notification routing (PARTIAL)

1. **Objective:** ensure a notification for a selected prerelease or stable release opens that exact tag, not GitHub's unrelated `/releases/latest` stable page.
2. **Scope:** CloudBridge source commit `d9e130ac716b9d8a26486a4a6b8c2284848c84be`; validated release-tag URL policy, notification content/action intents, regression-test source, and status documentation. No updater scheduling, selection policy, engine pin, database, or provider state changed.
3. **Out of scope:** HTTP paging/worker integration, installed-channel flows, device behavior, APK packaging, CI, signing, PR, or publication.
4. **Preconditions:** Rareities/CloudBridge updater identity and strict SemVer parser are in `d715e16`; GitHub refresh found no verified fork release. The selected version value is checked by the existing SemVer parser before inclusion in a URL path.
5. **Design:** route valid selected tags to `/releases/tag/<tag>` and invalid persisted values to the safe `/releases` index. Use the same target for notification body tap and the explicit view action.
6. **Safety invariants:** do not construct a path from arbitrary stored text; invalid values cannot escape the GitHub release index; notifications remain informational and do not download/install APKs.
7. **Implementation:** `UpdateReleasePolicy.releasePageUrl` centralizes safe routing; `AppUpdateNotification` receives the selected version and applies one exact URL to both PendingIntents. Regression source covers stable, prerelease/build metadata, traversal-like input, and empty input.
8. **Reuse:** existing SemVer parser, notification channel, browser intent, and ignore-version action.
9. **Retired/decision:** removed the misleading always-latest notification route; no update installation behavior was added.
10. **Failure behavior:** malformed stored versions fall back to the release index; valid selected versions retain their exact tag page.
11. **Tests:** `:app:mergeOssDebugResources` and `:app:compileOssDebugKotlin` were attempted offline on Temurin JDK 21.0.8 / Gradle 8.13 / SDK 36; resource merge **PASS**, Kotlin compilation **BLOCKED/NOT PASSED** while Gradle could not create a transformed cached dependency directory. Focused JUnit/standalone reruns remain **BLOCKED/NOT PASSED** before JUnit on cached dependency/Javac access errors. New selected-tag test is authored but has **NOT RUN**. Samsung, Proton and full Android integration remain **NOT RUN**.
12. **Acceptance:** exact-tag routing is implemented locally, but WP13 remains partial; test execution, HTTP/notification integration, a real fork release, signer/artifact provenance, CI and acceptance-device gates remain open.
13. **Rollback/next:** revert only `d9e130ac716b9d8a26486a4a6b8c2284848c84be` if integrated intent testing reveals a regression. Keep the Rareities repository route and safe index fallback; no PR/APK/signing/release changed.

## 2026-09-25 - WP09 clean Proton API Bridge source recheck (PARTIAL)

1. **Objective:** replace the earlier unpromotable partial/promisor candidate with a reproducible local checkout of the exact Proton API Bridge v1.0.5 source recorded by the rclone dependency checksum.
2. **Scope:** isolated local repository `work/Proton-API-Bridge-wp09-clean`; base commit `259c687ee5bfaa08c38c42217d9d1e96bec37650`, implementation commit `646039439e70e47bd7566feae8ceedd636a37afd`, evidence HEAD `bad819c9f778114be7b3376b3e271dc9d4f29b92`. No Rareities/rclone or CloudBridge pin changed.
3. **Out of scope:** upstream publication, dependency promotion, exact-pin inclusion, CloudBridge integration, live Proton operations, Galaxy acceptance, PR/APK/signing/release.
4. **Preconditions:** materialized the v1.0.5 module from the local Go module cache with network disabled; module ZIP checksum `h1:K1++Qtk3PvgkiCCiv6Pahju1TMOzKY6VSwiwT7XLAVc=` and go.mod checksum `h1:vCeOPhlXzevN0AFojgh1zsjhetiShy/ArvJ/xkFUDWk=` match `work/rclone/go.sum`; cached tag metadata names source commit `9d772d08d663a66cedbcbef759dd764cce7c8def`.
5. **Design:** correct the upload fan-out lifecycle at the responsible library layer, preserving the first error while canceling siblings, rechecking cancellation after semaphore acquisition, releasing only acquired permits, and joining all workers before returning.
6. **Safety invariants:** no network/provider or credential access; no release or pin change; acquisition failure cannot release a permit or start a block; every acquired permit is released once; failed sibling work is canceled and joined.
7. **Implementation:** `file_upload.go` and deterministic `file_upload_cancel_test.go` implement/test the worker lifecycle. The clean source checkout has no configured remote and is separate from the damaged earlier promisor candidate.
8. **Reuse:** v1.0.5 API and existing weighted semaphore/upload callback; no protocol or persisted-state format changed.
9. **Retired/decision:** the old “candidate has no reproducible source commit” status is superseded for this new clean local checkout only. It remains unpublished and unselected; exact rclone app-pin tests do not include this patch.
10. **Failure behavior:** first worker error cancels the shared context, a concurrent successful acquire checks cancellation before upload, result collection drains all worker outcomes, and return waits for every worker.
11. **Tests:** Go 1.26.8 offline (`GOPROXY=off`, `GOSUMDB=off`, `GOWORK=off`, `CGO_ENABLED=0`, credentials unset): `go test -run '^TestUploadBlocks' -count=100 -timeout=3m .` **PASS**; full `go test -timeout=10m ./...` **PASS** with provider credential tests skipped; `go vet ./...` and gofmt/diff checks **PASS**. Race test and live Proton **NOT RUN**.
12. **Acceptance:** repeatable source and local library tests establish a candidate for review, not WP09 completion. App-pin compatibility, full rclone integration against the changed dependency, multi-account/session/revision behavior, live disposable-vault interoperability and device acceptance remain open.
13. **Rollback/next:** keep the candidate isolated; do not update go.mod/go.sum or the app pin until independent review, exact rclone integration tests, and immutable upstream provenance are established. Samsung and Proton access remain **NOT RUN**.

## 2026-09-25 - WP12 Simplified Chinese sync notification format (PARTIAL)

1. **Objective:** make the Simplified Chinese three-value progress string use the same deterministic positional-format contract as the default locale.
2. **Scope:** CloudBridge source commit `21eb807`; one resource string in `app/src/main/res/values-zh-rCN/strings.xml`.
3. **Out of scope:** full locale/accessibility review, all-feature survival regressions, VCP authorization, device acceptance, or WP12 completion.
4. **Preconditions:** resource-merger warning showed the Chinese translation used three unindexed `%s` placeholders while the default locale and call site format three ordered strings.
5. **Design:** assign `%1$s`, `%2$s`, `%3$s` to the existing Chinese phrase without changing wording or argument order.
6. **Safety invariants:** presentation-only; no task, transfer, persisted, network, or provider behavior changed.
7. **Implementation:** changed `sync_notification_short` to positional placeholders.
8. **Reuse:** existing `StatusObject` three-argument formatting contract and default resource format.
9. **Retired/decision:** removed ambiguous locale-specific placeholder ordering; no feature removed.
10. **Failure behavior:** preserves all three values in deterministic positions for locale formatting.
11. **Tests:** offline JDK 21.0.8 / Gradle 8.13 / Android SDK 36 `:app:mergeOssDebugResources` **PASS**; the prior locale format warning no longer appeared. `:app:compileOssDebugKotlin` **BLOCKED/NOT PASSED** when Gradle could not create a transformed dependency-cache directory; no JUnit test was needed for this string-only change. `git diff --check` **PASS**. Device/font-scale/language-switch validation **NOT RUN**.
12. **Acceptance:** this closes one resource-format defect only. WP12 remains partial; localization, accessibility and the full feature-survival matrix remain unaccepted.
13. **Rollback/next:** revert only `21eb807` if locale QA identifies a wording/order regression; rerun resource merge after other localization changes. No APK or device acceptance is claimed.

## 2026-09-25 - WP08 paired backup-manifest persistence foundation (PARTIAL)

1. **Objective:** persist a paired, run-owned record of proposed Bisync backup locations, bound to current profile/preflight/preview evidence, while keeping native mutation unavailable.
2. **Scope:** CloudBridge source commit `2c302158d131f566724aa1814d2ee8b9e1440b02` plus instrumentation-test follow-up `d38f6a3` on `codex/luna-implementation`; schema v16, paired manifest/location records, observation fingerprint, repositories and tests. The Kotlin production compile snapshot includes WP12/WP13 source through `193d972839679c66a667e8ded4e480b0c2a35ed5`.
3. **Out of scope:** provider path creation/probing/reservation, byte-copy verification, restore, restart reconciliation, UI-confirmation proof, mandatory pre-mutation integration, enabling initialization/apply/recovery, Galaxy/Proton acceptance, PR/APK/signing/release.
4. **Preconditions:** master WP08 and current code/ledger reviewed; CloudBridge already has durable run, preflight and preview identities; app remains pinned to immutable rclone `fe775a8b58cf217fdf4bd34f0975af1e4c19c1a0`; prior source was `21eb807`.
5. **Design:** atomically persist two hashed-account/scope locators with run/profile/engine/preflight/preview/owner snapshots; reject stale or mismatched evidence and overlaps with persisted backup reservations and new sync roots; allow creation only while run is in `PREFLIGHT`.
6. **Safety invariants:** `mutationPermitted` is hard-coded false; there is no production caller; no provider or native operation is performed; caller-supplied `userConfirmedAt` is ordering metadata, not proof that a UI displayed or received explicit confirmation; initialization/apply/recovery remain unavailable.
7. **Implementation:** added SQLite v16 additive migration, foreign-key enforcement, paired manifest/location tables and indexes, transactional `createPending`, integrity-checked readback, unresolved-profile lookup, safety observation hashing, and regressions for migration/pair ownership/staleness/overlap/cancellation/running-owner rejection.
8. **Reuse:** existing `RunRepository`, preflight/preview repositories, immutable profile/run snapshots and `BisyncBackupPlacementPolicy`; no provider path semantics were invented and no rclone code/pin changed.
9. **Retired/decision:** independent read-only review found the first draft accepted `RUNNING`, which lacked a barrier proving native mutation had not begun. Source now rejects every non-`PREFLIGHT` run in both preparation and transactional owner checks; test `d38f6a3` changes the persisted run row to `RUNNING` while retaining the prior `PREFLIGHT` snapshot. The reviewer confirmed this exercises the transactional mismatch path. This is a local repository guard only, not proof every mutator requires a reservation.
10. **Failure behavior:** stale owner/profile/preflight/preview, unresolved scope, overlap, invalid state or DB integrity failure rejects without returning mutation permission; no filesystem/provider data is changed.
11. **Tests:** Temurin JDK 21.0.8 / Gradle 8.13 / Android SDK 36 / offline task-local homes: `:app:compileOssDebugKotlin` **PASS** on production source content through `193d972`. Focused `:app:testOssDebugUnitTest` and `:app:compileOssDebugAndroidTestKotlin` are **BLOCKED / NOT PASSED before JUnit/test compilation**: main javac reports `AccessDeniedException` for cached transformed `viewbinding-8.13.2-api.jar`, then dependent missing-symbol diagnostics. Migration/repository Android instrumentation sources are authored but **NOT RUN**; SQLite runtime/migration behavior is therefore unverified. `git diff --check` **PASS**.
12. **Acceptance:** partial WP08 foundation only. Provider-backed durable reservation, mandatory pre-mutation gate, exact restore, crash/restart reconciliation, UI-confirmation evidence, last-moment revalidation and fault/device/provider matrices remain release blockers.
13. **Rollback/next:** revert only `2c302158d131f566724aa1814d2ee8b9e1440b02` if regression is demonstrated; do not infer reservation from this database proposal. Re-review the PREFLIGHT-only guard, add provider-specific proof and recovery, and connect a mandatory pre-mutation barrier only after all safety tests. No PR/APK/signature/release.

## 2026-09-25 - WP12 canonical VCP document-ID validation (PARTIAL)

1. **Objective:** prevent malformed or path-prefix-confused Android document IDs from reaching VCP query/open/mutation paths; verify parent-child and remove-parent identity at the owning provider boundary.
2. **Scope:** CloudBridge source commit `af638f33c5173ea8541a340a01cf4aa5e4a3dacc`; `VirtualContentProvider.java`, new pure `DocumentIdPolicy.java`, focused unit and public-provider tests.
3. **Out of scope:** SAF caller/grant authorization, cross-app/device behavior, complete VCP feature-survival or collision audit, WP11 UI, WP12 package acceptance, PR/APK/release.
4. **Preconditions:** master WP12 and `AGENTS.md` reviewed; previous WP12 child-name policy exists; Android supplies Root ID `rclone` to recent-document queries while the provider document root is `rclone/remotes`.
5. **Design:** validate complete rooted or legacy-short IDs, canonicalize structural separators without altering literal backslash/Unicode path characters, enforce component-aware descendants, and require supplied removal/move parents to match the document ID.
6. **Safety invariants:** invalid IDs fail closed before path lookup/delegation; provider root and remote roots cannot be treated as file targets; malformed parent IDs cannot expand containment; no authority is inferred from syntactic validity.
7. **Implementation:** validate public query/open/mutation boundaries, root ID for recents/search, web-link IDs before superclass delegation, configured remote identity, move source parent, and remove parent before deletion. Add pure and public-boundary regressions.
8. **Reuse:** existing provider root/remote ID scheme, target-name validation and `FileNotFoundException` failure contract; no remote path is rewritten beyond established ID normalization.
9. **Retired/decision:** replaced raw `startsWith` child checks and unvalidated TODO superclass forwarding. Independent re-review found no new defect in the corrected recent-root contract or updater state-clear fix; valid recent-root superclass behavior remains untested.
10. **Failure behavior:** malformed/unconfigured document inputs reject before provider mutation; mismatched remove/move parent is rejected before delete/move is issued.
11. **Tests:** standalone JDK 21.0.8/JUnitCore 4.13.2 `DocumentIdPolicyTest`: **4 PASS**. Javac printed ZipFS `AccessDeniedException` while closing the cached Hamcrest archive after writing classes; JUnitCore still ran and reported 4/4. Current full Gradle focused test task is **BLOCKED / NOT PASSED** during Java compilation at transformed `viewbinding-8.13.2-api.jar` before JUnit; public VCP tests did not execute. Kotlin production compilation does not compile the changed Java VCP class. `git diff --check` **PASS**.
12. **Acceptance:** a bounded input/containment slice only. Caller grants, cross-app URI behavior, valid callback integration, provider capabilities, file-operation races, SAF/device tests and all other WP12 survival rows remain open.
13. **Rollback/next:** revert only `af638f33c5173ea8541a340a01cf4aa5e4a3dacc` if provider integration tests expose a regression; keep API/device authorization separate from syntactic validation and re-run Java/JUnit and SAF-client tests when the toolchain permits.

## 2026-09-25 - WP13 bounded release scanner and request cancellation (PARTIAL)

1. **Objective:** bound updater resource use and ensure cancellation stops in-flight HTTP requests without corrupting previously stored update state.
2. **Scope:** CloudBridge source commit `193d972839679c66a667e8ded4e480b0c2a35ed5`; `UpdateWorker.kt`, new `UpdateReleaseScanner.kt`, and scanner test sources.
3. **Out of scope:** automatic download/install, changing updater opt-in defaults, proving a Rareities release exists, complete Android HTTP/preferences/notification tests, production signer, CI, PR/APK/release.
4. **Preconditions:** existing notification-only Rareities updater and strict SemVer/channel policy; prior updater commits `d715e16` and `d9e130a`; local Gradle/SDK/JDK toolchains.
5. **Design:** cap scan to five pages of 100 entries, HTTP response to 512 KiB and changelog to 16 KiB; bridge OkHttp enqueue through cancellable coroutine continuation, apply a 30-second call timeout, and change preferences only after a complete scan.
6. **Safety invariants:** coroutine cancellation cancels the `Call`; oversized/malformed/incomplete scans do not overwrite prior state; no release channel is silently broadened; updater remains informational and never installs an APK.
7. **Implementation:** added suspendable page scan seam, bounded body streaming, response/page/schema validation, atomic version+changelog writes and selected-tag notification behavior; a complete scan with no candidate clears stale changelog together with current version.
8. **Reuse:** existing OkHttp, WorkManager retry/result policy, shared preferences, release URL/SemVer policy and notification channel.
9. **Retired/decision:** removed unbounded `ResponseBody.string()` flow and completion-hook waiting that delayed cancellation. Read-only reviewer confirmed request cancellation, body cap, selected recent root and atomic no-candidate clearing by source inspection; runtime remains unverified.
10. **Failure behavior:** retryable network/HTTP failures preserve prior state; malformed or over-limit scans are incomplete and do not apply candidates; cancellation propagates instead of becoming success.
11. **Tests:** `:app:compileOssDebugKotlin` **PASS** on exact source commit `193d972`; scanner page/error/bounds/cancellation unit tests are authored but **NOT RUN**. Focused app JVM task **BLOCKED / NOT PASSED before JUnit** at cached transformed `viewbinding-8.13.2-api.jar`. Earlier 9-test pure URL/SemVer evidence covers the prior policy snapshot only. No Android worker/preferences/notification/HTTP runtime test. `git diff --check` **PASS**.
12. **Acceptance:** partial WP13 implementation. Scanner/worker behavior, installed channel opt-out/managed-store paths, exact notification intents, release availability, signer/provenance, CI and device distribution remain unaccepted.
13. **Rollback/next:** revert only `193d972839679c66a667e8ded4e480b0c2a35ed5` if integrated tests identify a defect; run scanner and worker tests against reliable Java test compilation before distribution. No PR/APK/signature/release.

## Documentation scope reconciliation (2026-09-25)

- The governing master attachment is the complete WP00-WP15 plan. The latest attached addendum (`bde104c5-967a-4fea-8e29-1135e79d00f5`) is only 96 lines, ends at an empty “Existing code to reuse” heading, claims WP00-WP16, and calls DB v8 current. Those statements conflict with the master and current schema v16; the attachment was not edited, and the master controls. Treat v8 as a legacy fixture only where supported.
- `AGENTS.md` was corrected to follow the master’s owning-layer rule: Android orchestration defects belong in CloudBridge; backend/protocol/state/library-concurrency defects belong in Rareities/rclone or their responsible dependency. Independently validate lower-layer fixes before app integration.
- WP numbering and addendum conflict are recorded in `REQUIREMENTS_MATRIX.md`; no WP16 was created. Samsung/Proton and Android instrumentation remain **NOT RUN**.

## 2026-09-25 - WP09 exact app-pin integration evidence (PARTIAL)

1. **Objective:** verify the isolated Proton API Bridge cancellation candidate against the exact immutable rclone revision configured by CloudBridge, without changing that pin or contacting Proton.
2. **Scope:** app-pin worktree `work/rclone-app-pin-wp08` at `fe775a8b58cf217fdf4bd34f0975af1e4c19c1a0` and clean Proton API Bridge candidate at evidence HEAD `bad819c9f778114be7b3376b3e271dc9d4f29b92` (implementation commit `646039439e70e47bd7566feae8ceedd636a37afd`, source tag provenance `9d772d08d663a66cedbcbef759dd764cce7c8def`).
3. **Out of scope:** changing `go.mod`/`go.sum`, pin promotion, app/native ABI integration, upstream publication, full provider acceptance, live Proton, PR/APK/signing/release.
4. **Preconditions:** master WP09 reviewed; clean candidate provenance and module hashes checked against rclone `go.sum`; CloudBridge app pin verified clean before and after; tests used an isolated temporary Go workspace.
5. **Design:** resolve the bridge module to the candidate with `go list -m`; run only ProtonDrive package tests and vet from the immutable app-pin checkout using `-mod=readonly`.
6. **Safety invariants:** `GOTOOLCHAIN=local`, `CGO_ENABLED=0`; `PROTON*` and `RCLONE_CONFIG*` environment variables removed; no remote/provider test data or credentials; Go checksums retained; no repository source/pin/remote changed.
7. **Implementation:** no application or engine source changes. The test workspace selected the candidate only for this process; dependency downloads were from the public Go module proxy and verified against recorded module checksums.
8. **Reuse:** exact CloudBridge engine ref, candidate module, current `backend/protondrive` tests, Go module verification, and read-only test/vet commands.
9. **Retired/decision:** resolved only the earlier open question of package-level app-pin compatibility. Do not infer whole-rclone compatibility, candidate publication approval, or live Proton behavior from this result.
10. **Failure behavior:** package failures would stop before any provider operation; workspace isolation prevents silent dependency-manifest/pin changes.
11. **Tests:** Go 1.26.8 Windows/amd64; module resolution printed `github.com/rclone/Proton-API-Bridge => .../Proton-API-Bridge-wp09-clean`; `go test -mod=readonly -count=1 ./backend/protondrive` **PASS** (0.727s); `go vet -mod=readonly ./backend/protondrive` **PASS**. Both repos remained clean. This turn fetched public build modules only; no live network/provider API was contacted.
12. **Acceptance:** WP09 remains **PARTIAL**. Independent candidate focused repeated tests, full library suite and vet passed previously; exact app-pin ProtonDrive package integration and vet now pass. Full rclone/app coverage, race, promotion review, official-client/disposable-vault interoperability and live Proton remain open or **NOT RUN**.
13. **Rollback/next:** no source/pin rollback required. Keep candidate local/unpromoted; obtain independent publication review and complete safe disposable-vault acceptance when Proton access is explicitly available. Keep Galaxy and live Proton acceptance **NOT RUN**.

## 2026-09-25 - WP13 current-source policy and Android test retry

- Re-audited current updater source instead of replaying an older read-only report: `UpdateReleasePolicy` bounds page URLs, includes a test for releases beyond the original first ten, rejects surrounding version whitespace, and tests prerelease ordering/build metadata. The older first-page/whitespace findings are already addressed. Standalone JDK 21.0.8/JUnitCore 4.13.2 execution reported **10/10 PASS**; javac emitted a ZipFS close warning for the cached JUnit archive but exited 0 and JUnit executed successfully.
- A fresh Gradle 8.13 home retry of `:app:testOssDebugUnitTest` remained **BLOCKED / NOT PASSED before JUnit**: javac reported `AccessDeniedException` closing Android SDK platform archives and transformed `viewpager-1.0.0-api.jar`, then dependent missing-symbol diagnostics. This does not establish source errors. No test method ran; host `.android/` and SDK contents were left unmodified.
- WP13 scanner/worker tests, notification/preferences integration, and device behavior remain **NOT RUN**. Current Android-source status is unchanged; updater remains optional, notification-only, and WP13 partial.

## WP11 current-source audit checkpoint

- Independent read-only audit at CloudBridge HEAD `39a54173d5c05cfb7dc2a6e6851472cba82733e9` confirmed no app implementation of Obsidian linking, foreground detection, deferred scheduling, debounce, vault fingerprinting, or sync-before-open; the only source reference is a test fixture path. The user guide correctly warns not to rely on the workflow.
- Current Bisync screen is explicitly preview-only; it does not present preview completion as an applied sync and it says endpoint contents may have changed. The regular worker rejects unsupported Bisync directions. The WP08 manifest remains DB-only, has no production caller, and never grants mutation permission.
- Do not add Apply or Obsidian launch until WP08 has mandatory verified mutation/restore and WP10 has durable deferral/cancellation. WP11 remains **NOT COMPLETE**; preserve a manual no-Usage-Access route and unknown foreground must remain deferred in any future workflow. No tests/device/provider operations were run by the reviewer.

## 2026-09-25 - WP10 scheduled-trigger admission and copy safety (PARTIAL)

1. **Objective:** prevent current scheduled work from running after a trigger is disabled, deleted, edited or no longer eligible; serialize current-process schedule changes against native admission; avoid corrupting the source row when copying a trigger.
2. **Scope:** CloudBridge source commit `e6097f1dc4f7fba87440359039c84196d250c922` on `codex/luna-implementation`; trigger database writes/import reconciliation, AlarmManager delivery, WorkManager snapshot/follow-up and native-launch gate; adjacent WP12 duplicate-trigger identity/persistence correction.
3. **Out of scope:** schema-backed trigger-generation migration, unambiguous migration of legacy WorkManager requests, durable single-dispatcher/coalescing/missed-run policy, tested boot/time-change/force-stop delivery semantics, cross-process guarantees, Obsidian flow, Galaxy/Proton acceptance, PR/APK/signing/release.
4. **Preconditions:** re-read master WP10/12 and current source; app remains pinned to rclone `fe775a8b58cf217fdf4bd34f0975af1e4c19c1a0`; independent code review found the delivered-alarm stale-config race and duplicate-insert failure; only explicit app paths were staged.
5. **Design:** carry target/type/time/weekdays in alarm intents; compare complete snapshot to current persisted row before queueing; carry persisted trigger metadata through WorkManager follow-ups; re-read and gate enablement/config/day before native launch under a shared in-process trigger-state monitor.
6. **Safety invariants:** stale/missing alarm snapshots fail closed and reconcile current alarm; deleted/disabled/stale newly tagged scheduled work must not start a native child; manual requests remain distinct in new payloads; trigger-copy persistence failure must not enqueue/display an unsaved row; cancellation propagates rather than becoming success.
7. **Implementation:** synchronized `DatabaseHandler` create/update/delete/replace operations; importer alarm reconciliation; current-row checks in trigger service; disabled interval cancellation and long interval arithmetic; alarm snapshot validation; worker current-state checks and metadata propagation to children; `CancellationException` propagation in guardian worker; fresh unsaved trigger copy plus insert-result check and localized error. Source review confirmed existing `BootReceiver` requeues all persisted trigger types on `BOOT_COMPLETED`; after boot an interval is anchored at current time plus its configured period. This behavior was not instrumented/device-tested.
8. **Reuse:** existing trigger rows, `TriggerStateLock`, `ScheduleTimeCalculator`, WorkManager task IDs, `SyncWorker` run/native ownership, existing localized resources and current tests; no database schema or rclone pin change.
9. **Retired/decision:** replaced the old copy path that mutated the original trigger and reused its persisted ID; added snapshot rejection for already-delivered alarms. Independent reviewers confirmed the snapshot path and lock order by inspection. A historical enqueue/query audit found legacy manual and scheduled requests share WorkManager input/tag identity, durable run rows do not retain origin, and WorkInfo queries cannot recover absent trigger metadata. Do not blanket-cancel or reject ambiguous old requests: that could cancel pending manual work, while leaving them preserves stale-scheduled-run risk; product policy is required.
10. **Failure behavior:** trigger DB read failure, absent/deleted row, disabled row, stale/missing alarm snapshot or mismatched queued configuration prevents the newly tagged native launch; old ambiguous WorkManager requests remain a compatibility risk pending a safe migration design.
11. **Tests:** JDK 21.0.8 / Gradle 8.13 / SDK 36, offline and workspace-local Gradle/Android/temp state: `:app:mergeOssDebugResources` **PASS**; `:app:compileOssDebugKotlin` **PASS**; standalone JUnitCore `TriggerDispatchPolicyTest` **4/4 PASS** and `ScheduledTriggerExecutionPolicyTest` **4/4 PASS** (each javac run printed a ZipFS `AccessDeniedException` while closing the cached JUnit archive, exited 0, and JUnit executed all four). `:app:testOssDebugUnitTest` **BLOCKED / NOT PASSED before JUnit** on transformed `viewbinding-8.13.2-api.jar` `AccessDeniedException`; subsequent missing-symbol diagnostics are dependent. `TriggerTest` **NOT RUN**. `git diff --cached --check` **PASS**. Android instrumentation, Galaxy S26 and live Proton **NOT RUN**; native Gradle tasks excluded; no APK.
12. **Acceptance:** partial WP10 guards only, not package completion. Still open: legacy scheduled-work ambiguity; reusable trigger IDs without generation tokens; `long` PendingIntent ID narrowing/collision; single durable dispatcher/coalescing/missed-run behavior; verified boot/time-change/force-stop and missed-interval semantics; cross-process races; duplicate DB failure/UI test; alarm/edit/import/delete/worker barrier tests; Android lifecycle/quota/permission and Galaxy acceptance.
13. **Rollback/next:** revert only `e6097f1dc4f7fba87440359039c84196d250c922` if integrated tests expose regression; first resolve generation and safe legacy-request migration without harming manual work, replace lossy PendingIntent identity with tested migration, add barrier/instrumentation tests, and define missed-run/reboot semantics. Preserve rclone pin and do not publish pending WP08/WP10/WP14 gates.

## 2026-09-25 - WP14 acceptance gate inventory (ACCEPTANCE NOT RUN)

1. **Objective:** reconcile exact master WP14 acceptance obligations with present evidence so planning or narrower package tests are not mistaken for system acceptance.
2. **Scope:** master WP14/section 8 acceptance matrix, current CloudBridge/rclone evidence, `TEST_REPORT.md`, requirements and release-readiness state.
3. **Out of scope:** manufacturing passes from prior tests; live user-data testing; unbounded Proton traffic; replacing Galaxy acceptance with emulator results; closing WP14.
4. **Preconditions:** master attachment and current source/ledger checked. WP14 prerequisite “core packages complete” is unmet; no verified Samsung device or disposable Proton scope/client access is available in this environment.
5. **Design:** record each acceptance family separately and label absent evidence **NOT RUN**; keep focused rclone repetitions clearly distinct from integrated WP14 races.
6. **Safety invariants:** never lower thresholds; no destructive live-vault tests; no reuse/deletion of a pre-existing `Proton:RoundSync-Test`; create a unique disposable subdirectory only after ownership/scope verification. Preserve seeds, bytes/state and failed iterations when execution becomes authorized.
7. **Implementation:** added the gate-by-gate WP14 matrix to `TEST_REPORT.md`, corrected WP14 wording in `REQUIREMENTS_MATRIX.md`/`RELEASE_READINESS.md`, and reconciled the Patch Ledger signing entry so WP13 checks cannot be confused with WP14 acceptance.
8. **Reuse:** master sections 7, 8 and 9; existing test report, feature matrix, migration source inventory and current release gate table.
9. **Retired/decision:** older focused rclone `-count=100` runs do not satisfy per-critical-race integrated repetitions; prior JVM pass is not current-source acceptance; standalone Go build/cross-build is not APK/device evidence. WP14 is **ACCEPTANCE NOT RUN**, not “partially passed.”
10. **Failure behavior:** any corruption, lost version, secret leak, orphan writer, stale baseline or unresolved owner blocks release and must retain reproducer and persisted state.
11. **Tests / gate evidence:** complete real-engine local data matrix **NOT RUN**; full fault-injection matrix **NOT RUN**; >=100 barrier-driven app/native repetitions per critical race **NOT RUN**; separate-process lock takeover/corruption cases **NOT RUN**; initial eight-hour local soak/resource trend series **NOT RUN**; supported migration/rollback/interruption matrix **NOT RUN**; Galaxy S26 model/API/firmware and One UI 8.5/9 overnight lifecycle **NOT RUN**; verified disposable Proton sequence with official-client revisions and second-client edits **NOT RUN**. Current resource/Kotlin/pure-policy results concern WP10, not WP14 acceptance.
12. **Acceptance:** **NOT ACCEPTED / BLOCKING.** Earlier WP08/WP09/WP10/WP11/WP12/WP13 gates remain open; no exact integrated artifact/device/provider combination was tested.
13. **Rollback/next:** complete safe local fixtures, migration snapshots, fault harness, deterministic barriers, process-lock tests and measured soak independently; fix and rerun each failure. Only after prerequisites, device access and verified disposable Proton scope exist may real acceptance begin. Do not claim release readiness meanwhile.

## 2026-09-25 - WP15 strategic-plan preparation (PARTIAL; NOT FINAL)

1. **Objective:** assemble a current, evidence-linked security/reliability/engineering plan while keeping final WP15 sign-off gated on WP14 and preceding package evidence.
2. **Scope:** current CloudBridge/rclone findings and safety gates, source `e6097f1`, package ordering, regression strategy, residual risks, release decision and traceability docs.
3. **Out of scope:** external penetration certification, claiming all findings closed, branch-history cleanup, PR approval, signing, APK production or release authorization.
4. **Preconditions:** master WP15 final-deliverable section and current ledgers/docs checked; WP14 acceptance and multiple core packages remain incomplete.
5. **Design:** report current verified findings and owners, distinguish implemented safeguards from required safeguards, order work by dependency, and state explicit NOT RUN/residual conditions.
6. **Safety invariants:** no required feature silently deferred; no unresolved test shown as pass; no launch/mutation/release gate opens by documentation alone.
7. **Implementation:** added exact “Security, Reliability, and Engineering Quality Plan” section and SR-09 trigger compatibility/identity finding to `SECURITY_REVIEW.md`; updated requirements, test, compatibility, patch and release-readiness status for `e6097f1` and the WP14 matrix.
8. **Reuse:** master WP00-WP15, `AGENTS.md`, security findings SR-01–SR-09, feature-survival/requirements matrices, test report and release-readiness gate inventory.
9. **Retired/decision:** superseded old resume note that `39a5417` was current and later edits were documentation-only; source is now `e6097f1`. Strategic plan is a working deliverable, not final WP15 audit or security certification.
10. **Failure behavior:** any unresolved critical/high finding or missing acceptance evidence keeps readiness NO-GO and names exact owner/environment needed next.
11. **Tests:** documentation consistency and diff hygiene are checked; no new application test is implied by this plan. Current WP10 Kotlin/resource/policy results and Java/JUnit blocker are recorded above; Galaxy, Proton, instrumentation, PR/CI, signing and artifact provenance remain **NOT RUN/UNKNOWN**.
12. **Acceptance:** WP15 **NOT COMPLETE**. Final requirements/finding closure, independent PR/CI review, exact integrated build and provenance/rollback record, repository refresh and human release recommendation remain outstanding until WP14 evidence exists.
13. **Rollback/next:** keep the plan as current working evidence; revise each section after package changes and finalize only after master gates and all 13-field package records are reconciled. Maintain NO-GO and continue safe independent work.

## 2026-09-25 - WP14 exact app-pin lock regressions (PARTIAL EVIDENCE)

1. **Objective:** add repeated separate-OS-process evidence for the exact rclone revision configured by CloudBridge, without implying full WP14 acceptance.
2. **Scope:** clean detached worktree `work/rclone-app-pin-wp08` at immutable pin `fe775a8b58cf217fdf4bd34f0975af1e4c19c1a0`; all 11 existing `TestLockfile*` functions in `cmd/bisync/lockfile_test.go`, repeated 100 times each. Two named tests create independent OS processes.
3. **Out of scope:** rclone source edits; separate-process renewal/expiry, stale release, corrupt metadata and clock matrix; app/native barriers; migration/rollback; data matrix; soak; Android/Linux runtime; Galaxy S26; Proton; PR/APK/signing/release.
4. **Preconditions:** verified exact app pin and clean checkout; read the selected test implementations and `TestMain`; both tests create all lock profiles under Go `t.TempDir()` and invoke only a local helper process. No configured remotes were used.
5. **Design:** repeat the complete existing lockfile test group 100 times. This checks local acquisition/release, dry-run, same-process duplicate owner, legacy/unreadable metadata preservation, stale heartbeat, renewal/release serialization, delayed former-owner behavior, independent-process exclusion/release and crash recovery.
6. **Safety invariants:** module proxy and checksum network access disabled; test temp, config path and fresh Go build cache isolated under workspace `work/tmp`; `RCLONE_CONFIG` points to a unique nonexistent task-local file; password/config-command overrides unset; no remote/provider operation or user data access.
7. **Implementation:** no source changes. Added bounded evidence to `TEST_REPORT.md`, requirements/compatibility/release tables and this ledger; retained WP14 as **ACCEPTANCE NOT RUN / BLOCKING**.
8. **Reuse:** exact app-pin lock implementation, existing helper-process tests, Go 1.26.8, `-mod=readonly`, and task-local temp/cache paths.
9. **Retired/decision:** all 11 test functions passed, but only `TestLockfileSerializesIndependentProcesses` and `TestLockfileReclaimsAfterOwnerProcessCrash` exercise separate OS processes. The run does not prove app integration, Linux/Android semantics, separate-process renewal/expiry, stale release, corrupt metadata, clock behavior or any other WP14 gate.
10. **Failure behavior:** any test failure would retain its output and stop promotion/acceptance; no remote/provider state was available for mutation. Both selected tests completed with zero failures.
11. **Tests:** command `go test -mod=readonly -run '^TestLockfile' -count=100 -timeout=20m ./cmd/bisync`; Go 1.26.8 Windows/amd64; `GOPROXY=off`, `GOSUMDB=off`, `GOWORK=off`, `CGO_ENABLED=0`; `GOMODCACHE=work/go-mod-cache`; fresh `GOCACHE` and `TEMP`/`TMP` under `work/tmp/wp14-locksuite-94bcb85137914698bb330ecc85bc03bd`. Result: **PASS**, `ok github.com/rclone/rclone/cmd/bisync 178.236s`; all 11 matching top-level test functions ran 100 times. `RCLONE_CONFIG` pointed to a unique nonexistent task-local file. The exact rclone checkout remained unchanged.
12. **Acceptance:** local lockfile regressions have broad repeated evidence; only active-owner exclusion/release and process-crash recovery were tested across OS processes. Separate-process expiry/renewal, stale-owner release, corrupt metadata and clock tests remain **NOT RUN**; Android/Linux and integrated app/native acceptance remain **NOT RUN**. WP14 **NOT ACCEPTED / BLOCKING** because prerequisites and other gates remain open.
13. **Rollback/next:** no source rollback needed; leave app pin unchanged. Continue independent disposable local cases and current-source app tests; do not infer device/provider acceptance or release readiness from this slice.

## 2026-09-25 - Retrospective 13-field ledger normalization (WP00, WP02-WP05)

The following records normalize the earlier narrative entries to the master template. They
do not add tests or upgrade any earlier package to complete; the original evidence and
limitations remain in the dated sections above. Package-level status below is checked
against the master acceptance criteria, not the narrower historical “bounded implementation”
phrasing.

### WP00 - Freeze evidence and reproduce baseline

1. **Objective:** freeze both-repository identity, toolchain, test inventory and baseline failures before further implementation.
2. **Scope:** Rareities/CloudBridge and Rareities/rclone snapshots, dependency/build configuration, app pin, GitHub PR/CI evidence and workspace toolchains.
3. **Out of scope:** product-feature changes or replacing either repository history.
4. **Preconditions:** read the master handoff and both repository instructions; refresh the supplied snapshot through the 2026-09-22/23 GitHub evidence.
5. **Design:** record immutable branch SHAs, archive hashes, app configuration, dependency/toolchain versions, exact commands and per-gate PASS/FAIL/NOT RUN results.
6. **Safety invariants:** preserve dirty/user files; never push archive-import history as upstream ancestry; missing NDK, device/provider or signing access stays NOT RUN.
7. **Implementation:** created evidence-only source imports and history-preserving local branches, captured the baseline test/gap inventory, and added requirements/patch ledgers; WP01 later established the current immutable rclone app pin `fe775a8b58cf217fdf4bd34f0975af1e4c19c1a0`.
8. **Reuse:** repository build scripts, existing tests, Go 1.26.8, Gradle 8.13, JDK 17/21 evidence and Android project configuration.
9. **Retired/decision:** old Round Sync identity/status and absent Git history in downloaded archives are not current remote evidence; do not blindly fast-forward the 170-commit upstream gap or rewrite/push history.
10. **Failure behavior:** broad rclone tests remain incomplete after Windows server-script, symlink-privilege and WebDAV failures; Android native baseline is blocked by unavailable NDK/`local.properties`; none is presented as a pass.
11. **Tests:** standalone rclone build and focused sync/operations/ProtonDrive/Internxt tests passed; broad `go test ./...` was not clean; Gradle pin/config tasks and an older 36-test JVM/lint run passed; native APK, reproducibility/inspection, Galaxy and live Proton were **NOT RUN**.
12. **Acceptance:** **PARTIAL.** Immutable identity, evidence/gap report and current candidate are recorded, but clean integrated native build/APK inspection and environmental acceptance gates are not established.
13. **Rollback/next:** keep archive imports as local evidence, retain known baseline commits and do not push; continue with WP01 independent engine review and refresh GitHub state before any PR/release.

### WP02 - Secure execution inputs and diagnostics

1. **Objective:** harden externally reachable command inputs, shortcut authorization and diagnostic sinks.
2. **Scope:** CloudBridge command construction, exported shortcut route, centralized logging/redaction and error text; bounded source commit `f3bd473`.
3. **Out of scope:** Bisync transfer semantics and complete native-process lifetime ownership.
4. **Preconditions:** WP00 baseline and inventory of the affected app routes/sinks.
5. **Design:** typed/validated requests, per-task shortcut capability and common redaction/bounds before data reaches log or user-facing sinks.
6. **Safety invariants:** no shell interpolation, raw secret-bearing diagnostics, unbounded log output or implicit destructive defaults; invalid shortcut authorization fails closed.
7. **Implementation:** centralized redaction covers formatted/file/sync logs and rclone stderr, bounds diagnostics and sync-log growth, and validates shortcut action, task ID and capability token.
8. **Reuse:** private components, URI checks, scoped work tags and existing shortcut creation flow.
9. **Retired/decision:** legacy shortcuts without a capability token require recreation; this is an explicit safe-compatibility boundary.
10. **Failure behavior:** malformed/unauthorized requests are rejected with sanitized errors; broader process execution/lifecycle findings stay assigned to WP05 and later packages.
11. **Tests:** recorded `:app:testOssDebugUnitTest` **PASS** (22 tests) and `:app:lintOssDebug` **PASS** with pre-existing findings; native artifact, Samsung and live Proton were **NOT RUN**.
12. **Acceptance:** **PARTIAL.** The bounded shortcut/diagnostic slice is implemented, but the master all-public-routes and no-secret-sink audit is not complete; do not call WP02 package acceptance closed.
13. **Rollback/next:** revert only the isolated boundary commit if its regressions require it; enumerate every public/Runtime.exec/RC path, retain safe adapters and continue WP03-WP05 tests.

### WP03 - Make config and database changes recoverable

1. **Objective:** make imports, database replacement and credential storage recoverable on validation/write/key failure.
2. **Scope:** CloudBridge import/export, SQLite replacement, staged config backup and passphrase storage; bounded source commit `3ab1d6b`.
3. **Out of scope:** unrelated schema/style cleanup or whole-config encryption redesign without migration evidence.
4. **Preconditions:** WP02 redaction and WP00 migration/baseline fixtures.
5. **Design:** bound and validate the complete payload, stage config changes, transact database replacement and preserve snapshots for rollback.
6. **Safety invariants:** never discard the prior valid database/config before validation; never clear ciphertext on Keystore/key loss; do not log imported secrets.
7. **Implementation:** validates schema/references/legacy arrays, remaps imported IDs transactionally, stages ZIP config entries, restores prior stores on later failure, and wraps the stored rclone passphrase with AES-GCM/Android Keystore.
8. **Reuse:** existing importer, database/preferences stores, encrypted native-config support and import UI.
9. **Retired/decision:** removed destructive importer ordering and plaintext decrypt-over-config behavior; the config file itself is not claimed to be whole-file encrypted.
10. **Failure behavior:** failed insert rolls back DB replacement; staged config errors restore prior stores; Keystore loss retains ciphertext and requires explicit recovery.
11. **Tests:** recorded unit suite **PASS** (30 tests), lint task **PASS** with 91 warnings/6 baseline-filtered errors, and bounded import validation tests pass; crash/disk-full injection, live Keystore, native APK, Galaxy and Proton are **NOT RUN**.
12. **Acceptance:** **PARTIAL.** Bounded recoverability implementation exists, but master crash-atomic migration/key-loss/concurrency tests and live device proof are missing; earlier “complete for bounded scope” wording does not mean WP03 package closure.
13. **Rollback/next:** restore only from verified snapshots/transaction rollback; do not downgrade DB blindly; run crash/disk-full/rename/key-loss and supported migration tests before claiming acceptance.

### WP04 - Create authoritative profiles and run state

1. **Objective:** establish durable profile identity and one persisted owner/result for each run.
2. **Scope:** CloudBridge profile/run repositories, schema migration, legacy task adapter and worker claim/finish paths; bounded source commit `acc4f5c`.
3. **Out of scope:** transfer-algorithm reimplementation or silently coercing unsupported legacy modes.
4. **Preconditions:** WP03 transactional import/config foundation.
5. **Design:** stable UUID, semantic revision, mode/readiness, endpoint/settings fingerprint and engine pin, with one durable active owner and explicit terminal result.
6. **Safety invariants:** queued work cannot retarget after profile edit/delete; unknown counters remain unknown; old Bisync/unknown directions require repair rather than conversion.
7. **Implementation:** deterministic legacy mapping, active-run uniqueness, transactional task/import updates, claim revalidation and startup recovery for uncertain rows.
8. **Reuse:** existing task/trigger model through a compatibility adapter and WorkManager ownership.
9. **Retired/decision:** numeric task IDs are not the durable authority; compatibility work remains for legacy requests and ephemeral file-explorer flows.
10. **Failure behavior:** stale/repair-required claims are refused; interrupted or uncertain ownership moves to explicit recovery state, not success.
11. **Tests:** recorded model/JVM suite **PASS** (36 tests), lint **PASS** with 98 warnings/6 baseline-filtered errors; migration, process-death, duplicate-dispatch and terminal-write instrumentation were **NOT RUN**.
12. **Acceptance:** **PARTIAL.** Durable profile/run ownership is present, but UI readiness/ephemeral adapters and instrumented migration/process-death behavior are not closed; the master one-source-of-truth gate remains open.
13. **Rollback/next:** preserve the schema checkpoint and explicit compatibility adapter; add migration/terminal-write/process-death coverage and keep Bisync mutation blocked until WP08 gates pass.

### WP05 - Own the complete native lifetime

1. **Objective:** ensure one owner controls native launch, pipe drain, cancellation, timeout, exit/reap and resource release.
2. **Scope:** CloudBridge workers, metadata/config commands, listing, serving/streaming and RCD process families across commits `a3c87f0` through `0337d85` and recorded follow-ups.
3. **Out of scope:** a new generic service framework or changing rclone transfer algorithms.
4. **Preconditions:** WP02 safe inputs/diagnostics and WP03-WP04 durable run ownership.
5. **Design:** `NativeExecutionHandle` owns process lifetime and bounded dual-pipe drains; terminal outcomes are single-assignment and attached resources remain leased until confirmed reap.
6. **Safety invariants:** no lock/resource release while native mutation might continue; unconfirmed exit cannot report success or admit conflict work; cancellation and interruption propagate.
7. **Implementation:** migrated operation families to the handle, drained stdout/stderr concurrently with caps, serialized stop-before-launch, and retained an RCD unconfirmed-exit guard across service recreation; raw process construction/lifecycle was consolidated in launch plumbing/handle.
8. **Reuse:** existing `Rclone` launch/config APIs, scoped transfer locks, coroutines, worker/result model and streaming adapters.
9. **Retired/decision:** removed scattered caller ownership and wait-before-drain deadlock patterns; preserve interactive pipe semantics and do not create dual owners for one operation.
10. **Failure behavior:** failed/unconfirmed exits remain failure/recovery-required; RCD guard intentionally stays blocked after process-death until a verified recovery path exists.
11. **Tests:** at the WP05 milestone, JVM suite reported 53 tests/11 suites pass; Go build/focused tests and prior four-ABI native build passed; a historical universal debug APK hash `CA9DD6D6B413359A5A4E0CBA1F64DA2BD4993CD6FECA2BE3288ADBDDC9FC6A27` was debug-signed with old engine pin `1583cce1e28340e5d064ed955179f5f2b31e7757`, not the current source/pin. Process-death/lifecycle instrumentation, Galaxy, live Proton and current-code APK/provenance remain **NOT RUN**.
12. **Acceptance:** **PARTIAL.** Most retained process callers were migrated, but master process/resource leak and Android lifecycle/recovery evidence is incomplete; the historical debug APK is not a current candidate or release artifact.
13. **Rollback/next:** migrate/revert by operation family without dual ownership; retain the RCD guard on uncertain exit; finish lifecycle/process-death tests and inspect a fresh current-pin APK only after prerequisites.

### WP11 - First-class Bisync and Obsidian UI (retrospective 13-field normalization)

1. **Objective:** make Bisync setup, preview, progress, conflict/recovery and the optional Obsidian workflow understandable and safe for ordinary users.
2. **Scope:** current preview request/review screen, durable preview status, feature-survival audit, user guidance and the missing Obsidian integration.
3. **Out of scope:** authorizing native apply/initialization/recovery before WP08 preservation and restore are verified; promising exact Android background timing; adding unsupported background-launch or AccessibilityService behavior.
4. **Preconditions:** stable WP07-WP10 identity, mutation, ownership and scheduling interfaces. WP08 and WP10 remain partial, so these prerequisites are unmet.
5. **Design:** UI renders durable state and engine capabilities but does not make independent safety decisions; unknown foreground state remains deferred and manual use remains available without Usage Access.
6. **Safety invariants:** preview completion is never represented as a successful sync; no launch follows cancellation/failure; no UI action bypasses freshness, run ownership or recovery gates.
7. **Implementation:** a read-only Bisync preview request/review surface and durable status/freshness messaging exist. The source audit found no Obsidian foreground observation, vault fingerprinting, debounce/deferred scheduling, sync-before-open or launch workflow; the regular worker rejects unsupported Bisync directions.
8. **Reuse:** existing Material UI/resources, preview repository/scheduler/workers, native preview protocol, run identity and localized error/status resources.
9. **Retired/decision:** keep Apply, initialization, recovery and Obsidian launch unavailable until WP08 has a mandatory verified mutation/restore boundary and WP10 has durable deferral/cancellation. Do not reuse the legacy direct `SyncService` intent recipe; it conflicts with the current non-exported manifest boundary.
10. **Failure behavior:** unsupported directions and stale/incomplete preview evidence fail closed and remain visibly distinct from applied work; unknown foreground is not treated as Obsidian being closed.
11. **Tests:** no WP11 package-level UI/state-transition acceptance is recorded. Accessibility, process recreation, permission denial, repeat taps, launch races, custom-path/provider validation and foreground-unknown tests are **NOT RUN**; compile and standalone policy results belong to other packages and do not satisfy these tests.
12. **Acceptance:** **PARTIAL / NOT COMPLETE.** The preview-only slice is truthful, but the master UI paths, Obsidian workflow, lifecycle/accessibility tests and Galaxy acceptance are open; no WP11 completion is claimed.
13. **Rollback/next:** retain preview-only behavior and manual no-Usage-Access route. Design the remaining UI against verified WP08/WP10 contracts, add focused state/accessibility tests first, and keep apply/recovery/launch disabled until prerequisites pass.

## 2026-09-25 - WP14 Bisync package test-isolation fix (PARTIAL LOCAL EVIDENCE)

1. **Objective:** remove order-dependent state-test failures in the standalone Bisync package so the suite reports its own results deterministically.
2. **Scope:** rclone candidate branch codex/wp14-stats-isolation, based on CloudBridge's exact app pin; cmd/bisync/state_test.go only.
3. **Out of scope:** production engine behavior, changing CloudBridge's app pin, other packages, cross-process scenarios not already covered, provider/device acceptance, PR or release.
4. **Preconditions:** exact-pin candidate had a six-test aggregate-only failure: the expected file/directory-collision error left an error in process-global accounting stats, and a later dry-run interpreted it as a listing failure. Tests passed alone; the interaction reproduced in the aggregate.
5. **Design:** provide each state test a fresh rclone configuration and a unique accounting stats group; retain the existing data and expected-failure assertions.
6. **Safety invariants:** no production logic change; no remote/config credentials; tests use disposable local fixtures; app pin remains fe775a8b58cf217fdf4bd34f0975af1e4c19c1a0.
7. **Implementation:** helper newStateTestContext uses fs.AddConfig and accounting.WithStatsGroup with a unique random identifier; all state tests in the file now use the helper. Commit 7045c79f9adcd769118977fd07e5ddaaa5bc51ee, tree c77c5da0003a7feebdaad538c27ffd17491674c2.
8. **Reuse:** rclone accounting.WithStatsGroup, the existing state-test fixture constructors and Go testing temp directories.
9. **Retired/decision:** retired shared default accounting-group state across these direct in-process test calls; this fixes test isolation, not a production Bisync defect.
10. **Failure behavior:** a future test must not inherit prior test errors; the dry-run regression remains asserted to preserve root bytes and canonical listings.
11. **Tests:** selected six preservation/guard tests passed together with count=10; complete go test -mod=readonly -timeout=20m ./cmd/bisync passed on Go 1.26.8 Windows/amd64, offline, exit 0 (41.105s). git diff --check passed. The run required a short task-local temp path because the default Windows temp and a long path were inaccessible to fixtures.
12. **Acceptance:** **PARTIAL LOCAL ENGINE TEST EVIDENCE ONLY.** The complete cmd/bisync package passes on this test-only candidate; broader repository tests were not rerun and WP14 remains NOT RUN / NOT ACCEPTED because core prerequisites, other stress/fault/device/provider gates remain open.
13. **Rollback/next:** revert only the test helper change if it introduces instability; keep this candidate isolated from the app pin. Review diff against refreshed Rareities/rclone master and establish a narrow reviewable base before any PR.

## 2026-09-25 - WP13/WP15 documentation and audit crosswalk (PARTIAL)

1. **Objective:** align repository-facing docs with the current master plan, source evidence and limits of the live-status snapshot.
2. **Scope:** current C01-C12 finding register; feature survival, compatibility, requirements, security, test, release, build, user and static-site docs; historic audit warning.
3. **Out of scope:** implementing remaining application features, accepting unresolved package gates, creating a PR, signing, building or publishing an APK.
4. **Preconditions:** reread the authoritative WP00-WP15 attachment; review current source and existing docs/ledgers; refresh GitHub through the available read-only connector.
5. **Design:** each C finding has eight required evidence fields; each of 16 feature rows has an explicit KEEP disposition and migration status; distinguish registered source from verified support; state workflow/release query limits.
6. **Safety invariants:** no feature removal, no compromise inference, no actionable obsolete key-rotation directive, and no unavailable device/provider test represented as a pass.
7. **Implementation:** added CURRENT_AUDIT_FINDINGS.md; updated FEATURE_SURVIVAL_MATRIX.md, COMPATIBILITY_MANIFEST.md and the related status guides/pages; corrected the historical Phase 0/RG-0 headings and current release/Actions wording.
8. **Reuse:** current source paths, master §4 and §7-§9, existing SR findings, tests and release-readiness table.
9. **Retired/decision:** withdrew inherited signing-emergency language; no key, history, release, feature or user data was changed.
10. **Failure behavior:** unverified support remains partial/NOT RUN; Bisync initialization/apply/recovery and distribution remain blocked.
11. **Tests:** crosswalk structural audit found 12 findings/96 field entries; local relative HTML href/src audit found no missing local targets; git diff --check passed. No app code tests or build were run.
12. **Acceptance:** **DOCUMENTATION SLICE COMPLETE; WP13 PARTIAL; WP15 PREPARATORY ONLY.** This does not close any feature or release gate.
13. **Rollback/next:** revert only documentation hunks if required. Continue remaining code/package work and rerun validation after later doc edits.
