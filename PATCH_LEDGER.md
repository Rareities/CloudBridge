# CloudBridge patch ledger

**Snapshot:** 2026-09-25, branch `codex/luna-implementation`. This file indexes scoped source changes; the required 13-field implementation/test/rollback evidence is in [`EXECUTION_LEDGER.md`](EXECUTION_LEDGER.md). Local source commits are not GitHub PRs and are not release evidence.

| Source commit | WP | Scope | Verification / state |
|---|---|---|---|
| `80d1732` | WP13/WP14 | Fail-closed release signing; removed automatic beta publication | Static/Gradle signing-gate checks recorded in execution ledger. No production key, signed artifact, provenance, or release. |
| `5cbceb5578806ac115e0195e951a0dd88b82f258` | WP08 | Bisync endpoint/backup-root claim parsing and contention tests | Source and parser work documented; original app unit/instrumentation execution was blocked at the time. Later full JVM suite passed after SDK workaround, but instrumentation remains **NOT RUN**. |
| `3406d2a27065657bbb94289095fffde3dddf7b40` | WP08 | Read-only endpoint snapshot decoder/classifier | Followed by separator correction `d95ea063dce4c124e05bb1125db6a2508975cc6d`; current unit tests now execute. No backup-placement approval is produced. |
| `755454e35838384e1b161053cc4fa7b15469ad1e` | WP08 | Bounded foreground-future cancellation/timeout behavior | Kotlin compilation passed in prior ledger evidence; full WP08 backup/restore/mutation boundary remains incomplete. |
| `115cd94b2dec78841c85f211a721f0e581799b5c` | WP10 | Partial one-off WorkManager dispatch/scheduling | Ledger records the bounded slice; persisted dispatcher/coalescing/missed-run/device behavior remain open. |
| `70b2375f6d8cce4d65433b9658f7e0a6dcbf1809` | WP08/WP10 | Missing Java import for schedule calculator; corrected canonical future-schema test mutation | Full offline OSS debug JVM suite: 24 suites, 130 tests, 1 platform-capability skip, 0 failures; native Gradle tasks excluded. |
| `a35e0b323626a3e544986619acd010c3fa079369` | WP13 documentation slice | Fork-specific user/build/security/test/release docs, accurate subprocess architecture, qualified ABI/API statements, portability/status corrections, and updater comment interval | `git diff --cached --check` passed; local Markdown/HTML target and stale-claim searches passed. Runtime behavior was unchanged at this commit; updater URLs were subsequently retargeted by `d715e16`. No APK/release. |
| `d715e16` | WP13 | Rareities/CloudBridge notification-only updater identity, strict SemVer/channel selection, bounded release paging, About repository destination and localized copy | Pure policy suite: 9 tests passed in the prior run on the same policy/test snapshot; fresh standalone and Gradle reruns were blocked before JUnit by javac/ZipFS cached-file access. Kotlin compile, XML/link scans, and diff hygiene passed. WP13 remains partial; no real release, integrated worker/device acceptance, APK, or publication. |
| `d9e130a` | WP13 | Route notification tap and view action to the validated selected release tag; safe release-index fallback for invalid stored versions | Resource merge and Kotlin compile had previously passed on this source. Latest resource merge **PASS**; Kotlin task and JUnit reruns **BLOCKED/NOT PASSED** before the new test ran because of local Gradle/Javac cache access errors. Selected-tag regression is authored but **NOT RUN**. No APK/release. |
| `21eb807` | WP12 | Fix Simplified Chinese sync-progress positional placeholders | `:app:mergeOssDebugResources` **PASS** with the previous placeholder-format warning absent. Kotlin compile is **BLOCKED/NOT PASSED** by Gradle transformed-cache directory creation; device locale/accessibility validation remains **NOT RUN**. |

## Promotion boundaries

- The app remains pinned to Rareities/rclone commit `fe775a8b58cf217fdf4bd34f0975af1e4c19c1a0`.
- Local rclone worktrees `rclone-history` and `rclone-preview-state` have separate candidate code/evidence and must not be conflated with the app pin. The broad preview-state branch is not approved for wholesale promotion.
- Do not cherry-pick proposals or generated documentation blindly. Re-review each patch against the refreshed upstream/fork state and current package prerequisites.
- CloudBridge WP08 commit `2557a835918ad8c3d245d0875abcdcaa6be64561` adds only a pure, hash-only placement-shape assessment. It is not mutation authorization and remains local/unpublished.
- No external branch, pull request, APK, production signature, or release was created by the entries above.
