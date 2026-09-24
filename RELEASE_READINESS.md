# CloudBridge + Rareities/rclone release readiness

**Decision as of 2026-09-25: NO-GO — no release candidate.** No APK was built in the
current verification, no production signing identity/artifact provenance is established,
and core safety/provider/device gates remain open. This is not a release approval request.

## Gate status

| Gate | Status | Evidence / blocker |
|---|---|---|
| Repository, PR and CI refresh | **PARTIAL / refreshed** | Read-only GitHub snapshot on 2026-09-25: CloudBridge master c492876258ca841232229249519abe92ff77c3a4; rclone master 1583cce1e28340e5d064ed955179f5f2b31e7757; no open PRs, observed Actions runs or releases; default branches unprotected. Local work is not a GitHub PR. |
| Standalone rclone baseline | **PARTIAL** | Recorded Windows/amd64 Go 1.26.8 build, Bisync/ProtonDrive tests and Android/arm64 cross-build exist on local worktree source. Broader historical go test ./... was not clean; race/Linux/Android runtime and live Proton remain **NOT RUN**. |
| App engine link/provenance | **PARTIAL** | Gradle config pins https://github.com/Rareities/rclone.git at immutable `fe775a8b58cf217fdf4bd34f0975af1e4c19c1a0`. The exact detached source pin now passes focused Bisync/ProtonDrive tests, three repeated preservation regressions, scoped vet, and `go build ./...`; broad `go test ./...` was not clean. This is not APK/ABI/runtime integration or release provenance. |
| WP08 Bisync preview/init/recovery | **OPEN / BLOCKING** | Canonical snapshot parser and read-only preview components have local tests. Source commit `2c302158` adds schema-v16 paired DB manifest metadata, strict preview-after-preflight ordering, a safety-observation digest, and cross-record backup/sync-scope checks; creation is limited to `PREFLIGHT`, but no production caller or required pre-mutation gate exists. Kotlin compiles, but migration/repository instrumentation is **NOT RUN**. Provider reservation/verification, restart reconciliation, exact restore, UI-verifiable user confirmation and mutation-boundary authorization are absent. Do not enable initialization/apply/recovery. |
| WP09 Proton | **PARTIAL / BLOCKING** | Backend/dependency review and local candidate tests exist. With the exact CloudBridge rclone pin and the candidate library selected through a temporary Go workspace, `go test ./backend/protondrive` and scoped `go vet` passed. Promotion, broader compatible coverage and live disposable-vault official-client verification remain open. Proton access is **NOT RUN**. |
| WP10 scheduling | **PARTIAL / BLOCKING** | Source commit `e6097f1` adds serialized current-process trigger mutation/admission, stale-alarm snapshot rejection, trigger metadata on scheduled follow-ups, cancellation propagation and interval overflow/disable fixes. `BootReceiver` requeues all trigger types after `BOOT_COMPLETED`, but interval restart/missed-run behavior is not device-tested. Resource merge/Kotlin production compilation and both standalone policy suites (4/4 each) pass; full Android Java/JUnit is blocked before JUnit by cached transformed `viewbinding` archive access. A historical audit confirms legacy manual and scheduled WorkManager requests cannot be selectively distinguished: do not automatically cancel/reject ambiguous pending work without a product decision, because that could cancel manual syncs; stale scheduled work may consequently still run. Trigger-generation reuse, PendingIntent ID narrowing, persisted coalescing/missed-run policy, platform quota/permission outcomes and device lifecycle evidence remain open. |
| WP11 Bisync/Obsidian UI | **NOT COMPLETE / BLOCKING** | Preview/review UI is not full initialization/conflict/recovery or Obsidian acceptance. No explicit supported Obsidian integration was located in reviewed app paths. |
| WP12 retained features | **PARTIAL / OPEN** | FEATURE_SURVIVAL_MATRIX.md remains an inventory, not regression acceptance. Source commit `af638f3` validates canonical document IDs, child containment and remove-parent identity; four standalone pure ID-policy tests passed, but new public VCP tests are **NOT RUN**. SAF/VCP authorization, transfers, serving, Internxt/Drime, accessibility/locales and other rows still need owning tests. |
| WP13 signing/build/docs/CI | **PARTIAL / BLOCKING** | Fail-closed production-signing check and no-auto-publish workflow slice are recorded. The optional updater targets Rareities and routes notices to the validated release tag. Source commit `193d972` uses bounded scanning, response limits, cancellable OkHttp calls and a timeout; `:app:compileOssDebugKotlin` passed on that source snapshot, while scanner/worker tests are **NOT RUN**. Targeted JUnit is blocked in javac before tests on cached transformed-JAR access. No real production key, CI run, native APK, signer continuity, dependency/security closure or artifact inspection. No verified fork release candidate was observed in the 2026-09-25 refresh. |
| WP14 stress/migration/device/provider acceptance | **ACCEPTANCE NOT RUN / BLOCKING** | Core preconditions are unmet. All 11 existing exact-app-pin rclone `TestLockfile*` functions passed 100 repetitions on Go 1.26.8 Windows/amd64; two exercise separate processes for active-owner exclusion and crash recovery. Other separate-process scenarios, full migration/rollback matrix, real-engine data matrix, per-critical-app-race barrier evidence, eight-hour soak/resource trends, Galaxy S26 model/API/firmware run, and verified disposable Proton/official-client/second-client sequence remain unestablished. Emulator or Windows lock evidence does not substitute for the named device/integrated gates. |
| WP15 final audit and handoff | **PARTIAL PREPARATION / NOT COMPLETE** | The current security/reliability plan and evidence are being reconciled, not finalized. Final finding/requirements closure, PR/CI review, clean integrated build/artifact provenance, rollback record and concrete human release decision remain gated. |

## Artifact and identity record

- APK/AAB: **none produced** in the current verification.
- Artifact SHA-256: **not applicable; no candidate artifact**.
- Application ID/version/channel: source defaults are in COMPATIBILITY_MANIFEST.md;
  no release values are approved.
- Signing certificate fingerprint/continuity: **UNKNOWN / NOT VERIFIED**.
- Native rclone binary version/hash in an app artifact: **NOT RUN**.
- Known-good production rollback artifact/tag: **none established**. A debug build or
  source commit is not a production rollback artifact.
- PRs: none opened by this work. Do not push archive-import/local-only ancestry as if it
  were refreshed GitHub history; first establish the intended reviewable branch base.

## Conditions before reconsidering

1. Close WP08 preservation, exact-restore and mutation-boundary gates with deterministic
   tests and a safe explicit user workflow.
2. Close WP09 at the independent backend/library layer; review dependencies and test the
   exact immutable app engine revision before integration. Obtain verified disposable
   Proton access or keep release blocked.
3. Finish WP10 and WP11, including honest scheduler behavior and a tested required Obsidian
   workflow; preserve manual alternatives and do not claim unknown foreground state is
   closed.
4. Complete WP12 survival regressions, WP14 migration/fault/stress/device/provider gates,
   with actual Galaxy S26 model, Android API and firmware recorded.
5. Complete WP13 independent CI, dependency/license/advisory review, merged manifest and
   ABI/native inspection, production signer continuity, provenance and safe diagnostics.
6. Complete WP15 finding/requirements reconciliation, fresh repository/PR/CI refresh,
   rollback record and a human release decision. No build job alone may auto-publish.

## Safe interim boundary

Continue local disposable-data implementation and independent tests. Keep the engine pin
immutable and never promote the broad rclone-preview-state branch as a whole. Keep
Samsung and Proton unavailable access recorded as **NOT RUN**. Do not request, store or
expose production signing secrets in repository files or this report. No push, PR, APK
publication or release is authorized by this NO-GO snapshot.
