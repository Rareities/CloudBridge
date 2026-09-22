# CloudBridge + rclone requirements matrix

Initial WP00 matrix from `CloudBridge-rclone-Luna-Master-Handoff.md`. A row is complete only
when its owning package, independent tests, integration tests and acceptance evidence are all
recorded in the execution ledger.

| Requirement | Owning layer | Package(s) | WP00 status | Completion evidence required |
|---|---|---|---|---|
| Safe first-class Bisync | rclone plus app orchestration | WP01, WP03–WP08, WP13 | NOT STARTED | Standalone engine tests, profile/init/preview/conflict/recovery tests, integrated Android runs |
| Proton Drive / Obsidian workflow | rclone backend/dependencies plus app | WP01, WP09–WP10, WP14 | NOT STARTED | Native compatibility tests, disposable Proton tests, sync-before-open and lifecycle tests |
| Durable scheduling and duplicate prevention | CloudBridge | WP03–WP08, WP12 | PARTIAL / WP04 owner boundary | Durable profile/run claim and active-owner uniqueness are implemented; scheduling, process-death, cancellation and lock evidence remains |
| CloudBridge feature retention | CloudBridge | WP03–WP15 | NOT STARTED | Named provider, transfer, browse, share, move/delete and serving regressions |
| Independent rclone health | Rareities/rclone | WP00–WP02, WP09 | PARTIAL / focused PASS | Go 1.26 build, sync/operations tests, targeted Proton/Internxt tests pass; broad Windows-dependent sweep is not clean |
| Independent Android health | CloudBridge | WP00, WP03–WP08, WP12–WP15 | PARTIAL / 47 unit+lint PASS | JDK17/Gradle 47 JVM unit tests and lint pass; debug/release/R8/native checks remain required |
| CloudBridge links to Rareities/rclone | CloudBridge build integration | WP01, WP15 | PINNED LOCALLY / integration NOT RUN | URL is `https://github.com/Rareities/rclone.git`, ref `1583cce…` is immutable, missing properties fail closed; clean Gradle/native build must still prove provenance |
| Config/import/credential recoverability | CloudBridge | WP03 | PARTIAL / bounded PASS | Transactional DB replacement, bounded import validation, staged ZIP rollback and Keystore-wrapped config secret are implemented; crash/fault-injection and live-device evidence remain |
| Authoritative profile/run state | CloudBridge | WP04–WP08, WP10–WP12 | PARTIAL / WP04 bounded PASS | UUID profiles, semantic revisions, durable owner claims and terminal rows are implemented; UI migration, native lifetime and instrumentation evidence remain |
| Safety and truthful failure reporting | Both, at owning layer | WP02–WP08, WP11–WP13 | PARTIAL / WP05 IN PROGRESS | WP02–WP04 bounded implementations persist; WP05 adds an owned native handle for workers, serving, RCD, file-open, metadata and bounded listing/config dump; remaining config/OAuth and lifecycle/process-death coverage remain |
| Acceptance device and live-provider gates | External environment | WP15 | NOT RUN | Actual Galaxy model, Android API and firmware recorded; disposable Proton scope verified |
