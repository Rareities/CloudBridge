# CloudBridge + rclone requirements matrix

Initial WP00 matrix from `CloudBridge-rclone-Luna-Master-Handoff.md`. A row is complete only
when its owning package, independent tests, integration tests and acceptance evidence are all
recorded in the execution ledger.

| Requirement | Owning layer | Package(s) | WP00 status | Completion evidence required |
|---|---|---|---|---|
| Safe first-class Bisync | rclone plus app orchestration | WP01, WP03–WP08, WP13 | NOT STARTED | Standalone engine tests, profile/init/preview/conflict/recovery tests, integrated Android runs |
| Proton Drive / Obsidian workflow | rclone backend/dependencies plus app | WP01, WP09–WP10, WP14 | NOT STARTED | Native compatibility tests, disposable Proton tests, sync-before-open and lifecycle tests |
| Durable scheduling and duplicate prevention | CloudBridge | WP03–WP08, WP12 | NOT STARTED | Worker/process-death/cancellation/lock tests and observable result truth |
| CloudBridge feature retention | CloudBridge | WP03–WP15 | NOT STARTED | Named provider, transfer, browse, share, move/delete and serving regressions |
| Independent rclone health | Rareities/rclone | WP00–WP02, WP09 | PARTIAL / focused PASS | Go 1.26 build, sync/operations tests, targeted Proton/Internxt tests pass; broad Windows-dependent sweep is not clean |
| Independent Android health | CloudBridge | WP00, WP03–WP08, WP12–WP15 | PARTIAL / unit+lint PASS | JDK17/Gradle unit tests and lint pass; debug/release/R8/native checks remain required |
| CloudBridge links to Rareities/rclone | CloudBridge build integration | WP01, WP15 | PINNED LOCALLY / integration NOT RUN | URL is `https://github.com/Rareities/rclone.git`, ref `1583cce…` is immutable, missing properties fail closed; clean Gradle/native build must still prove provenance |
| Safety and truthful failure reporting | Both, at owning layer | WP02–WP08, WP11–WP13 | PARTIAL / WP02 bounded PASS | WP02 redacts/bounds audited diagnostics and gates exported shortcuts; process/result/scheduling coverage remains |
| Acceptance device and live-provider gates | External environment | WP15 | NOT RUN | Actual Galaxy model, Android API and firmware recorded; disposable Proton scope verified |
