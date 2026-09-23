# CloudBridge + rclone requirements matrix

Initial WP00 matrix from `CloudBridge-rclone-Luna-Master-Handoff.md`. A row is complete only
when its owning package, independent tests, integration tests and acceptance evidence are all
recorded in the execution ledger.

| Requirement | Owning layer | Package(s) | WP00 status | Completion evidence required |
|---|---|---|---|---|
| Safe first-class Bisync | rclone plus app orchestration | WP01, WP03–WP08, WP13 | PARTIAL / WP08 parser, owner-bound command adapter, durable worker foundation, and native isolated-baseline primitive PASS locally | Successful native dry-run scratch no longer invalidates accepted state; reinitialization preserves the prior accepted baseline. CloudBridge pins published `fe775a8…`. Local unpublished native follow-up `81ac481705944ac125e2f8eeab823d78f6b1cfdb` adds guarded byte-exact baseline cloning and `--preview-state-from`. CloudBridge now has durable queue/worker wiring and fresh-preflight admission, but no user-facing preview/review UI or mutation-boundary revalidation; compatible-state preview remains fail-closed on the published app pin. Durable backups/restore, init/recovery and device/provider acceptance remain open |
| Proton Drive / Obsidian workflow | rclone backend/dependencies plus app | WP01, WP09–WP10, WP14 | NOT STARTED | Native compatibility tests, disposable Proton tests, sync-before-open and lifecycle tests |
| Durable scheduling and duplicate prevention | CloudBridge | WP03–WP08, WP12 | PARTIAL / WP04 owner boundary plus WP08 preview scheduler/worker | Durable profile/run claim, preview queue owner, unique WorkManager dispatch, and owner-token terminal transitions are implemented; instrumented scheduling/cancel/process-death runs, normal-sync scheduling and lock/reboot evidence remain |
| CloudBridge feature retention | CloudBridge | WP03–WP15 | NOT STARTED | Named provider, transfer, browse, share, move/delete and serving regressions |
| Independent rclone health | Rareities/rclone | WP00–WP02, WP09 | PARTIAL / WP08 native follow-up validated locally | Go 1.26 full `cmd/bisync` tests, package vet, full `go build ./...`, fresh CLI validation smoke and Android/arm64 package cross-build pass for local commit `81ac481…`; source is not yet published and broader Windows-dependent/provider sweep remains separate |
| Independent Android health | CloudBridge | WP00, WP03-WP08, WP12-WP15 | PARTIAL / current v15+worker snapshot: 92 JVM tests + instrumentation-source compile + lint PASS | JDK17.0.20.1 / Gradle 8.13: 92 tests, 0 failures/errors, 1 existing Windows capability skip. Android-test sources compile but instrumentation is not executed. Lint task passes with 94 visible warnings; the existing baseline filters 2 errors and 428 warnings and contains 76 stale entries. The previously verified debug APK predates DB v14/v15, preview-command and worker changes and is not evidence for final integration; rebuild after UI/revalidation work is complete |
| CloudBridge links to Rareities/rclone | CloudBridge build integration | WP01, WP08, WP15 | Existing published pin/4-ABI debug PASS; local preview-state follow-up not integrated | Current app URL/ref remain `https://github.com/Rareities/rclone.git` / `fe775a8b58cf217fdf4bd34f0975af1e4c19c1a0`, tree `68df728c1eb3cd1e60340286cd99548e6d0d0452`; previously verified four-ABI debug APK uses that source and is debug-signed. Native commit `81ac481…` / tree `ada7e7df6b8c196957afd0da77142dfe64227327` exists only in local clean source checkout pending app worker/UI integration and reviewed GitHub publication. No claim the app APK contains `--preview-state-from`; PR/CI, signing/provenance and device/provider gates remain open |
| Config/import/credential recoverability | CloudBridge | WP03 | PARTIAL / bounded PASS | Transactional DB replacement, bounded import validation, staged ZIP rollback and Keystore-wrapped config secret are implemented; crash/fault-injection and live-device evidence remain |
| Authoritative profile/run state | CloudBridge | WP04–WP08, WP10–WP12 | PARTIAL / WP08 evidence persistence and baseline retention | UUID profiles, semantic revisions, durable owner claims and terminal rows exist; DB v12→v13 stores sanitized native state/reason and non-authorizing recovery-list validation evidence; confirmed reinitialization preserves the last accepted baseline; instrumentation compiled but not run |
| Safety and truthful failure reporting | Both, at owning layer | WP02–WP08, WP11–WP13 | PARTIAL / WP05 IN PROGRESS | WP02–WP04 bounded implementations persist; WP05 adds an owned native handle for workers, serving, RCD, file-open, metadata and bounded listing/config dump; remaining config/OAuth and lifecycle/process-death coverage remain |
| Acceptance device and live-provider gates | External environment | WP15 | NOT RUN | Actual Galaxy model, Android API and firmware recorded; disposable Proton scope verified |

WP08 checkpoint note (2026-09-24): CloudBridge DB v14 added separate path-free preview identity,
freshness and durable owner/history storage; DB v15 now binds absent-state preview identity to an
explicit resync preference and migrates v14 absent-state rows without inventing one. Source-derived
SQLite checks accept an absent/PATH1 queued row and a complete row with all counters, while
rejecting a duplicate active owner, absent state without preference, and complete status without
counters. A v14-to-v15 SQLite fixture leaves a formerly running absent-state owner
`RECOVERY_REQUIRED` with incremented generation and marks a queued absent-state preview `STALE`;
the corresponding Android instrumentation test sources compile but have not run. Full JVM count is
88 with 0 failures/errors and one platform-capability skip; lint passes with the existing baseline
(94 visible warnings; 2 errors and 428 warnings filtered; 76 stale entries). This does not complete
preview execution: no app worker/CLI invocation, result UI, mutation-boundary revalidation,
initialization or recovery integration exists yet. The earlier debug APK does not contain DB v14 or
v15 changes. Samsung and Proton acceptance remain **NOT RUN**.

Latest WP08 update (2026-09-24): CloudBridge commit `2f26ebc` adds an exact-engine-SHA command
builder and an `Rclone` adapter bound to the durable preview owner token/generation. It launches
only dry-run/path-free JSON with explicit delete caps, filters and initialization policy; it bounds
stdout, avoids output/path logging, checks scratch containment/endpoint overlap and retains scratch
when process stop cannot be confirmed. The published app pin can preview absent state but fails
closed for compatible state because that pin lacks `--preview-state-from`; the clone-capable native
SHA remains local and unpublished. Latest offline app validation is 92 JVM tests (0 failures/errors,
1 platform-capability skip), Android-test source compile PASS, and lint task PASS (94 visible
warnings; existing baseline filters 2 errors/428 warnings, 76 stale entries). At that checkpoint
there was no WorkManager call site/worker. There is still no user-facing preview/review UI, new APK,
or device/provider run; Samsung and Proton remain
**NOT RUN**.

Latest WP08 continuation (2026-09-24): CloudBridge commit `cd3fd57cf37728675f25f8e5d2ec7371aaee66fd`
adds a durable WorkManager scheduler/worker, fresh preflight reconstruction, exact identity claim,
endpoint/filter snapshot handoff, owner-safe cancellation/failure transitions, and a queue admission
gate that requires recent persisted successful preflight evidence (including exact compatible
baseline matching or explicit absent-state policy). Offline `:app:testOssDebugUnitTest` passes with
92 tests, 0 failures/errors, 1 existing Windows capability skip; Android instrumentation test
sources compile, but were not executed; `:app:lintOssDebug` passes with the existing baseline
(94 visible warnings; 2 errors/428 warnings filtered; 76 stale entries). ADB could not start its
local server here, so no device availability/API/firmware is inferred. The scheduler has no
user-facing launch/result surface yet; compatible-state execution still fails closed on the published
app pin. No APK/native rebuild, PR, CI publication or release in this checkpoint. Samsung and Proton
acceptance are **NOT RUN**.
