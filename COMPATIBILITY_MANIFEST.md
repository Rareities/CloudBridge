# Compatibility manifest

**Status:** engineering snapshot only, not a supported-release contract. **Checked:** 2026-09-27. Refresh source, tests, CI, PRs, dependencies, and acceptance evidence before app-pin promotion or release. Source registration and declared SDK/ABI values do not alone establish runtime compatibility.

**Current implementation identity:** CloudBridge code commit `ddeee759a034b71f0c08697f0cbf17cfba3320e8`
on `Rareities/CloudBridge:codex/luna-implementation`; branch head `5b608a2` adds only
the reconciled ledgers below; later branch commits are documentation-only provenance
clarifications. App-native rclone pin
`cf3ad40d29d15919af116a5d1e64e0381e2ce3fd` from `Rareities/rclone:codex/luna-engine-final`
(branch head `4d6404e`, ledger-only descendant).
OSS/RS JVM suites are 473/473 with zero failures/errors and two skips per flavor; both debug
flavors assemble and both flavor lint tasks pass. The source-bound OSS debug manifest records
five APKs/four ABIs and toolchain metadata. Instrumentation, Samsung Galaxy S26, live Proton,
production signing and release acceptance are **NOT RUN/NO-GO**. PR creation is deferred by
user instruction; no upstream/original PR is created or updated.

**Current-source follow-up (2026-09-26):** the active dirty development tree is schema v17, not
v16. The checked 2026-09-25 evidence below remains historical unless explicitly refreshed here;
this note does not claim migration instrumentation or device acceptance.

**Package map refresh (2026-09-27):** the master handoff is canonical: WP00-WP15, with WP14
stress/device acceptance and WP15 final handoff. The addendum's WP00-WP16 wording and migration
definition of WP14 are cross-cutting requirements, not a competing package sequence. Its migration,
identity, signing and rollback gates are mapped to WP03, WP04, WP08, WP13, WP14 and WP15; no WP16
is created. No package has master-level acceptance, and this mapping is not migration evidence.

## Source and build identity

| Field | Current value | Evidence / limitation |
|---|---|---|
| CloudBridge repository | `https://github.com/Rareities/CloudBridge` | Read-only refresh recorded default branch `master` at `c492876258ca841232229249519abe92ff77c3a4` on 2026-09-25. This is not the local implementation checkpoint. |
| Current local app branch | `codex/luna-implementation` | Latest committed source is `ddeee759a034b71f0c08697f0cbf17cfba3320e8`, pushed to the Rareities fork. The worktree is clean apart from ignored task-local caches/artifacts. OSS and RS debug APKs were built from this commit; they are debug-signed and not release artifacts. |
| Application ID | `de.schuelken.cloudbridge` | `app/build.gradle`; debug variants append `.debug`. Existing signing continuity is not established. |
| App version defaults | `1.0.1`, versionCode `20` before ABI offsets | `app/build.gradle`; environment overrides exist. Not approved as a release version. |
| Minimum / compile / target SDK | 23 / 36 / 36 | `app/build.gradle`; declaration is not proof of API/device acceptance. |
| Flavors / ABIs | `oss`, `rs`; `armeabi-v7a`, `arm64-v8a`, `x86`, `x86_64`, universal APK | `app/build.gradle`; both debug flavors package all four native ABIs. OSS provenance manifest records the five OSS APKs. APKs use the Android Debug signer; release provenance/signing is absent. |
| Gradle / Java | Wrapper 8.13; checked-in Android workflow uses Temurin JDK 17 | Local verification used Temurin JDK 21.0.8. |
| Go / NDK | Minimum Go 1.26.0; NDK 29.0.14206865 | `gradle.properties`; local rclone checks used Go 1.26.8 on Windows/amd64. |
| Database schema | SQLite version 17 | Active development source at `058b63a` plus dirty WP04 changes; v16 introduced app-side Bisync preflight/preview and partial backup-manifest records, while v17 adds `run_filter_snapshot`. Android-test sources compile, but v17 migration instrumentation/runtime execution is **NOT RUN**. A production mutation gate, complete restore/recovery contract, supported source-to-target migration/rollback matrix, and on-device migration result remain absent. |

## Cross-package migration, identity and rollback boundary

The supplementary WP14 addendum was read in full (attachment SHA-256
`9A2841F4C3818BBCA2C5248C247D96602AC18267F5B1933F79C5276D4A41C96C`). It makes migration and
identity tests mandatory. The supplied master text defines WP00-WP15, with WP14 stress/device
acceptance and WP15 final handoff; the addendum says WP00-WP16 and defines WP14 as migration/rollback.
The master is the canonical package sequence; the addendum's migration/identity criteria remain
mandatory cross-cutting gates assigned to WP03, WP04, WP08, WP13, WP14 and WP15. No WP16 is created.
The addendum's current-v8 statement is older than inspected local source: `work/CloudBridge` master
is schema v10, isolated validation is v11, and active dirty development is v17. These separate trees
do not establish a supported version transition or any release compatibility.

| Installed source/state | Candidate target | Identity/signature relation | Migration evidence and preservation result | Status |
|---|---|---|---|---|
| No verified signed Rareities release artifact in the available snapshot; source version, package signer and released state are not established | Current production/release variant | Default application ID is de.schuelken.cloudbridge; production certificate continuity is UNKNOWN / NOT VERIFIED | No signed source artifact or prior-state manifest is available to exercise an in-place update | **NOT ESTABLISHED — do not claim an upgrade path** |
| Current local OSS debug build | Another OSS debug build | Debug application ID has the .debug suffix; debug signer continuity across machines/build environments is not recorded | Current source uses schema v17; current-source instrumentation and app-data upgrade/rollback were **NOT RUN** | **NOT ACCEPTED — development-only** |
| Existing database schema 15 | Current schema 17 | Same local app source family; app identity/signing not tested by this database-only fixture | Instrumented test source seeds a pre-v16-shaped row set and asserts preservation/new manifest tables, then adds the v17 filter snapshot; this hand-built fixture is not tied to a signed release artifact and did not run | **AUTHORED, NOT RUN — not a supported release migration claim** |
| Schema 8 | Current schema 17 | No exact released source artifact or app identity is recorded | v8 instrumentation fixture seeds only a synthetic sentinel row and checks selected new-table creation; it does not represent a captured v8 app database and was **NOT RUN** | **NOT ACCEPTED — compatibility not demonstrated** |
| Schema 10 or 12 | Current schema 17 | No exact released source artifact or app identity is recorded | Hand-built instrumentation fixtures exercise selected profile/preflight/filter-snapshot upgrades, not complete historical database images; neither ran | **AUTHORED, NOT RUN — compatibility not demonstrated** |
| Schema 13, 14, or 16 | Current schema 17 | No exact released source artifact or app identity is recorded | Hand-built fixtures cover selected preview-owner/filter-snapshot transitions, not complete historical database images. Android-test sources compile, but no instrumentation assertion ran | **FIXTURE SOURCE COMPILES - RUNTIME NOT RUN / NOT ACCEPTED** |
| Unknown/newer database, app state, or native Bisync listing | Older app/engine | May be incompatible; signer relation unknown | No app-defined downgrade path or native listing rollback acceptance is recorded. Do not overwrite source state or auto-resync to mask incompatibility | **UNSUPPORTED / NOT TESTED — block downgrade and preserve source** |
| Export/import to a new application ID | Side-by-side app | New-ID migration is not selected or implemented as an accepted product path | WP03 has bounded importer/config safeguards, but authenticated end-to-end export/import, app-ID isolation, post-import scheduling and recovery are **NOT RUN** | **NOT ESTABLISHED — retain the original app/data** |

The table is a truthful gap record, not approval of a migration. A release path must name the exact
source/target artifact, app ID, signer, database/config/native-state versions, and tested recovery
boundary. Do not uninstall the old app, discard old data, or claim rollback until WP03/WP04/WP08/
WP13 migration and identity prerequisites pass and the integrated stress/final handoff gates are
complete under the package numbering the user confirms.

## rclone engine pin and dependency evidence

| Field | Current value | Evidence / limitation |
|---|---|---|
| Engine repository | `https://github.com/Rareities/rclone.git` | Configured in `gradle.properties`. |
| App immutable engine ref | `cf3ad40d29d15919af116a5d1e64e0381e2ce3fd` | Configured in `gradle.properties`; `app/build.gradle` rejects a ref that is not a full 40-character SHA. Gradle refreshes origin refs, verifies reachability and exact checkout, and writes source provenance. This does not establish live provider/device acceptance. |
| Declared engine version | `1.76.0` | Build property in `gradle.properties`. No release compatibility claim is made from the property alone; verify the built binary’s version/ref before release. |
| Rareities/rclone implementation branch | `codex/luna-engine-final` at `cf3ad40d29d15919af116a5d1e64e0381e2ce3fd` | Fork branch is the exact app pin and was independently checked out/refreshed by Gradle. The commit is unsigned; no release claim is made. |
| Proton dependency versions at app pin | Proton-API-Bridge `v1.0.5`; go-proton-api `v1.0.4`; gopenpgp `v3.4.1` | Versions are in the pinned checkout's `go.mod`. WP09 package tests/vet used the pinned rclone source paired in an isolated temporary workspace with a local Proton API Bridge v1.0.5 candidate; that candidate was not promoted as a reviewed app dependency change and no live provider calls occurred. |

At the exact app-pinned rclone SHA, all 11 existing `cmd/bisync` `TestLockfile*` top-level tests passed 100 repetitions on Go 1.26.8 Windows/amd64 (`go test -mod=readonly -run '^TestLockfile' -count=100 ...`). Only `TestLockfileSerializesIndependentProcesses` and `TestLockfileReclaimsAfterOwnerProcessCrash` exercise separate processes. `go build ./...` and focused preservation checks are recorded as passing; broader `go test ./...` was **NOT CLEAN**, the race detector was **NOT RUN**, and Android/Linux runtime tests remain **NOT RUN**. Do not generalize these slices into engine-wide acceptance.

## Provider compatibility boundary

| Area | Checked-in source | What can be claimed now |
|---|---|---|
| App provider discovery | `Rclone.java#getProviders`; `RemoteConfig/ProviderListFragment.kt` obtains `mRclone.providers` | The app presents provider metadata returned by the configured engine. This is dynamic configuration UI, not a per-provider support or certification list. |
| Engine provider registration | At the exact app pin, `backend/all/all.go` imports backends including `protondrive`, `internxt`, `drime`, `drive`, `dropbox`, `s3`, `sftp`, and `webdav` | These backends are registered in that source build. Registration does not prove successful authentication, complete operations, app-specific UI compatibility, service availability, or released support. The complete import list is in that file. |
| App-specific provider paths | `RemoteConfig/ConfigCreate.kt` and related Internxt reauthentication code; `Rclone.java` has provider identity handling for Proton Drive, Internxt, and Drime | Source integration exists for these paths. Provider behavior across auth refresh, account/session changes, transfers, and upgrades is not fully accepted. |
| SAF/WebDAV bridge | `safdav` module, `VirtualContentProvider.java`, storage/document-tree code | This is a separate Android/SAF integration, not evidence that the rclone `webdav` backend or every Android document provider is compatible. Public-provider/device acceptance is **NOT RUN**. |
| Supported provider matrix | None recorded as an accepted release matrix | **NOT ESTABLISHED.** Do not label every registered backend “supported.” Proton live tests are **NOT RUN**; Internxt/Drime provider acceptance is also not established by source presence. |

## Bisync and state compatibility boundary

| Area | Checked-in source / evidence | Compatibility limit |
|---|---|---|
| Engine Bisync code | Pinned rclone `cmd/bisync`; includes `--inspect-state`, preview support, and lockfile implementation/tests | `--inspect-state` is explicitly read-only and says it does not recover or migrate native state. Pinning one engine SHA does not establish compatibility of old listing files across future engine upgrades. No native listing-format migration matrix is recorded. |
| App preview/preflight | `Rclone.java#runBisyncPreview`, `Database/BisyncPreflight*`, `Database/BisyncPreview*`, `workmanager/BisyncPreview*`; test sources include `BisyncPreflightTest`, `BisyncPreviewCommandBuilderTest`, `BisyncPreviewFreshnessTest`, and repository instrumentation tests | Preview/preflight source exists. Android instrumentation/migration tests did not run; the preview/manifest records are not a production mutation authorization or a restore guarantee. |
| Backup/restore foundation | `Database/BisyncBackupManifestRepository.kt`, schema v16 manifest foundation plus schema v17 filter snapshot, `BisyncBackupManifestRepositoryTest.kt` | DB-only pending-manifest foundation. Production caller, provider reservation, exact-byte verification, restart reconciliation, complete restore, and mutation-boundary enforcement remain missing/open. Keep initialization, apply, and recovery unavailable. |
| Native lock slice | `cmd/bisync/lockfile_test.go`; 11 top-level lock tests ×100 at exact pin, Windows/amd64 | Partial evidence only. Separate-process renewal/expiry, stale release, corruption/clock cases, Android/Linux behavior, integrated app/native races, and WP14 acceptance remain **NOT RUN**. |

## Runtime/support acceptance

**WP02 current-tree correction (2026-09-25):** the late-reap follow-up raises `NativeExecutionHandleTest` to **16/16** and the combined standalone helper/native suite to **38/38 PASS**. Older counts below are superseded. OSS Kotlin compiles; app Javac remains **BLOCKED / NOT PASSED** on SDK/dependency `AccessDeniedException` with dependent diagnostics, and Galaxy/Proton/instrumentation remain **NOT RUN**.

| Area | Current status |
|---|---|
| Android JVM | Prior dirty-tree OSS/RS full passes: 231 tests per flavor, 0 failures/errors, 2 Windows symlink-capability skips per flavor, based on docs HEAD `058b63a` plus earlier uncommitted changes; `:rclone:buildAll` excluded. These full passes predate current WP02/WP12 source edits. Current standalone `VcpJobCompletionTest`/`LogRedactorTest` pass 7/7 and 5/5; bounded share/input/provider/tag helper suites pass 22/22; `NativeExecutionHandleTest` passes 14/14 in standalone JUnit (one asynchronous Android Log JNI warning from the non-Android harness). OSS Kotlin compilation passes, including process-aware staged-source cleanup; full app Java compilation is **BLOCKED / NOT PASSED** by Windows/JDK access to cached transformed `print-1.0.0-api.jar`, with downstream missing-symbol diagnostics inconclusive. `WorkInfo` terminal state is not treated as proof of native-process exit; uncertain enqueue/exit preserves staging, so queued-cancellation/process-death orphans remain an open recovery item. A separate Android-test source compilation is compile-only, not instrumentation. Earlier `:app:lintOssDebug` exited 0 with 147 active warnings and was not rerun on the newest tree. Instrumentation, current full suite, provider/device acceptance, Galaxy and Proton remain **NOT RUN / NOT ESTABLISHED**. The earlier OSS debug APK was inspected with the cached exact Rareities pin; it is debug-signed, not a release artifact. |
| Android instrumentation | **NOT RUN** in this verification; authored Bisync migration/repository tests did not execute. |
| Actual acceptance device | **NOT RUN**. Required target: Galaxy S26 on One UI 8.5/9; actual Android API and firmware must be recorded during testing. |
| Proton Drive | Live provider/official-client acceptance **NOT RUN**; no credentials or verified unique disposable scope. `Proton:RoundSync-Test` is a proposed area, not permission to delete or overwrite it. |
| Obsidian | No explicit integration/entry point identified in current app source; **NOT VERIFIED**, not a supported behavior claim. |
| Production signing identity | **UNKNOWN / NOT VERIFIED**. The release gate now requires `CB_SIGNING_CERT_SHA256` and compares the configured alias's X.509 certificate hash; policy checks and a disposable synthetic-keystore pass/mismatch test passed. No authorized production fingerprint, key continuity or signed release artifact is present. |
| APK / ABI | A prior OSS debug-only four-ABI APK set was built and inspected from an earlier source tree; it is not evidence for the current working tree or a release artifact. Current RS APK packaging, current-source artifact inspection, production certificate continuity, and release provenance are **NOT VERIFIED**. |
| CI / PR / release snapshot | 2026-09-25 read-only GitHub refresh returned no open PRs and no combined status checks for the observed CloudBridge/rclone default heads or exact app-pinned engine SHA. The GitHub Actions runs API returned `total_count=0` for both repositories. The checked-in `.github/workflows/android.yml` runs OSS debug unit tests and packages a debug APK on push-to-master, PR-to-master, or manual dispatch; it does not upload an artifact or publish a release. The current Releases API queries for both forks returned empty arrays (no published GitHub releases at query time). Branch-protection endpoint queries returned 403 (resource not accessible by integration), so protection status is **NOT VERIFIED**. The local OSS four-ABI debug APK set was built and inspected; RS APK packaging is **NOT BUILT** after a Windows/JDK archive-access failure. Neither is a production release artifact. |

This manifest is a traceability aid, not authorization to publish, not an ABI/API/provider compatibility guarantee, and not a substitute for migration, stress/device acceptance, or final release review. Their exact WP numbering is pending resolution of the supplied master/addendum conflict.
