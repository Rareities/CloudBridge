# CloudBridge + rclone requirements matrix

Initial WP00 matrix from `CloudBridge-rclone-Luna-Master-Handoff.md`. A row is complete only
when its owning package, independent tests, integration tests and acceptance evidence are all
recorded in the execution ledger.

| Requirement | Owning layer | Package(s) | WP00 status | Completion evidence required |
|---|---|---|---|---|
| Safe first-class Bisync | rclone plus app orchestration | WP01, WP03–WP08, WP13 | PARTIAL / WP08 native-state foundation | WP07 fail-closed preflight and native deletion guard; WP08 native state inspector plus durable, informational recovery evidence. Preview/init/recovery workflows and device acceptance remain open |
| Proton Drive / Obsidian workflow | rclone backend/dependencies plus app | WP01, WP09–WP10, WP14 | NOT STARTED | Native compatibility tests, disposable Proton tests, sync-before-open and lifecycle tests |
| Durable scheduling and duplicate prevention | CloudBridge | WP03–WP08, WP12 | PARTIAL / WP04 owner boundary | Durable profile/run claim and active-owner uniqueness are implemented; scheduling, process-death, cancellation and lock evidence remains |
| CloudBridge feature retention | CloudBridge | WP03–WP15 | NOT STARTED | Named provider, transfer, browse, share, move/delete and serving regressions |
| Independent rclone health | Rareities/rclone | WP00–WP02, WP09 | PARTIAL / focused PASS | Go 1.26 build, sync/operations tests, targeted Proton/Internxt tests pass; broad Windows-dependent sweep is not clean |
| Independent Android health | CloudBridge | WP00, WP03–WP08, WP12–WP15 | PARTIAL / 76 JVM tests + instrumentation-source compile + lint PASS | JDK21 Gradle run: 14 suites, 76 tests, 0 failures/errors, 1 platform skip; Android-test Java compiled, not executed; lint passes with baseline (92 warnings, 2 baseline errors and 428 warnings filtered; 76 stale entries); current APK/R8/release checks remain |
| CloudBridge links to Rareities/rclone | CloudBridge build integration | WP01, WP15 | PINNED / FOUR-ABI INTEGRATION BUILD PASS | URL is `https://github.com/Rareities/rclone.git`, immutable ref `d53551e1722305268c6072263f11066f1278a4a0`; tree `98c402c28fda112c56b543b3104841435fb8cdf6`; four ABI native build and app tests passed. PR/CI and release provenance remain open |
| Config/import/credential recoverability | CloudBridge | WP03 | PARTIAL / bounded PASS | Transactional DB replacement, bounded import validation, staged ZIP rollback and Keystore-wrapped config secret are implemented; crash/fault-injection and live-device evidence remain |
| Authoritative profile/run state | CloudBridge | WP04–WP08, WP10–WP12 | PARTIAL / WP08 evidence persistence compiled | UUID profiles, semantic revisions, durable owner claims and terminal rows exist; DB v12→v13 stores sanitized native state/reason and non-authorizing recovery-list validation evidence; migration instrumentation compiled but not run |
| Safety and truthful failure reporting | Both, at owning layer | WP02–WP08, WP11–WP13 | PARTIAL / WP05 IN PROGRESS | WP02–WP04 bounded implementations persist; WP05 adds an owned native handle for workers, serving, RCD, file-open, metadata and bounded listing/config dump; remaining config/OAuth and lifecycle/process-death coverage remain |
| Acceptance device and live-provider gates | External environment | WP15 | NOT RUN | Actual Galaxy model, Android API and firmware recorded; disposable Proton scope verified |
