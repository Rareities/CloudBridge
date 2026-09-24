# CloudBridge + Rareities/rclone test report

**Snapshot:** 2026-09-25. **Overall status:** PARTIAL; this is not release acceptance.
Per-package execution ledgers contain the full 13-field work-package records and must be
read alongside this summary.

## Tested revisions and results

| Project / scope | Revision and environment | Command or test | Result |
|---|---|---|---|
| CloudBridge JVM suite | Source fix 70b2375f6d8cce4d65433b9658f7e0a6dcbf1809; Temurin JDK 21.0.8, Gradle 8.13, Android SDK 36; isolated workspace Gradle home, offline dependencies and task-local build-tools 36 override | Gradle task :app:testOssDebugUnitTest; :rclone:checkoutRclone and :rclone:buildAll excluded | **PASS** — 24 suites, 130 tests, one platform-capability skip, zero failures/errors. App Kotlin/Java and unit-test Kotlin/Java compiled. |
| CloudBridge focused regression | Same source revision and environment | EndpointSnapshotCodecTest | **PASS** - canonical delimiter parsing and a future-schema fixture that actually changes the schema value. |
| WP08 backup-placement shape policy | Source commit 2557a835918ad8c3d245d0875abcdcaa6be64561; app Kotlin classes compiled by Gradle on JDK 21.0.8 / Gradle 8.13 / SDK 36 | Standalone Kotlin K2 compiler 1.9.22 compile of BisyncBackupPlacementTest, then JUnitCore 4.13.2 | **PASS** - 4 pure tests. Structural identity/account/overlap rules only; mutation permission remains hard-coded false. A full Gradle unit-task retry failed before JUnit in javac while closing Android SDK core JARs; see the execution ledger. |
| App Java/Kotlin linkage | Same source revision | Included in full JVM task | **PASS** — TriggerService.java resolves the Kotlin ScheduleTimeCalculator static API. |
| Standalone rclone active worktree | Source 894298219d4f3798b5507fee5f457464bab6547c; ledger-only follow-up brought active HEAD to a881c6ac9bfd9509ce8ca8a93cdb51997ee45fac; Go 1.26.8 Windows/amd64, offline modules and disposable local fixtures | TestBisyncBackupDirReuseReplacesExistingPreservedPath (20 repetitions); go test -mod=readonly -count=1 ./cmd/bisync ./backend/protondrive; go vet -mod=readonly ./cmd/bisync ./backend/protondrive; go build -mod=readonly ./...; Android/arm64 Bisync cross-build | **PASS** for the recorded local source and package scope. These worktrees are not the app pin. Detailed evidence is in the task-level execution ledger and the rclone worktree PATCH_LEDGER.md, which is outside this repository. |
| Standalone rclone preview-state CLI candidate | Source commit 645848e4a1d58fa54106b1a4eae23f14490b28a9; ledger follow-up 4f2af750aae33837864bcbf837c83e03cce3c790; local broad branch `codex/luna-preview-isolation`, not the app pin; Go 1.26.8 Windows/amd64, offline modules, short task temp root | Focused preview-state tests `-count=20`; full `go test -mod=readonly -count=1 ./cmd/bisync ./backend/protondrive`; `go vet` for both; `go build -mod=readonly ./...`; gofmt and `git diff --check` | **PASS** for this local WP08 CLI boundary. Blank explicit state sources now fail closed; accepted-source listing bytes are copied into a separate preview workdir. Go emitted an access-denied module stat-cache warning outside the workspace; tests/build exited 0 using cached modules. Earlier long-temp run hit a Windows path-length failure and was rerun successfully with the short task root. No app pin/PR/runtime/provider/device acceptance. |
| rclone broader suite | Verified Rareities archive baseline / earlier local checkpoints | go test ./... | **INCOMPLETE / NOT A CLEAN PASS** — a prior run encountered missing Windows test-server scripts, symlink privilege failures and a WebDAV range test failure. Focused package results do not establish full-suite acceptance. |
| Diff hygiene | Recorded CloudBridge checkpoints | git diff --check | **PASS** for the recorded checkpoints. |

## Not run or not established

- Full Android Gradle build with the pinned native rclone checkout, all ABIs, merged-manifest and artifact inspection: **NOT RUN**. The passing JVM task explicitly excluded native checkout/build.
- APK packaging, instrumentation, current-source lint, R8/release build, signing/certificate continuity, artifact hashes and reproducible artifact comparison: **NOT RUN**.
- Tests against the app's immutable engine pin fe775a8b58cf217fdf4bd34f0975af1e4c19c1a0: **NOT RUN** by the JVM suite. Tests on rclone-history or rclone-preview-state do not validate that pin.
- Go race detector: **NOT RUN** (CGO_ENABLED=0; no GCC toolchain in the recorded engine environment).
- Android/Linux native runtime and multi-process lock acceptance: **NOT RUN**.
- Galaxy S26 / One UI 8.5/9: **NOT RUN**; no actual model, Android API, firmware, battery settings or device artifact certificate were recorded.
- Live Proton Drive: **NOT RUN**; no credentials or verified unique disposable area. Proton:RoundSync-Test is not deletion authorization.
- CI/Actions and published artifacts: **NOT RUN**. The 2026-09-25 GitHub refresh observed no workflow runs, open PRs or releases for the two Rareities repositories.
- Full `:app:testOssDebugUnitTest` on source commit 2557a83: **NOT PASSED**. Gradle Kotlin compilation completed, but Java compilation failed before JUnit because JDK ZipFS could not close Android SDK `core-for-system-modules.jar` / `core-lambda-stubs.jar`; the 4 focused policy tests were run separately and passed as recorded above.

## Interpretation and next tests

The JVM pass closes the specific app compilation/test defects in EXECUTION_LEDGER.md;
it does not close WP08, WP09 or WP10. WP08 still requires endpoint-specific backup
placement, a durable run-owned manifest/reservation, restart reconciliation, exact-byte
restore and mutation-boundary revalidation. WP09 still requires independent
dependency/library closure and live disposable-vault acceptance. WP10 still requires the
persisted global dispatch/coalescing/missed-run policy and device lifecycle acceptance.
Do not enable Bisync initialization/apply/recovery or claim release readiness from these
test results. The new structural placement evaluator does not alter that boundary.

## Evidence locations

- CloudBridge 13-field records and setup limitations: EXECUTION_LEDGER.md.
- CloudBridge source change index: PATCH_LEDGER.md.
- Standalone engine evidence: task-level execution ledger and the rclone worktree PATCH_LEDGER.md; the latter is a separate workspace checkout, not a file in this repository.
- Current scope/status maps: REQUIREMENTS_MATRIX.md, FEATURE_SURVIVAL_MATRIX.md and COMPATIBILITY_MANIFEST.md.
