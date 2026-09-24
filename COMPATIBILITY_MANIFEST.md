# Compatibility manifest

**Status:** engineering snapshot only, not a supported-release contract. **Checked:** 2026-09-25. Refresh source, tests, CI, PRs, dependencies, and acceptance evidence before app-pin promotion, PR, build provenance, or release. Source registration and declared SDK/ABI values do not alone establish runtime compatibility.

## Source and build identity

| Field | Current value | Evidence / limitation |
|---|---|---|
| CloudBridge repository | `https://github.com/Rareities/CloudBridge` | Read-only refresh recorded default branch `master` at `c492876258ca841232229249519abe92ff77c3a4` on 2026-09-25. This is not the local implementation checkpoint. |
| Current local app branch | `codex/luna-implementation` | Source checkpoint `e6097f1dc4f7fba87440359039c84196d250c922` includes WP10 trigger-state/alarm/follow-up guards and a WP12 trigger-copy identity/persistence fix, atop WP08 `2c302158`, WP12 `af638f3`, WP13 `193d972`, and earlier changes. It is local/unpublished and has no APK. User-owned `.android/` remains untouched and unstaged. The last full Android JVM pass is older source `70b2375`. |
| Application ID | `de.schuelken.cloudbridge` | `app/build.gradle`; debug variants append `.debug`. Existing signing continuity is not established. |
| App version defaults | `1.0.1`, versionCode `20` before ABI offsets | `app/build.gradle`; environment overrides exist. Not approved as a release version. |
| Minimum / compile / target SDK | 23 / 36 / 36 | `app/build.gradle`; declaration is not proof of API/device acceptance. |
| Flavors / ABIs | `oss`, `rs`; `armeabi-v7a`, `arm64-v8a`, `x86`, `x86_64`, universal APK | `app/build.gradle`; no current APK or ABI inspection. |
| Gradle / Java | Wrapper 8.13; checked-in Android workflow uses Temurin JDK 17 | Local verification used Temurin JDK 21.0.8. |
| Go / NDK | Minimum Go 1.26.0; NDK 29.0.14206865 | `gradle.properties`; local rclone checks used Go 1.26.8 on Windows/amd64. |
| Database schema | SQLite version 16 | `Database/DatabaseInfo.kt`; checked-in v16 source includes app-side Bisync preflight/preview and partial backup-manifest records. A production mutation gate, complete restore/recovery contract, supported source-to-target migration/rollback matrix, and on-device migration result are absent; instrumentation is authored but **NOT RUN**. |

## rclone engine pin and dependency evidence

| Field | Current value | Evidence / limitation |
|---|---|---|
| Engine repository | `https://github.com/Rareities/rclone.git` | Configured in `gradle.properties`. |
| App immutable engine ref | `fe775a8b58cf217fdf4bd34f0975af1e4c19c1a0` | Configured in `gradle.properties`; `app/build.gradle` rejects a ref that is not a full 40-character SHA. Clean detached source checkout evidence exists. This does not establish Android-native or APK integration. |
| Declared engine version | `1.76.0` | Build property in `gradle.properties`. No release compatibility claim is made from the property alone; verify the built binary’s version/ref before release. |
| Rareities/rclone default branch | `master` at `1583cce1e28340e5d064ed955179f5f2b31e7757` in the 2026-09-25 read-only refresh | Refresh returned no open PR and no combined status for the observed head. The workflow-run lookup covered PR-triggered runs only and returned none; push/manual-dispatch runs were not established. Release listing was unavailable in that refresh, so published-release status is **NOT VERIFIED** here. |
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
| Backup/restore foundation | `Database/BisyncBackupManifestRepository.kt`, `DatabaseInfo.kt` schema v16, `BisyncBackupManifestRepositoryTest.kt` | DB-only pending-manifest foundation. Production caller, provider reservation, exact-byte verification, restart reconciliation, complete restore, and mutation-boundary enforcement remain missing/open. Keep initialization, apply, and recovery unavailable. |
| Native lock slice | `cmd/bisync/lockfile_test.go`; 11 top-level lock tests ×100 at exact pin, Windows/amd64 | Partial evidence only. Separate-process renewal/expiry, stale release, corruption/clock cases, Android/Linux behavior, integrated app/native races, and WP14 acceptance remain **NOT RUN**. |

## Runtime/support acceptance

| Area | Current status |
|---|---|
| Android JVM | Last full pass: 130 tests across 24 suites, one platform-capability skip, on source `70b2375`; native checkout/build excluded. On source `e6097f1`, `:app:mergeOssDebugResources` and `:app:compileOssDebugKotlin` passed; standalone `TriggerDispatchPolicyTest` and `ScheduledTriggerExecutionPolicyTest` each passed 4/4. Current full Java/JUnit compilation is **BLOCKED / NOT PASSED before JUnit** by cached transformed-JAR `AccessDeniedException`; downstream missing symbols do not establish a source defect. Trigger model-copy test, Android instrumentation, integrated worker tests, and current-source full JVM acceptance are **NOT RUN / NOT ESTABLISHED**. |
| Android instrumentation | **NOT RUN** in this verification; authored Bisync migration/repository tests did not execute. |
| Actual acceptance device | **NOT RUN**. Required target: Galaxy S26 on One UI 8.5/9; actual Android API and firmware must be recorded during testing. |
| Proton Drive | Live provider/official-client acceptance **NOT RUN**; no credentials or verified unique disposable scope. `Proton:RoundSync-Test` is a proposed area, not permission to delete or overwrite it. |
| Obsidian | No explicit integration/entry point identified in current app source; **NOT VERIFIED**, not a supported behavior claim. |
| Production signing identity | **UNKNOWN / NOT VERIFIED**. Release packaging is fail-closed without a production key; no certificate-continuity evidence is present. |
| APK / ABI | No current native Android checkout/build, APK, certificate inspection, packaged ABI/hash inspection, or artifact provenance. |
| CI / PR / release snapshot | 2026-09-25 read-only GitHub refresh returned no open PRs and no combined status checks for the observed CloudBridge/rclone default heads or exact app-pinned engine SHA. PR-triggered workflow-run lookup returned none; push/manual workflow runs were not covered by that lookup. The checked-in `.github/workflows/android.yml` runs OSS debug unit tests and packages a debug APK on push-to-master, PR-to-master, or manual dispatch; it does not upload an artifact or publish a release. No release-list query was available in the refresh, so current published-release status is **NOT VERIFIED**. Default branches were reported unprotected at refresh. No APK was built in the local JVM verification. |

This manifest is a traceability aid, not authorization to publish, not an ABI/API/provider compatibility guarantee, and not a substitute for WP14 migration/device testing or WP15 release review.
