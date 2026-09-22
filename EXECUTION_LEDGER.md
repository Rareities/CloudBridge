# CloudBridge + rclone execution ledger

This ledger records implementation evidence for `CloudBridge-rclone-Luna-Master-Handoff.md`.
It is maintained separately from the source checkout while the projectless task is being
bootstrapped, and is intended to move into the reviewed repository documentation once a
real Git checkout is available.

## Standing instructions

- The complete handoff at `work/CloudBridge-rclone-Luna-Master-Handoff.md` is authoritative.
- Work one bounded package at a time; WP04 is the next package after the completed WP03 entry.
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

The local integration pin is now recorded in CloudBridge commit `f4f622e`:

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
| CloudBridge -> Rareities/rclone configuration pin | PASS with limitation | Commit `f4f622e` uses the immutable Rareities ref and fail-closed missing-property checks; `:rclone:tasks` and `:rclone:properties` pass under Gradle 8.13/JDK 17 and print the exact URL/ref/version; native compilation is NOT RUN |
| Android unit/lint/debug build | PARTIAL | CloudBridge 30 JVM unit tests and lint pass under JDK 17/Gradle 8.13; the debug APK/native build remains NOT RUN because the NDK and `local.properties` are unavailable |
| Release/R8/signing/ APK inspection | NOT RUN | No compatible Android build toolchain or signing evidence |
| Samsung Galaxy S26 / One UI acceptance | NOT RUN | No acceptance device access in this environment |
| Live Proton Drive disposable-area tests | NOT RUN | No Proton credentials or approved disposable remote area |

### WP00 acceptance status

Baseline identity, current app pin, repository instructions, CI/PR refresh and gap list are
recorded in the local evidence commits above. Standalone engine build and focused safety,
Proton and Internxt tests now have evidence, and the Gradle configuration resolves the exact
Rareities URL/ref/version under JDK 17. WP00 build/test acceptance remains incomplete because
the Android NDK, acceptance device/provider access and full upstream Git metadata are
unavailable. The app pin is already changed locally in the pre-existing `f4f622e` commit; this
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
from the pre-existing `f4f622e` commit. No further URL/ref change was made in WP01. The
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

CloudBridge commit `d50bb21` implements the bounded WP02 input and diagnostic hardening
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

CloudBridge commit `c7f67e4` implements the bounded WP03 recoverability scope. Database and
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

## Next package

WP04 — authoritative profiles and run state is the next bounded package. It must read this
ledger and the current source, preserve the existing CloudBridge -> Rareities/rclone pin,
and establish the profile/run-state model before broader scheduling, Bisync or provider work.
A moving branch or fallback to `thies2005/rclone` remains prohibited.
