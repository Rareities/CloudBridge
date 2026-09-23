# CloudBridge + rclone requirements matrix

Initial WP00 matrix from `CloudBridge-rclone-Luna-Master-Handoff.md`. A row is complete only
when its owning package, independent tests, integration tests and acceptance evidence are all
recorded in the execution ledger.

| Requirement | Owning layer | Package(s) | WP00 status | Completion evidence required |
|---|---|---|---|---|
| Safe first-class Bisync | rclone plus app orchestration | WP01, WP03–WP08, WP13 | PARTIAL / WP08 parser and native isolated-baseline primitive PASS locally | Successful native dry-run scratch no longer invalidates accepted state; reinitialization preserves the prior accepted baseline. CloudBridge still pins published `fe775a8…` and its verified four-ABI debug APK uses that ref. Local unpublished native follow-up `81ac481705944ac125e2f8eeab823d78f6b1cfdb` adds guarded byte-exact baseline cloning and `--preview-state-from`; app worker/UI/persistence/freshness/mutation-boundary integration is not implemented yet. Durable backups/restore, init/recovery and device/provider acceptance remain open |
| Proton Drive / Obsidian workflow | rclone backend/dependencies plus app | WP01, WP09–WP10, WP14 | NOT STARTED | Native compatibility tests, disposable Proton tests, sync-before-open and lifecycle tests |
| Durable scheduling and duplicate prevention | CloudBridge | WP03–WP08, WP12 | PARTIAL / WP04 owner boundary | Durable profile/run claim and active-owner uniqueness are implemented; scheduling, process-death, cancellation and lock evidence remains |
| CloudBridge feature retention | CloudBridge | WP03–WP15 | NOT STARTED | Named provider, transfer, browse, share, move/delete and serving regressions |
| Independent rclone health | Rareities/rclone | WP00–WP02, WP09 | PARTIAL / WP08 native follow-up validated locally | Go 1.26 full `cmd/bisync` tests, package vet, full `go build ./...`, fresh CLI validation smoke and Android/arm64 package cross-build pass for local commit `81ac481…`; source is not yet published and broader Windows-dependent/provider sweep remains separate |
| Independent Android health | CloudBridge | WP00, WP03-WP08, WP12-WP15 | PARTIAL / 88 JVM tests + instrumentation-source compile + lint + earlier debug APK PASS | JDK17.0.20.1 / Gradle 8.13: latest JVM suite reports 88 tests, 0 failures/errors, 1 existing Windows capability skip. Android-test sources compile but instrumentation is not executed. Latest lint task passes with 0 current errors, 94 warnings and existing baseline suppression of 2 errors/428 warnings (76 stale baseline entries); preview owner/freshness code has two `UseKtx` suggestions. Earlier APK predates DB v14 preview-owner changes, so it is not evidence for this snapshot; rerun APK after worker integration |
| CloudBridge links to Rareities/rclone | CloudBridge build integration | WP01, WP08, WP15 | Existing published pin/4-ABI debug PASS; local preview-state follow-up not integrated | Current app URL/ref remain `https://github.com/Rareities/rclone.git` / `fe775a8b58cf217fdf4bd34f0975af1e4c19c1a0`, tree `68df728c1eb3cd1e60340286cd99548e6d0d0452`; previously verified four-ABI debug APK uses that source and is debug-signed. Native commit `81ac481…` / tree `ada7e7df6b8c196957afd0da77142dfe64227327` exists only in local clean source checkout pending app worker/UI integration and reviewed GitHub publication. No claim the app APK contains `--preview-state-from`; PR/CI, signing/provenance and device/provider gates remain open |
| Config/import/credential recoverability | CloudBridge | WP03 | PARTIAL / bounded PASS | Transactional DB replacement, bounded import validation, staged ZIP rollback and Keystore-wrapped config secret are implemented; crash/fault-injection and live-device evidence remain |
| Authoritative profile/run state | CloudBridge | WP04–WP08, WP10–WP12 | PARTIAL / WP08 evidence persistence and baseline retention | UUID profiles, semantic revisions, durable owner claims and terminal rows exist; DB v12→v13 stores sanitized native state/reason and non-authorizing recovery-list validation evidence; confirmed reinitialization preserves the last accepted baseline; instrumentation compiled but not run |
| Safety and truthful failure reporting | Both, at owning layer | WP02–WP08, WP11–WP13 | PARTIAL / WP05 IN PROGRESS | WP02–WP04 bounded implementations persist; WP05 adds an owned native handle for workers, serving, RCD, file-open, metadata and bounded listing/config dump; remaining config/OAuth and lifecycle/process-death coverage remain |
| Acceptance device and live-provider gates | External environment | WP15 | NOT RUN | Actual Galaxy model, Android API and firmware recorded; disposable Proton scope verified |

WP08 checkpoint note (2026-09-24): CloudBridge DB v14 now provides separate path-free preview
identity, freshness and durable owner/history storage. The exact source-generated SQLite DDL and
indexes passed an in-memory integrity/constraint check; full JVM count is 88 with 0 failures/errors
and one platform-capability skip. Android instrumentation sources compile but have not run. This
does not complete preview execution: no app worker/CLI invocation, result UI, mutation-boundary
revalidation, initialization or recovery integration exists yet. The earlier debug APK does not
contain these DB v14 changes. Samsung and Proton acceptance remain **NOT RUN**.
