# Current audit findings C01–C12

**Provenance:** Prepared 2026-09-25 against the complete master handoff attachment revised 2026-09-22 (SHA-256 `7179ECAFD7D142D478DD46A0495AC1D4D2C57F431E70703F13B7D77359D791B3`), especially §4, and the CloudBridge worktree at `50ea557` on `codex/luna-implementation`, including current source and the dated execution/release records. The C-numbering and ownership follow master §4; in particular, C03 means import/config/credential integrity. This is an evidence register, not acceptance or a security certification. “Severity” is a planning assessment, not a claim that exploitation has been demonstrated. Tests or device/provider gates without recorded execution are **NOT RUN**; no finding is accepted by this register.

## C01 — Task truth, durable ownership and legacy mode migration

- **finding:** Task dispatch and completion must remain tied to the intended immutable task/profile revision, with unsupported legacy modes never reinterpreted as an ordinary destructive sync.
- **evidence:** `SyncWorker` now claims durable run ownership, returns failure for a non-success failure reason, and rejects unsupported directions; `SyncDirectionObject` preserves unsupported spinner values instead of mapping them to a supported default. However, its compatibility path still accepts a numeric task ID and calls `queueLegacyTask` from the worker, where the current task is resolved. See [SyncWorker.kt](app/src/main/java/ca/pkay/rcloneexplorer/workmanager/SyncWorker.kt), [RunRepository.kt](app/src/main/java/ca/pkay/rcloneexplorer/Database/RunRepository.kt), and [SyncDirectionObject.java](app/src/main/java/ca/pkay/rcloneexplorer/Items/SyncDirectionObject.java). The ledger records profile/run foundations as partial; process-death, duplicate-dispatch, migration and terminal-write instrumentation are **NOT RUN**.
- **failure mode:** A queued request may execute a later-edited task, or cancellation/completion races may leave user-visible run state inconsistent; unsupported directions could otherwise cause false success or unintended one-way execution.
- **severity:** High (planning assessment).
- **scope/owner:** CloudBridge profile/task persistence, editor/import migration, `RunRepository`, dispatcher and workers.
- **required action:** Carry a stable profile/run UUID and semantic revision from enqueue through execution; use typed modes/results, an atomic run claim and one durable terminal writer. Keep unknown and legacy Bisync modes visibly unsupported until explicitly migrated; never default them to ordinary sync.
- **validation:** Test deleted/missing tasks, legacy values 1–8 plus malformed/future values, edit-after-enqueue retargeting, duplicate dispatch, concurrent stop/completion, DB failure at each state transition, process death and exactly-once notifications. Migration, barrier-driven concurrency and device lifecycle evidence remain **NOT RUN**.
- **order/dependencies:** WP03–WP05; close before enabling any Bisync mutation in WP06–WP08.

## C02 — Process and remote-control lifecycle

- **finding:** Every native rclone command and remote-control operation needs one owner for process launch, streams, cancellation, deadlines, exit status and resource release.
- **evidence:** [NativeExecutionHandle.java](app/src/main/java/ca/pkay/rcloneexplorer/util/NativeExecutionHandle.java) now drains both pipes concurrently, bounds retained output, and reaps/cancels an owned process; it is described as a compatibility bridge for older `Process` APIs. [Rclone.java](app/src/main/java/ca/pkay/rcloneexplorer/Rclone.java) still has a direct `Runtime.exec` launch path, and the ledger says process/resource and Android lifecycle acceptance is incomplete. The complete command/config/hash/version/streaming/RCD/reconnect ownership matrix is not evidenced.
- **failure mode:** A full pipe can deadlock a child, cancellation can leave it running, or a lock/resource can be released before native writes stop.
- **severity:** High (planning assessment).
- **scope/owner:** CloudBridge execution adapters, workers, streaming, OAuth/reconnect and RCD callers; rclone context/cancellation at the native boundary.
- **required action:** Route every operation—including ephemeral config, hashing, version probes, streaming and RCD—through the lifecycle owner; retain bounded output, per-run identity, explicit terminal outcomes and redaction. Keep resources attached until process exit/reap is confirmed.
- **validation:** Exercise simultaneous stdout/stderr saturation, oversized lines, timeout, interruption before/after launch, failed startup, stale callbacks, RCD failure, cancellation during writes and process/app death. Full caller and Android lifecycle matrices are **NOT RUN**.
- **order/dependencies:** WP02–WP05; establish before WP06–WP08 mutating work and WP10–WP12 lifecycle-dependent features.

## C03 — Import, config and credential integrity

- **finding:** Import/config replacement must be recoverable and serialized, and at-rest protection claims must match what is actually encrypted.
- **evidence:** The current [Importer.java](app/src/main/java/ca/pkay/rcloneexplorer/Database/json/Importer.java) bounds and parses/validates the full JSON before calling database replacement; missing optional arrays default to empty. The requirements ledger records transactional DB replacement and staged ZIP rollback. [ConfigSecretStore.java](app/src/main/java/ca/pkay/rcloneexplorer/util/ConfigSecretStore.java) Keystore-wraps the rclone config passphrase only; [ADR-001](docs/ADR-001-credential-storage-at-rest.md) explicitly says the rclone config itself is not whole-file encrypted. Crash/disk-full, concurrent writer, key-loss and on-device Keystore acceptance are **NOT RUN**.
- **failure mode:** A malformed or interrupted import can lose settings or leave config/database state inconsistent; stale refresh writes or overstated encryption can expose credentials or mislead users.
- **severity:** High (planning assessment).
- **scope/owner:** CloudBridge import/export, database/config repositories, rclone config replacement and auth-refresh persistence.
- **required action:** Preserve bounded staging and transactional replacement; validate cross-references/schema/UUID mapping before mutation, retain recoverable prior bytes, and serialize config writers including refresh. Keep whole-file encryption claims withdrawn unless a versioned, recoverable migration is designed and tested; never clear usable config on generic key or network failure.
- **validation:** Truncated/oversized JSON, optional legacy fields, duplicate IDs, future schemas, injected write/rename/disk-full failures, process death at each replacement boundary, encrypted import/export round-trip, concurrent refresh and missing/invalidated Keystore key. Fault-injection and device cases are **NOT RUN**.
- **order/dependencies:** WP03, with WP02 redaction and WP00 migration fixtures as prerequisites; retain these guarantees through WP13.

## C04 — Exported actions and untrusted input

- **finding:** Every external action and dynamic input must have an explicit authorization and validation contract before it can enqueue work, alter configuration or reach a destructive command.
- **evidence:** [ShortcutServiceActivity.kt](app/src/main/java/ca/pkay/rcloneexplorer/Activities/ShortcutServiceActivity.kt) now checks the expected action, positive task ID and a validated shortcut capability. The manifest still exposes provider/grant surfaces, and [DynamicRemoteConfigFragment.kt](app/src/main/java/ca/pkay/rcloneexplorer/RemoteConfig/DynamicRemoteConfigFragment.kt) retains TODOs that required/hidden provider-option metadata is not honored. Public-provider, cross-app and real-launcher authorization tests are **NOT RUN**.
- **failure mode:** Forged or stale intents, malformed provider fields, URI/path confusion or unsafe argument construction could start the wrong task or cause unintended data access/mutation.
- **severity:** High for destructive entry points (planning assessment).
- **scope/owner:** CloudBridge exported activities/providers, URI grants, dynamic config/OAuth callbacks, forms and command construction.
- **required action:** Separate public automation from private internal intents; keep shortcuts capability-scoped and compatible with real launchers. Enforce required/hidden/exclusive option semantics and typed/allowlisted argv values across all routes; validate provider metadata, OAuth state and URI-grant lifetime.
- **validation:** Cross-app forged/stale shortcut attempts, unsupported actions/extras, unknown provider fields, required/hidden/exclusive options, traversal and encoded paths, filter preservation, secret-bearing values and URI grant/revocation behavior. Public-provider and device tests are **NOT RUN**.
- **order/dependencies:** WP02 and WP11; authorization tests must precede enabling new external Bisync/Obsidian entry points.

## C05 — Deletion, path ownership and native Bisync safety

- **finding:** Bisync initialization, apply and recovery must be impossible until native safety and an app-wide verified backup/mutation boundary are mandatory for every mutator.
- **evidence:** CloudBridge has profile/preflight/preview code and a schema-v16 paired backup-manifest foundation, but the [ledger](EXECUTION_LEDGER.md) states it is database-only, has no production caller or provider-backed reservation/verification, exact restore, restart reconciliation or mutation authorization; `mutationPermitted` remains false. The app-pinned rclone `fe775a8b58cf217fdf4bd34f0975af1e4c19c1a0` lock regression suite passed all 11 existing functions 100 times on Windows/amd64; only two tests exercise separate OS processes. Broader guard/listing failure matrices, app barriers, Android/Linux runtime, recovery and integrated races are **NOT RUN**. See [BisyncBackupManifestRepository.kt](app/src/main/java/ca/pkay/rcloneexplorer/Database/BisyncBackupManifestRepository.kt) and [RELEASE_READINESS.md](RELEASE_READINESS.md).
- **failure mode:** Concurrent or incorrectly scoped operations can corrupt shared Bisync state, treat an incomplete/error listing as deletion, delete the wrong path, or leave no recoverable prior data.
- **severity:** Critical data-integrity gate (planning assessment).
- **scope/owner:** rclone Bisync algorithm/locking/listing/guard semantics; CloudBridge endpoint identity, path overlap, all-mutator coordination, backup reservation, verification, restore and run admission.
- **required action:** Keep initialize/apply/recovery unavailable. Independently prove native lock ownership, incomplete-listing behavior and exact count-plus-percentage guard boundaries. Require a durable, provider-backed, path-specific reservation and byte-verified backup/restore before a shared mutation barrier can authorize any mutator; reconcile safely after restart.
- **validation:** Separate-process lock contention/renewal/expiry/stale release/corrupt metadata/clock cases; empty-but-failed and incomplete listings; exact guard boundaries; backup collision, permission/disk/network faults; byte/listing preservation, kill/restart/restore; then integrated barrier races. Missing cases and device/provider acceptance are **NOT RUN**.
- **order/dependencies:** WP01 native evidence, then WP05–WP08; WP09 and WP14 evidence are also required before release.

## C06 — Proton session isolation and credential preservation

- **finding:** Proton account/session state and refresh persistence must be isolated by actual account/session identity and must distinguish rejected credentials from transient failures.
- **evidence:** In the pinned [protondrive.go](../rclone-app-pin-wp08/backend/protondrive/protondrive.go), auth callbacks are held in a per-`Fs` `protonAuthState`, but cached-credential initialization failure falls through to username/password login, and auth callbacks can clear the mapped credentials. WP09 records focused `backend/protondrive` tests and `go vet` passing with a candidate Proton API Bridge selected through a temporary workspace; that candidate is not promoted into the app dependency pin. Multiple-account refresh races, restart persistence, live Proton and official-client checks are **NOT RUN**.
- **failure mode:** A transient network/provider error can trigger unnecessary password login or lose usable refresh state; overlapping filesystems/accounts can cross-update credentials, create login storms or break backup-directory initialization.
- **severity:** High (planning assessment).
- **scope/owner:** Rareities/rclone Proton backend and Proton API libraries own protocol/session behavior; CloudBridge owns config persistence and scheduling/coalescing, not protocol workarounds.
- **required action:** Use immutable per-account/session state and coordinated refresh/re-read/serialized persistence. Classify auth rejection separately from timeout, 5xx and rate limits; never wipe usable config or launch password login for an arbitrary error. Key sessions on provider account identity, not display name.
- **validation:** Two accounts and two `Fs` values for one account, concurrent refresh/rotation/config save, expired/revoked credentials, timeout/5xx/429, backup paths, cancellation and restart. Race and live disposable-Proton/official-client acceptance are **NOT RUN**.
- **order/dependencies:** WP01 and WP09 independently before app integration; WP14 live-provider acceptance remains required for release.

## C07 — Proton upload workers, revisions, lookup and cache

- **finding:** Proton upload worker cleanup, object lookup and destination-cache invalidation need independent backend/library proof before an app dependency promotion.
- **evidence:** WP09 records a clean Proton API Bridge upload-cancellation candidate with repeated and library tests, but it is not promoted into the app pin. Exact app-pin package tests/vet were run with that candidate selected only in a temporary Go workspace. Name-fallback lookup, destination-cache invalidation, official-client revision reads and live-provider behavior remain unaccepted or **NOT RUN**; see the WP09 entries in [EXECUTION_LEDGER.md](EXECUTION_LEDGER.md) and [TEST_REPORT.md](TEST_REPORT.md).
- **failure mode:** A worker/semaphore leak can strand later transfers; a hash miss or stale destination cache can look like a missing object; failed update semantics can orphan drafts or lose revisions.
- **severity:** High (planning assessment).
- **scope/owner:** Proton API Bridge and rclone Proton backend own worker, lookup, revision and cache semantics; CloudBridge consumes typed outcomes and must not mask failures.
- **required action:** Independently review and promote only a narrow, provenance-verified candidate. Join/drain workers on every failure/cancellation path and release permits exactly once; verify lookup fallback and cache invalidation. Preserve update-in-place/revision behavior; never delete/recreate an object to hide an update failure.
- **validation:** Fail first/middle/last upload block, cancel semaphore acquisition, then prove subsequent upload succeeds; multiple accounts, hash miss, external rename/move, cache refresh and revision-preserving updates read by the official client. Candidate promotion, race coverage and live disposable-vault tests are **NOT RUN**.
- **order/dependencies:** WP09 at the independent library/backend layer, before app integration and WP14.

## C08 — Forced local modification-time option

- **finding:** A global local-backend modification-time override may change rclone comparison behavior and must not remain without a demonstrated, narrowly scoped need.
- **evidence:** `RCLONE_LOCAL_NO_SET_MODTIME=true` is still added to both normal and RCD environments in [Rclone.java](app/src/main/java/ca/pkay/rcloneexplorer/Rclone.java) and [RcloneRcd.java](app/src/main/java/ca/pkay/rcloneexplorer/RcloneRcd.java). The feature-survival/compatibility records do not show direct-local and SAF comparison acceptance; these tests are **NOT RUN**.
- **failure mode:** Changed timestamp semantics can cause repeated transfers or missed changes, especially when local/SAF timestamps and remote precision differ.
- **severity:** Medium (proposed planning assessment).
- **scope/owner:** CloudBridge rclone environment integration and filesystem capability policy; do not alter reusable backend semantics to compensate for app policy.
- **required action:** Remove the global override unless source-level evidence justifies it; if needed, scope it to a verified filesystem capability and document why. Preserve standard rclone behavior elsewhere.
- **validation:** Direct local and supported SAF paths, same-size content with changed timestamps, one-way copy/sync and Bisync comparison on representative timestamp precisions. All such acceptance is **NOT RUN**.
- **order/dependencies:** WP05 and WP12; validate before claiming local-storage/SAF feature survival.

## C09 — Foreground execution, main-thread work and reconnect

- **finding:** Long-running operations and reconnect/UI work need lifecycle-owned, bounded execution without main-thread native waits or loss of CloudBridge-specific authentication flows.
- **evidence:** [StreamingService.java](app/src/main/java/ca/pkay/rcloneexplorer/Services/StreamingService.java) still extends `IntentService`; [RemotesFragment.java](app/src/main/java/ca/pkay/rcloneexplorer/Fragments/RemotesFragment.java) retains synchronous remote-list access paths, while `NativeExecutionHandle` improves process ownership in migrated callers. The WP05 ledger marks process-death, Android lifecycle and recovery acceptance incomplete. Rotation/background/notification-denial and supported-API device evidence are **NOT RUN**.
- **failure mode:** ANR or foreground-start failure, a leaked process/context, cancellation that outlives its owner, or sensitive reconnect output escaping into logs.
- **severity:** High lifecycle/security risk (planning assessment).
- **scope/owner:** CloudBridge UI, services, workers, reconnect and process lifecycle; preserve Internxt sign-in/TOTP behavior while fixing ownership.
- **required action:** Move remaining long operations to lifecycle-owned asynchronous workers with correct foreground promotion, bounded state/output and explicit cancellation/reap. Remove main-thread cache-miss waits, sanitize parsing/logging, and keep a bounded reconnect state machine without continuous polling.
- **validation:** Rotation, backgrounding, process death, notification denial, timeout, malformed secret-bearing responses, reconnect/TOTP and minimum/target API behavior; Galaxy S26 model/API/firmware and lifecycle matrix are **NOT RUN**.
- **order/dependencies:** WP02 and WP05, then WP10–WP12 lifecycle integration; device acceptance in WP14.

## C10 — SessionGuardian and updater outcomes

- **finding:** Background health checks and update notifications must report real outcomes, preserve cancellation, avoid heuristic auth probes and retain the intended fork/channel identity.
- **evidence:** [SessionGuardianWorker.kt](app/src/main/java/ca/pkay/rcloneexplorer/workmanager/SessionGuardianWorker.kt) now uses provider eligibility and categorized results and rethrows cancellation, but its outer exception handler still returns `Result.success()`. The updater has been retargeted to Rareities, is notification-only and uses bounded/cancellable scanning; pure URL/SemVer policy tests report 10/10, while scanner/worker, preferences and notification integration tests are **NOT RUN**. See [SECURITY_REVIEW.md](SECURITY_REVIEW.md) and the WP13 ledger entry.
- **failure mode:** A failed health scan can be recorded as success; false auth status can prompt unnecessary reauthentication; wrong/malformed release metadata or channel handling can mislead users.
- **severity:** High operational; Medium distribution (planning assessment).
- **scope/owner:** CloudBridge SessionGuardian policy/scheduler, updater scanner and notification intent; provider protocol/auth stays in the backend.
- **required action:** Return truthful bounded retry/failure outcomes for outer errors while propagating cancellation; use provider capabilities and active-work coordination, not token-field heuristics. Keep the updater opt-in/notification-only, strict about fork/channel/version/assets, bounded, cancellable and tied to the selected validated release tag.
- **validation:** Idle no-op, transient vs auth/rate-limit/integrity failures, same-time active run, cancellation, stable/prerelease channels, malformed metadata, page/body caps and exact notification intents. Android scanner/worker/preferences/device tests are **NOT RUN**.
- **order/dependencies:** WP10 and WP13; distribution remains gated until integrated verification.

## C11 — Delete undo and content-provider semantics

- **finding:** Delayed deletion must carry an immutable target and explicit commit/undo semantics; document IDs and grants must remain rooted and authorized.
- **evidence:** [FileExplorerFragment.java](app/src/main/java/ca/pkay/rcloneexplorer/Fragments/FileExplorerFragment.java) copies the pending delete list, but the Snackbar callback enqueues on every dismissal except the Undo action and resolves `remote`/current path later through fragment state. [VirtualContentProvider.java](app/src/main/java/ca/pkay/rcloneexplorer/VirtualContentProvider.java) now validates canonical document IDs and checks supplied remove-parent identity; pure policy tests passed, but public VCP/SAF and URI-grant tests did not run. See the WP12 ledger entry; public-provider tests are **NOT RUN**.
- **failure mode:** Navigation, rotation, replacement/dismissal or process recreation can commit deletion against a changed remote/path; malformed IDs or overbroad grants can expose or mutate the wrong document.
- **severity:** High (planning assessment).
- **scope/owner:** CloudBridge file-operation coordinator/UI, VCP document-ID boundary and Android URI-grant policy.
- **required action:** Capture immutable remote identity, normalized root/path and item IDs before showing undo; make undo, timeout, replacement, lifecycle destruction and process death explicit states. Route eventual mutation through the shared coordinator. Keep syntactic ID validation separate from caller authorization.
- **validation:** Navigate/change remote/rotate during Snackbar, consecutive Snackbars, each dismissal event, process death, encoded separators/repeated roots, mismatched parent, unknown remote and cross-app read/write/revoke grants. Public provider, SAF-client and device tests are **NOT RUN**.
- **order/dependencies:** WP06 and WP12; complete before declaring file-operation/VCP retention accepted.

## C12 — Release identity, diagnostics and documentation

- **finding:** Release identity, engine provenance, diagnostics and technical documentation must agree with the actual build and verified acceptance evidence.
- **evidence:** [gradle.properties](gradle.properties) links CloudBridge to Rareities/rclone at immutable ref `fe775a8b58cf217fdf4bd34f0975af1e4c19c1a0`; this corrects the handoff's older-parent baseline. Release signing now fails closed when production signing inputs are absent, but no production certificate continuity, current APK, native-in-APK hash or provenance record exists. [RELEASE_READINESS.md](RELEASE_READINESS.md) is **NO-GO**. The technical notes now distinguish the checked-head combined-status/PR-triggered-run snapshot from unqueried push/manual runs and the unavailable complete release inventory. No release status is inferred from that missing inventory.
- **failure mode:** A wrong product/signature/engine or irreproducible artifact could be distributed; sensitive diagnostics could leak secrets; developers could maintain against inaccurate architecture/status claims.
- **severity:** Critical release gate (planning assessment).
- **scope/owner:** CloudBridge build/release/signing/diagnostic pipeline and docs; Rareities/rclone pin and dependency provenance.
- **required action:** Keep production builds fail-closed; record authorized signer continuity, immutable source/toolchain inputs, ABI/native hashes and artifact provenance. Audit all diagnostic sinks with canary secrets and centralized redaction. Correct status claims to the actual query limits; reproduce documented clean-build commands. Do not infer compromise, rotate keys, rewrite history or publish solely from inherited audit language.
- **validation:** Missing/misconfigured signer must fail; inspect APK identity/certificate/ABI/native SHA and notices; canary secrets must not appear in logcat/files/history/export; independently reproduce documented commands and refresh PR/CI/release evidence. Current APK, signing/provenance, CI and device checks are **NOT RUN**.
- **order/dependencies:** WP02/WP13 for diagnostics and build controls; final reconciliation in WP15 only after WP14 and all core/provider/device gates. Release remains NO-GO.
