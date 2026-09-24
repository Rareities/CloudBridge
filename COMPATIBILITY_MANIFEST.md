# Compatibility manifest

**Status:** engineering snapshot only, not a supported-release contract. **Checked:** 2026-09-25. Refresh before app pin promotion, PR, build provenance, or release.

## Source and build identity

| Field | Current value | Evidence / limitation |
|---|---|---|
| CloudBridge repository | `https://github.com/Rareities/CloudBridge` | Public fork; default branch `master` was `c492876258ca841232229249519abe92ff77c3a4` at refresh. |
| Current local app branch | `codex/luna-implementation` | Current source checkpoint `e6097f1dc4f7fba87440359039c84196d250c922` adds WP10 trigger-state/alarm/follow-up guards and a WP12 trigger-copy identity/persistence fix, on top of WP08 `2c302158`, WP12 `af638f3`, WP13 `193d972`, and earlier docs/resource changes. This local source commit is not published and has no APK. The current docs record this source/test state; user-owned `.android/` remains untouched and unstaged. The last full Android JVM pass is older source `70b2375`. |
| Application ID | `de.schuelken.cloudbridge` | Debug variants append `.debug`. Existing signing continuity is not established. |
| App version defaults | `1.0.1`, versionCode `20` before ABI offsets | `app/build.gradle`; environment overrides exist. Not approved as a release version. |
| Minimum / compile / target SDK | 23 / 36 / 36 | `app/build.gradle`; API support does not imply device acceptance. |
| Flavors / ABIs | `oss`, `rs`; armeabi-v7a, arm64-v8a, x86, x86_64, universal | Gradle config; this turn did not build an APK. |
| Gradle / CI Java | Gradle wrapper 8.13; CI Temurin JDK 17 | Local JVM evidence this turn used Temurin JDK 21.0.8. |
| Go / NDK | Minimum Go 1.26.0; NDK 29.0.14206865 | `gradle.properties`; local rclone tests used Go 1.26.8 Windows/amd64. |
| Database schema | SQLite version 16 in the current worktree | `DatabaseInfo.DATABASE_VERSION`; v16 adds only the partial WP08 backup-manifest foundation. A full supported source-to-target migration/rollback matrix and on-device migration test remain pending WP14; migration test source is authored but **NOT RUN**. |

## rclone engine pin and Proton dependency decision

| Field | Current value | Status |
|---|---|---|
| Engine repository | `https://github.com/Rareities/rclone.git` | Configured in `gradle.properties`. |
| App immutable engine ref | `fe775a8b58cf217fdf4bd34f0975af1e4c19c1a0` | Current configured pin; a separate clean detached source checkout passed focused Bisync/ProtonDrive tests, repeated preservation regressions, scoped vet and full Go build. No Android native checkout/build or APK integration was performed. |
| Declared rclone version | `1.76.0` | Build property; verify against the pinned source before release. |
| Rareities/rclone `master` | `1583cce1e28340e5d064ed955179f5f2b31e7757` | Refreshed 2026-09-25; branch unprotected, no Actions runs/open PRs/releases observed. |
| Proton library pins in last WP09 snapshot | Proton-API-Bridge `v1.0.5`; go-proton-api `v1.0.4`; gopenpgp `v3.4.1` | A clean local v1.0.5 Proton API Bridge candidate now has isolated source/test commits; it is not promoted into the app pin or live-provider acceptance. Refresh all dependencies before any promotion. |

The application pin is intentionally distinct from both local rclone worktrees. Do not infer that tests on `rclone-history` or `rclone-preview-state` validate `fe775a8`, and do not promote the broad local preview-state branch as one patch.

## Runtime/support acceptance

| Area | Current status |
|---|---|
| Android JVM | Last full pass: 130 tests across 24 suites, 1 platform-capability skip, on source `70b2375`; native checkout/build excluded. On worktree content subsequently committed as `e6097f1`, `:app:mergeOssDebugResources` and `:app:compileOssDebugKotlin` pass; standalone `TriggerDispatchPolicyTest` and `ScheduledTriggerExecutionPolicyTest` each report 4/4. Current full Java/JUnit compilation remains **BLOCKED / NOT PASSED before JUnit** by `AccessDeniedException` for transformed `viewbinding-8.13.2-api.jar`; dependent missing symbols are not proof of a source defect. Trigger model-copy test, Android instrumentation and integrated worker tests are **NOT RUN**. Current-source Android JVM acceptance is not established. |
| Android instrumentation | **NOT RUN** in this verification. |
| Native lock regression subset | Exact app-pinned rclone revision `fe775a8b58cf217fdf4bd34f0975af1e4c19c1a0`; all 11 existing `TestLockfile*` functions passed 100 repetitions on Go 1.26.8 Windows/amd64 in temporary local fixtures. Two tests use separate OS processes for active-owner exclusion and crash recovery; other cases are in-process. Separate-process renewal/expiry/stale-release/corruption/clock coverage, Android/Linux runtime and integrated acceptance remain **NOT RUN**. |
| Actual acceptance device | **NOT RUN**. Required target: Galaxy S26 on One UI 8.5/9, with actual Android API and firmware recorded. |
| Proton Drive | **NOT RUN** live; only local library tests on separate candidate checkouts are recorded. |
| Obsidian | **NOT VERIFIED** in app source/entry points; no supported integration claim. |
| Production signing identity | **UNKNOWN / NOT VERIFIED**. Release packaging is fail-closed without a production key; no certificate-continuity evidence is present. |
| CI/release artifacts | No GitHub Actions runs, releases, or open PRs were observed on 2026-09-25. Default branches were unprotected. No APK was built in the local JVM verification. |

This manifest is a traceability aid, not authorization to publish, not an ABI/API compatibility guarantee, and not a substitute for WP14 migration/device testing or WP15 release review.
