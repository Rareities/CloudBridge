# CloudBridge + Rareities/rclone release readiness

**Decision as of 2026-09-26: NO-GO - no release candidate.** OSS debug APKs were built
and inspected, but no production-signed release APK or signing identity/artifact
provenance is established, and core safety/provider/device gates remain open. This is
not a release approval request.

**Current build addendum (2026-09-26):** the current dirty CloudBridge source tree now has a
verified four-ABI native build and five signature-verified OSS debug APKs from the immutable
Rareities/rclone app pin. The APKs use an isolated disposable debug certificate; this is not the
production signer. The current APK's embedded native-library hashes match the freshly built
libraries. Lint passed with the existing baseline (which still suppresses 2 errors and 422
warnings). Current-source JVM XML reports 289 tests per flavor; the packaging invocation did
not rerun them. Instrumentation, Galaxy S26/actual firmware, live Proton, production signing,
hosted CI, and release provenance remain **NOT RUN**. This debug APK is not a release candidate.

## Gate status

| Gate | Status | Evidence / blocker |
|---|---|---|
| Repository, PR and CI refresh | **PARTIAL / refreshed** | Read-only GitHub connector refresh on 2026-09-26: CloudBridge master `c492876258ca841232229249519abe92ff77c3a4`; rclone master `1583cce1e28340e5d064ed955179f5f2b31e7757`; no CloudBridge PR entries and rclone PR #1 remains closed/unmerged. Combined status and PR-triggered workflow queries returned no entries for the checked heads. This connector does not provide a complete workflow-history or release inventory; release existence is **NOT VERIFIED**. Branch protection was not checked in this refresh. Local work is not a GitHub PR. |
| Standalone rclone baseline | **PARTIAL** | Recorded Windows/amd64 Go 1.26.8 build, Bisync/ProtonDrive tests and Android/arm64 cross-build exist on local worktree source. Broader historical go test ./... was not clean; race/Linux/Android runtime and live Proton remain **NOT RUN**. |
| App engine link/provenance | **PARTIAL** | Gradle config pins https://github.com/Rareities/rclone.git at immutable `fe775a8b58cf217fdf4bd34f0975af1e4c19c1a0`. The exact detached source pin now passes focused Bisync/ProtonDrive tests, three repeated preservation regressions, scoped vet, and `go build ./...`; broad `go test ./...` was not clean. This is not APK/ABI/runtime integration or release provenance. |
| WP08 Bisync preview/init/recovery | **OPEN / BLOCKING** | Canonical snapshot parser and read-only preview components have local tests. A fail-closed command-mode fence at both audited native launch paths permits only app-owned inspection/preview forms; current-source OSS and RS JVM XML reports 289 tests per flavor with 2 Windows symlink-capability skips. Source commit `2c302158` adds schema-v16 paired DB manifest metadata and schema v17 adds run-filter snapshots; strict preview-after-preflight ordering and cross-record checks exist, but there is no production caller or required pre-mutation gate. Android-test compilation is source-only; instrumentation is **NOT RUN**. Provider reservation/verification, restart reconciliation, exact restore, UI-verifiable user confirmation and mutation-boundary authorization are absent. Do not enable initialization/apply/recovery. |
| WP09 Proton | **PARTIAL / BLOCKING** | Backend/dependency review and local candidate tests exist. With the exact CloudBridge rclone pin and the candidate library selected through a temporary Go workspace, `go test ./backend/protondrive` and scoped `go vet` passed. Promotion, broader compatible coverage and live disposable-vault official-client verification remain open. Proton access is **NOT RUN**. |
| WP10 scheduling | **PARTIAL / BLOCKING** | Source commit `e6097f1` adds serialized current-process trigger mutation/admission, stale-alarm snapshot rejection, trigger metadata on scheduled follow-ups, cancellation propagation and interval overflow/disable fixes. Later source uses the full `long` trigger ID in new PendingIntent data-URI identities and cancels legacy request-code identities after reconciliation; its pure identity policy passes 2/2, but Android lifecycle/migration tests are **NOT RUN**. Bulk trigger reconciliation uses its locked snapshot, avoids one DB lookup per trigger, and serializes schedule-only refresh. MainActivity now records denied exact-alarm permission and retries only scheduled triggers on foreground resume; the service reports incomplete passes and the marker clears only if every schedule is armed/no-op and permission remains granted. Its pure retry/clear policy passes 4/4. An older JDK 21 app-Javac attempt was blocked by transformed-archive access denial; the later 2026-09-26 JDK 17 build compiled current OSS/RS production sources and current-source JVM XML reports 289 tests per flavor. Android settings/AlarmManager integration is still **NOT RUN**. This does not cover background permission grants, process death with no later wake-up, or the separate import/database-to-AlarmManager failure window. `BootReceiver` requeues all trigger types after `BOOT_COMPLETED`, but interval restart/missed-run behavior is not device-tested. Legacy manual and scheduled WorkManager requests cannot be selectively distinguished: do not automatically cancel/reject ambiguous pending work without a product decision, because that could cancel manual syncs; stale scheduled work may consequently still run. Trigger-generation reuse, durable coalescing/missed-run policy, platform quota/permission outcomes and device lifecycle evidence remain open. |
| WP11 Bisync/Obsidian UI | **NOT COMPLETE / BLOCKING** | Preview/review UI is not full initialization/conflict/recovery or Obsidian acceptance. No explicit supported Obsidian integration was located in reviewed app paths. |
| WP12 retained features | **PARTIAL / OPEN** | FEATURE_SURVIVAL_MATRIX.md remains an inventory, not regression acceptance. Current-source OSS/RS JVM XML reports 289 tests per flavor, including current VCP/cache/completion regressions; this does not establish device SAF/grant behavior or complete feature survival. Current source validates before logging, disambiguates the API 36 overload, requires terminal RCD success before reporting mutation success, and retains source grants on failure/unknown. Delayed-delete target/dismissal and immutable remote-delete snapshot policies have focused coverage. SAF/VCP device behavior, transfers, serving, Internxt/Drime interoperability, accessibility/locales and other matrix rows still need owning tests. |
| WP13 signing/build/docs/CI | **PARTIAL / BLOCKING** | Release signing now requires a valid `CB_SIGNING_CERT_SHA256` and verifies the actual configured X.509 certificate before packaging; Gradle policy assertions pass, missing signer and synthetic-certificate mismatch are rejected, and a disposable synthetic certificate matched successfully. No real production key, approved fingerprint, certificate continuity, confirmed CI, dependency/security closure or release artifact exists. The no-auto-publish workflow slice remains. The optional updater targets Rareities and routes notices to the validated release tag. Source commit `193d972` uses bounded scanning, response limits, cancellable OkHttp calls and a timeout; latest OSS/RS updater unit suites each pass 10/10 policy and 11/11 scanner cases. Android worker/preferences/notification/device integration remains **NOT RUN**. OSS debug APKs are now built and inspected; the latest GitHub refresh returned an empty release inventory for both forks. |
| WP14 stress/device/provider acceptance; upstream migration gates remain prerequisites | **ACCEPTANCE NOT RUN / BLOCKING** | Core preconditions are unmet. All 11 existing exact-app-pin rclone `TestLockfile*` functions passed 100 repetitions on Go 1.26.8 Windows/amd64; two exercise separate processes for active-owner exclusion and crash recovery. A separate test-only candidate now passes the full cmd/bisync package but is not an app-pin change or WP14 acceptance. Other process-lock scenarios, integrated barriers, eight-hour soak/resource trends, Galaxy S26 model/API/firmware, and verified disposable Proton/official-client/second-client sequence remain unestablished. Separately, WP03/WP04/WP08/WP13 migration and identity prerequisites are **NOT RUN / NOT ACCEPTED**: no signed source-to-target install path is accepted, and the six synthetic schema fixtures are not historical release evidence; v13/v14 Android-test sources now compile but have not run. See COMPATIBILITY_MANIFEST.md. |
| WP15 final audit and handoff | **PARTIAL PREPARATION / NOT COMPLETE** | The current security/reliability plan and evidence are being reconciled, not finalized. Final finding/requirements closure, PR/CI review, clean integrated build/artifact provenance, rollback record and concrete human release decision remain gated. |

WP03 follow-up (2026-09-25): transaction cleanup was hardened and an isolated late-import rollback instrumentation test was added. OSS/RS production Kotlin compiles, but app Java/test-source compilation and instrumentation did not run; migration, fault-recovery, device, and release gates remain open.

## Artifact and identity record

- APK/AAB: OSS **debug-only** ABI splits and universal APK produced; RS APK packaging is **NOT BUILT** after the Java compiler/archive-access failure.
- Artifact SHA-256: OSS debug APK hashes are recorded in `TEST_REPORT.md`; these are not release-candidate artifacts.
- Application ID/version/channel: source defaults are in COMPATIBILITY_MANIFEST.md;
  no release values are approved.
- Signing certificate fingerprint/continuity: **UNKNOWN / NOT VERIFIED** for any production identity.
- Signing guard: release packaging requires `CB_SIGNING_CERT_SHA256` and checks the actual alias certificate. Policy plus disposable-key match/mismatch checks passed; this does not establish an approved production fingerprint, private-key custody, signer continuity, or release artifact.
- Native rclone engine pin: `fe775a8b58cf217fdf4bd34f0975af1e4c19c1a0`, source tree `68df728c1eb3cd1e60340286cd99548e6d0d0452`; all four ABI build tasks passed from the verified local checkout. Packaged native bytes match the four generated `app/lib` binaries by SHA-256; runtime version metadata, reproducible-build comparison, and on-device loading are **NOT RUN**.
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
