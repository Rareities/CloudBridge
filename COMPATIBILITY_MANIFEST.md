# Compatibility manifest

**Status:** engineering snapshot only, not a supported-release contract. **Checked:** 2026-09-25. Refresh before app pin promotion, PR, build provenance, or release.

## Source and build identity

| Field | Current value | Evidence / limitation |
|---|---|---|
| CloudBridge repository | `https://github.com/Rareities/CloudBridge` | Public fork; default branch `master` was `c492876258ca841232229249519abe92ff77c3a4` at refresh. |
| Current local app branch | `codex/luna-implementation` | Latest code/test snapshot `d38f6a3` is a WP08 instrumentation-test follow-up to production source `193d972` (which includes WP08 `2c302158`, WP12 `af638f3`, prior updater commits `d715e16`/`d9e130a`, and the WP12 locale-format fix); later commits in this checkpoint are documentation-only. This is not a published commit or APK. The last full Android JVM pass is older source `70b2375`; current-source JVM/JUnit acceptance has not been established. |
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
| Android JVM | Last full pass: 130 tests across 24 suites, 1 platform-capability skip, on source `70b2375`; native checkout/build excluded. Current Kotlin production compilation passes on source `193d972`; the later `d38f6a3` change only strengthens an instrumentation test. Focused JVM and Android-test compilation fail during main Java compilation before JUnit/test compilation because javac cannot access the cached transformed `viewbinding-8.13.2-api.jar`; current-source JVM/JUnit acceptance is not established. |
| Android instrumentation | **NOT RUN** in this verification. |
| Actual acceptance device | **NOT RUN**. Required target: Galaxy S26 on One UI 8.5/9, with actual Android API and firmware recorded. |
| Proton Drive | **NOT RUN** live; only local library tests on separate candidate checkouts are recorded. |
| Obsidian | **NOT VERIFIED** in app source/entry points; no supported integration claim. |
| Production signing identity | **UNKNOWN / NOT VERIFIED**. Release packaging is fail-closed without a production key; no certificate-continuity evidence is present. |
| CI/release artifacts | No GitHub Actions runs, releases, or open PRs were observed on 2026-09-25. Default branches were unprotected. No APK was built in the local JVM verification. |

This manifest is a traceability aid, not authorization to publish, not an ABI/API compatibility guarantee, and not a substitute for WP14 migration/device testing or WP15 release review.
