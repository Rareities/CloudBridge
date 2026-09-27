# CloudBridge + Rareities/rclone security review

**Snapshot:** 2026-09-25. **Review status:** PARTIAL engineering review; not a penetration
test, external audit or security certification. The master handoff's security contracts
remain requirements, not proof that implementation meets them.

## Bounded controls with source/test evidence

- **Execution diagnostics (WP02):** the shared redaction path covers the audited formatted,
  file, sync and native-stderr sinks, caps diagnostic text and sync-log growth, and avoids
  raw config/provider payloads in reviewed paths. Exported shortcut requests require the
  expected action, a positive typed task ID and a per-task capability token; legacy or
  unrecognized requests fail closed. This is not proof that every future sink/entry point
  is safe.
- **Share ingress (WP02):** exported Android shares now stage under a unique app-cache directory,
  sanitize and disambiguate names, count bytes while streaming under per-file/aggregate/file-count
  ceilings, enforce both Unicode-code-point and UTF-8 filename-byte limits, and submit staged
  files through one WorkManager enqueue operation (remote upload outcomes remain independent).
  Cancelled/abandoned pre-enqueue staging is removed. After enqueue, each upload worker deletes only
  its own staged source after native process exit is confirmed; unconfirmed exit, ambiguous enqueue,
  or launch without a returned `Process` preserves the bytes and endpoint/config barriers. Android's
  `UNIXProcess` can fork before stream setup throws ([AOSP source](https://android.googlesource.com/platform/libcore/%2B/master/ojluni/src/main/java/java/lang/UNIXProcess.java)). WorkManager terminal state is not treated as process-exit proof, and no
  age-only/directory-wide cleanup is enabled. Unknown child state can strand the non-expiring
  durable endpoint claim/config barrier indefinitely because no PID/Process handle is available;
  recovery is an open release blocker. Provider exception text is not logged because it can
  contain incoming URIs. Bounded helper tests pass **22/22** and the combined helper/native suite is
  **38/38**; native-handle tests pass **16/16** in a standalone runner (one Android Log JNI warning
  from the non-Android harness); OSS Kotlin compile
  passes. Full app Java compilation is **BLOCKED / NOT PASSED** by transformed-archive access denial;
  Android caller, WorkManager and device integration remain **NOT RUN**. Process death, uncertain
  enqueue, or cancellation before a worker starts can leave cache-only staged data until Android
  trims cache; a durable ownership-aware orphan janitor/global cache quota is not implemented.
- **Provider metadata cache (WP02):** cached JSON reads are bounded, atomically written, and validate
  each provider name/options shape before UI construction; malformed cache falls back to a bounded
  native refresh. Semantic cache/UI integration tests remain **NOT RUN**.
- **Config and credentials (WP03):** bounded import validates before mutation and uses
  rollback snapshots. The rclone config is not wholly encrypted by CloudBridge. The cached
  config password is AES-GCM ciphertext wrapped by an Android Keystore key. A key-loss
  error preserves ciphertext/config and requires explicit recovery; no silent clear or
  downgrade is intended. Live device Keystore and fault-injection acceptance are **NOT RUN**.
- **Release signing (WP13 slice):** production packaging is fail-closed when the production
  key/credentials are unavailable; the debug key is not an acceptable production fallback.
  Release package/sign/bundle tasks now validate the configured X.509 fingerprint inline and
  cannot reuse cached/up-to-date artifacts; local policy/wiring checks pass and are included in
  the checked-in CI workflow. Hosted CI has not run. No production signer or certificate
  continuity has been verified; the workflow does not automatically publish a release.
- **Component boundary:** Services.SyncService is non-exported in the current manifest.
  The legacy README's direct external Tasker invocation is unsupported as written; docs
  were qualified rather than exporting the service.
- **Bisync:** current preview/endpoint parsing is fail-closed for unsupported endpoint
  shapes, but safe initialization/apply/recovery remains gated. Endpoint-specific backup
  placement, unique run-owned backup lifecycle, exact restoration and mutation-boundary
  revalidation are not established.

## Material findings

| Finding | Evidence | Failure mode | Severity | Scope / owner | Required action | Validation | Dependency / order |
|---|---|---|---|---|---|---|---|
| SR-01: Bisync preservation and recovery gate is incomplete | Source commit `2c302158` adds a schema-v16 database-only paired manifest and scope-overlap checks; repository creation is restricted to `PREFLIGHT` but is not a required transition before every mutator. It does not reserve/probe provider paths, verify backup bytes, reconcile after restart, restore, or authorize mutation; instrumentation is **NOT RUN** | Initialization/recovery could overwrite data without a collision-free, restart-surviving and verifiable copy, or act on stale endpoint identity | **Critical / release-blocking** | CloudBridge orchestration + rclone native semantics | Keep initialization/apply/recovery unavailable; make a validated reservation mandatory before native work; prove path-specific placement and implement unique run-owned backups, durable manifest, restart/restore and mutation-boundary checks | Exact bytes/listings across empty/populated roots, conflicts, late edits, collisions, permission/disk/network failure, kill boundaries, restart and restore; then integrated/device tests | WP08 after WP07/prerequisites; WP09 and WP14 before release |
| SR-02: Production signing identity and artifact provenance are unknown | No production APK, certificate fingerprint, signer continuity, native binary hash or release provenance is present | Artifact could be signed by the wrong identity or contain an unexpected engine; publisher continuity cannot be verified | **Critical / release-blocking** | CloudBridge release/build pipeline and key custody | Obtain authorized production key through the defined secure channel; record intended certificate, immutable sources/toolchains, ABI/native provenance and hashes | Authorized release build; inspect signer, manifest, ID/version, ABI/native binary and notices; verify provenance and rollback artifact | WP13 after core/WP14; human release decision last |
| SR-03: VCP/URI-grant and local serving surfaces lack the required authorization audit | Worktree now validates/canonicalizes supported VCP document-ID entry points, checks component-aware containment and rejects mismatched remove parents before deletion; public VCP tests are **NOT RUN**. Caller/grant, cross-app and network-binding matrices remain absent. | Overbroad URI access, path escape, unintended LAN exposure or data disclosure | **High / open** | CloudBridge provider, IPC and serving owners | Trace every provider/service surface and grant lifetime; define caller, URI containment, read/write and bind policy; fail closed on unknown callers/paths | Instrumented cross-app grant/revocation/path tests, permission denial and localhost/LAN/auth tests across supported APIs | WP12 after WP05-WP11 contracts stabilize; before WP15 |
| SR-04: Proton correctness and credential/session behavior are not integrated/accepted | WP09 partial; candidate dependency tests are separate from app-pinned engine; live Proton NOT RUN | Session collision/refresh or revision behavior could cause auth loops, lost updates, orphaned drafts or unsafe retry | **High / release-blocking** | rclone Proton backend/libraries; CloudBridge consumes typed outcomes | Close independent backend/library fixes and compatibility review before integration; never wipe credentials; verify via official client using verified disposable scope | Multiple accounts, refresh races, cancellation/worker cleanup, revisions, external rename, crypt layering, rate/auth errors; then disposable-vault round trips and restore | WP09 before integration; WP14/15 held without live evidence |
| SR-05: Scheduler background behavior and Obsidian workflow are unaccepted | WP10 source commit `e6097f1` adds persisted-state/snapshot checks, current-process admission serialization and cancellation/follow-up fixes; a complete persisted dispatcher/coalescing/missed-run policy is still absent. No explicit Obsidian integration was located. | Duplicate/missed/late work or launch after failure; required workflow may be absent or misrepresented | **High / release-blocking core feature risk** | CloudBridge scheduler/UI and app integration | Close WP10 compatibility/generation/dispatcher gaps; implement and test Obsidian entry/lifecycle only after WP08/WP10 safety prerequisites, without continuous polling or AccessibilityService abuse | Deterministic races, process recreation, cancellation, reboot/force-stop/doze/permissions and actual Galaxy S26 API/firmware | WP10 then WP11; device gate WP14 |
| SR-06: Supply-chain and public delivery controls are incomplete | 2026-09-25 read-only refresh reported default branches unprotected and returned no combined statuses or PR-triggered runs for checked heads; the workflow query was PR-scoped, so push/manual runs and complete release inventory are **NOT VERIFIED**. Signing, dependency/advisory/license and artifact gates remain incomplete | Changes could be promoted without independent checks or traceable provenance | **High / release-blocking** | Both repositories and release workflow | Establish reviewed PR checks and dependency/license/advisory evidence; pin immutable engine input and attest artifact inputs; no blind cherry-picks/history rewrite | Fresh CI on exact PR commits, independent build/test, inventory and artifact provenance verification | WP13 then WP15 |
| SR-07: App config at-rest confidentiality must not be overstated | Current ADR/security docs say rclone.conf itself is not whole-file encrypted; only stored passphrase is Keystore-wrapped | Users could assume stronger at-rest protection than provided | **Medium / user transparency** | CloudBridge config and docs | Keep claims precise; design versioned encrypted config only with migration, rotation and recovery tests; do not silently alter rclone's contract | Device storage/key-loss/migration tests and backup/import review | WP03/WP13; docs accurate meanwhile |
| SR-08: Optional update checker must preserve fork/channel identity | `UpdateWorker.kt` and `AppUpdateNotification.kt` use centralized Rareities/CloudBridge endpoints; preference defaults off and no installer path is present. Stable installs ignore prereleases. The bounded scanner caps pages/body/changelog and preserves prior state on incomplete results; HTTP calls now have a cancellation hook and total timeout. Notifications use the validated selected tag or safe release index. Kotlin compiles, but scanner/worker tests are **NOT RUN**. | A bad tag/API response, channel mix-up, cancellation race or incorrect notification intent could still mislead users | **Medium / distribution integrity** | CloudBridge updater owner and project identity | Keep fork URLs centralized, parse strict semantic versions, honor stable/prerelease metadata, bound pagination and body reads, cancel in-flight requests, use the selected tag URL, and keep the updater notification-only | Current standalone JDK/JUnitCore tests for the pure release URL/SemVer policy reported **10/10 PASS**; these do not exercise the Android scanner/worker. Scanner tests and Android HTTP/preferences/notification-intent behavior are **NOT RUN**; refresh release availability before distribution | WP13 PARTIAL; block distribution until integrated verification |
| SR-09: Legacy trigger work and trigger identity are not migration-safe | Independent history audit found pre-`e6097f1` manual and scheduled requests shared WorkManager input/tag identity; durable run rows do not retain origin and `WorkInfo` queries cannot recover absent trigger metadata. Selective cancellation/rejection is not safe: cancelling shared tags risks manual work; leaving jobs intact risks a stale scheduled run after disable/delete. The current working tree now uses a full-long data-URI identity for newly created PendingIntents, requeues all triggers on package replacement, and cancels legacy request-code identities after reconciliation; the pure identity test is 2/2 standalone. Gradle Java/JUnit and Android PendingIntent lifecycle tests are **NOT RUN**. Trigger-row ID reuse and ambiguous legacy WorkManager requests remain open. | A legacy scheduled job may run after disable/delete; delete/recreate can admit stale work; old colliding alarms may replace/cancel another trigger | **High / release-blocking scheduler safety** | CloudBridge trigger/database/WorkManager owner | Keep ambiguous legacy requests intact unless a user/product policy explicitly accepts the manual-work impact; document the residual stale-schedule risk. Add durable unique trigger-generation identity and safe alarm migration; preserve pending/manual work and explicit missed-run policy | Old-payload migration tests; manual-versus-scheduled queue discriminator tests; delete/recreate and same-config generation tests; Long-ID PendingIntent identity/cancellation tests; barrier-driven worker/edit/delete/import races; instrumentation and S26 lifecycle runs | WP10 before WP11/WP14; do not claim complete scheduling until Android and device acceptance |

## Master finding crosswalk (C01–C12)

The following register maps the master handoff's twelve consolidated findings to this
working-tree review. “Partial” means only the stated source slice exists; it is not a
package acceptance or a claim that the failure mode is reproduced. The rows retain the
master's evidence, failure, severity, owner, action, validation, and dependency fields.

| Finding / status | Evidence | Failure mode | Severity | Scope / owner | Concrete action | Validation | Dependency / order |
|---|---|---|---|---|---|---|---|
| **C01 — PARTIAL: task truth, durable ownership and legacy modes** | `RunRepository` and worker-side mode/run checks exist; the WP03/WP04 ledger still records incomplete single-source-of-truth, legacy-mode and process-death proof. | False success, stale/retargeted work, duplicate terminal writes, or unsupported old mode interpreted as ordinary sync. | High | CloudBridge task database, editor, scheduler and workers. | Finish immutable identity/revision propagation, one terminal writer, typed outcomes and explicit unsupported-mode migration; never fall back to a destructive default. | Missing/deleted/imported task, every legacy direction, queued retarget, duplicate dispatch, concurrent stop/completion, DB failure and process-death instrumentation; current device lifecycle **NOT RUN**. | WP03–WP05 before enabling Bisync or declaring WP04 complete. |
| **C02 — PARTIAL: process and RCD lifecycle** | `NativeExecutionHandle` is the central launch/drain implementation; operation-family migration and reap/resource-lifetime acceptance remain incomplete in the WP05 ledger. | Pipe deadlock, leaked child process, false completion or releasing endpoint ownership while native work continues. | High | CloudBridge launch, RCD, streaming, workers and native cancellation boundary. | Route every process family through one owner for argv/environment, bounded pipes, timeout, cancellation, exit/reap and resource leases; retain interactive streaming semantics. | Simultaneous pipe saturation, huge line, timeout, startup failure, interruption, callback failure and app-kill/reap tests; Android process-death tests **NOT RUN**. | WP02–WP05; device/lifecycle proof before WP14. |
| **C03 — PARTIAL: import/config/credential integrity** | `Importer` stages and validates bounded JSON before `DatabaseHandler.replaceAll`; `ConfigSecretStore` protects the saved config passphrase, but `rclone.conf` itself is not wholly encrypted. See WP03 and SR-07. | Partial settings replacement, stale config writes, credential loss or users believing plaintext config is encrypted. | High | CloudBridge importer, database/config persistence and native config writers. | Prove atomic replacement across config/preferences/database; serialize writers; define explicit key-loss, backup and whole-config encryption migration without overstating current protection. | Truncated/oversized/future imports, duplicate IDs, missing optional arrays, disk-full/rename failure, interruption at each boundary, migration/round-trip/key-loss fixtures; runtime instrumentation **NOT RUN**. | WP03 before profile/run acceptance and WP13 documentation/signing. |
| **C04 — PARTIAL: exported actions and untrusted inputs** | `ShortcutServiceActivity` validates action, task ID and capability; provider-form policy and VCP ID validation exist, while hostile cross-app/merged-manifest and final native-option acceptance are not complete. | Unintended task launch, command-option confusion, invalid remote config or path/URI escape. | High for destructive boundaries | CloudBridge IPC, shortcut, provider forms, document IDs and command construction. | Finish capability/replay policy, typed bounded provider values and final argv/config boundary checks; keep explicit exported components least-privileged. | Foreign-app forged/stale/replay intents; unknown provider fields; multiline/Unicode/oversize secrets; traversal/encoded paths; merged OSS/RS manifest and URI grant tests; Android hostile-caller tests **NOT RUN**. | WP02, then WP11/WP12 before release. |
| **C05 — OPEN / BLOCKING: deletion, path ownership and Bisync safety** | Native exact-pin tests are bounded to their recorded rclone scope. WP08 source includes database-only paired-manifest foundations but no mandatory provider reservation/verification, exact restore, restart reconciliation or production mutation authorization. | Wrong-side delete/overwrite, corrupt/shared baseline, or a second writer entering while prior native mutation may continue. | Critical / release-blocking | rclone Bisync algorithm/locks and CloudBridge endpoint/path/run ownership. | Complete provider-specific exclusive backup reservation and byte verification; bind it to immutable run/preflight identity; require authorization at every native mutation boundary; restore/reconcile after restart. | Native guard/lock/listing tests, exact byte/listing preservation, incomplete listings, collision/permission/disk/network faults, cancellation/app-kill, exact restore and integrated race tests. Provider/device acceptance **NOT RUN**. | WP01 and WP05–WP08; WP14 must pass before release. |
| **C06 — PARTIAL / BLOCKING: Proton sessions and credentials** | App-pin ProtonDrive tests and isolated candidate-library work are documented separately; no candidate promotion or verified disposable live Proton session exists. | Cross-account session contamination, lost refresh state, auth loops or credential clearing on transient failure. | High / release-blocking | rclone Proton backend/dependencies; CloudBridge consumes typed results. | Keep state per real account/session; coordinate refresh and persistence; distinguish rejected credentials from transient/rate/network errors; never clear usable config for arbitrary failures. | Two-account/two-Fs, concurrent refresh, token rotation/save races, timeout/5xx/429, cancellation/restart and verified disposable-account tests; live Proton **NOT RUN**. | WP01/WP09 independently before app integration and WP14. |
| **C07 — PARTIAL / NOT PROMOTED: Proton upload/revision/lookup/cache** | Proton API Bridge upload cancellation and related work are candidate-library evidence only; the current app pin and live official-client revision path are not accepted. | Leaked workers/semaphore permits, orphaned drafts, false missing object, lost revisions or stale destination cache. | High | rclone Proton API Bridge/backend, with CloudBridge integration. | Review and promote only a clean upstream-quality candidate; fix worker join/permit ownership, lookup fallback and cache invalidation at their owning library layer. | Fail first/middle/last block, cancelled permit, subsequent upload, external rename/cache refresh and official-client revision reads in a disposable area; live checks **NOT RUN**. | WP09 independent libraries before pin change/integration; WP14/15 held. |
| **C08 — OPEN: globally forced local modtime behavior** | `Rclone.getEnv()` and `RcloneRcd.getEnv()` still add `RCLONE_LOCAL_NO_SET_MODTIME=true`. | Filesystem comparison/modtime semantics change globally, causing repeated or missed transfer decisions. | Medium, pending exact platform evidence | CloudBridge environment policy and local/SAF provider integration. | Remove the global override or constrain it to a demonstrated provider capability with user-visible semantics. | Direct local/SAF same-size and changed-time files, one-way sync and Bisync comparisons across supported providers; not yet run. | WP05/WP12 before declaring retained filesystem features accepted. |
| **C09 — PARTIAL: foreground execution, main-thread work and reconnect** | Native process ownership has improved; `StreamingService` still extends `IntentService`, and Android lifecycle/background/notification-denial acceptance remains absent. | ANR, rejected background start, leaked process/context, stalled transfer or secret-bearing diagnostic. | High | CloudBridge UI/service lifecycle, foreground promotion and reconnect flow. | Use lifecycle-owned bounded asynchronous work and correct foreground promotion; bound reconnect state/output while preserving Internxt sign-in/TOTP. | Rotation, background/notification denial, timeout, malformed secret-bearing output and supported API/device matrix; device acceptance **NOT RUN**. | WP02/WP05 then WP10–WP12; actual API/firmware evidence in WP14. |
| **C10 — PARTIAL: SessionGuardian and updater outcomes** | SessionGuardian uses capability-based probes but currently logs an outer exception and returns WorkManager success; updater is fork-owned/bounded with pure policy tests, while scanner/worker/device integration remains open. | False health success, stopped/hidden monitoring, wrong release prompt or notification route. | High operational / Medium distribution | CloudBridge health worker and updater policy/runtime. | Persist truthful health-check outcome without silently stopping periodic monitoring; preserve cancellation; keep updater notification-only, fork-specific, bounded and failure-aware. | No-op/idle, transient-vs-auth, rate/cancel/failure, stable/prerelease, malformed release metadata, both intents and periodic recurrence tests; Android integration **NOT RUN**. | WP10/WP13; integrated acceptance before WP14/15. |
| **C11 — PARTIAL: delete undo and VCP semantics** | VCP now validates IDs/parents and waits for confirmed RCD mutation success; snackbar target policies and subtree-cache invalidation have source/tests, but the latest provider integration tests did not run. | Deletion of a different remote/path after navigation/dismissal, false mutation success, stale cache or overbroad URI grant. | High | CloudBridge file UI, delete coordinator, VCP and RCD callback ownership. | Capture immutable target before delayed UI, define explicit undo/commit states, retain grant on uncertain failure, and enforce containment/grant lifetime at provider boundary. | Consecutive snackbar/navigation/rotation/process death, cache descendants/siblings, failed/late RCD result, cross-app grant/revocation and unknown remote tests; current Java/JUnit/provider acceptance **NOT RUN**. | WP06/WP12 after WP05 interfaces; device/provider evidence before WP15. |
| **C12 — PARTIAL / RELEASE BLOCKING: release, diagnostics and documentation** | App pins Rareities/rclone immutably; release signing now requires and checks an expected X.509 SHA-256 fingerprint. Gradle policy and synthetic certificate match/mismatch tests pass, but the production fingerprint/key, signer continuity, artifact and CI provenance are not verified. OSS debug APKs are not release artifacts; WP14 gates and this crosswalk remain open. | Wrong identity/engine/signature, secret leak, unreproducible artifact or future maintenance based on false docs. | Critical for release | Both repositories, build/release/diagnostics and user documentation. | Reconcile C01–C12 and traceability docs; audit canary leakage/dependencies; prove ABI/native hashes and intended signer; preserve fail-closed release behavior and rollback record. | Fresh CI/PR, clean build, merged manifest, APK signature/ABI/native hash/provenance, redaction canaries, docs commands, dependency/license/advisory and rollback inspection. Production signer, Galaxy and Proton **NOT VERIFIED/NOT RUN**. | WP02/WP13, then WP14 and final WP15; no release before concrete user decision. |

## Explicitly unperformed review work

No external penetration test, dynamic app scan, device permission exercise, production-key
review, SBOM/advisory/license closure, full URI/provider audit, network-service exposure
test, native APK inspection, Galaxy S26 test or live Proton test was performed. These are
open work, not implicit passes. See TEST_REPORT.md and RELEASE_READINESS.md.

## Security, Reliability, and Engineering Quality Plan

**Status: WORKING PLAN, NOT FINAL; WP15 is not complete.** This plan is tied to the
current local source checkpoint `e6097f1dc4f7fba87440359039c84196d250c922` and the
evidence limits above. Update it after each blocking package and finalize only after WP14.

### 1. Threat and failure model

- **Assets:** user files and both Bisync baselines/versions; local/remote task, profile,
  filter and run state; Proton credentials, sessions and revision history; rclone config
  and wrapped passphrase; SAF grants and local/network shares; scheduler ownership and
  cancellation state; fork identity, pinned engine, build inputs and release artifacts.
- **Trust boundaries:** CloudBridge UI/database/workers/alarms; Android framework and
  process lifecycle; app-to-rclone process/config/IPC boundary; SAF/VCP URI and caller
  grants; local storage and removable media; remote backends and Proton API/library;
  GitHub branches/CI/dependencies; signing keys and release distribution.
- **Primary destructive failures:** one-sided overwrite/delete or lost conflict version;
  a stale/duplicate writer after cancellation, process death, import or restart; an
  unverified/overlapping backup or incorrect restore; stale preview/baseline acceptance;
  credential/config corruption or disclosure; path/URI escape or unauthorized serving;
  wrong engine/artifact/signing identity; scheduler work running after disable/delete;
  inaccurate updater/release claims.

### 2. Current-code findings

The nine material findings SR-01-SR-09 remain open at their stated scopes. WP10 commit
`e6097f1` now suppresses delivered alarms whose persisted target/type/time/weekdays
changed, gates new scheduled worker launches on persisted state, propagates trigger
metadata into follow-ups, and serializes current-process database edits against final
admission. It does not solve legacy WorkManager payload ambiguity, trigger ID reuse,
PendingIntent `long`-to-`int` collisions, multi-process admission, durable missed-run
coalescing, or tested missed-run behavior after boot/time changes (SR-09). These are residual release blockers,
not claims that all alarm races are eliminated. Current Android JUnit execution is
blocked before JUnit; Samsung and Proton acceptance are **NOT RUN**.

### 3. Architectural safeguards required before Bisync mutation

- Keep Bisync initialization/apply/recovery unavailable until provider-specific paired
  backup reservations are exclusive, durable, run-owned and revalidated at the actual
  mutation boundary.
- Verify exact copied bytes and metadata, retain both sides through finalization, recover
  after process/device interruption, and test exact restore from persisted evidence.
- Bind every operation to immutable profile/endpoint/engine/preflight/preview/run state;
  reject stale inputs and unresolved prior owners. A database manifest alone is not a
  provider reservation and a caller timestamp is not proof of visible user consent.
- Use one conservative ownership/locking policy across manual, scheduled, preview,
  provider/VCP, parent-child and remote aliases. Cancellation is not completion until the
  native child is joined or its unresolved state blocks conflicting work.
- Keep Proton session/revision decisions in the backend/library layer and validate with
  a verified disposable scope and official-client evidence before promotion.

### 4. Luna coding rules derived from this audit

- Read the master handoff, current package ledger and current source before resuming; do
  not infer completion from summaries or replay inherited audits blindly.
- Fix defects at the owning layer; independently test rclone/libraries before app
  integration. Preserve Bisync, Proton, scheduling, Obsidian requirements and useful
  CloudBridge features unless an explicit tested decision says otherwise.
- Treat persisted inputs as snapshots with owners/revisions. Fail closed on unknown,
  malformed, stale or unverified state; do not convert cancellation into success.
- Never delete/recreate user data to hide a protocol conflict. Use only verified,
  unique disposable test locations; the proposed `Proton:RoundSync-Test` path is not
  authorization to alter an existing directory.
- Record exact commit, command, environment, counts, failures and skipped/not-run tests.
  Test flakiness, compiler/archive errors and missing provider/device access are not passes.
- Keep patches reviewable, avoid blind cherry-picks, retain rollback boundaries, never
  stage `.android/`, and never claim APK/signing/release readiness without provenance.

### 5. Ordered security/reliability work packages

1. **WP08:** complete paired backup placement, durable provider proof, restart
   reconciliation, exact restore and mandatory pre-mutation authorization.
2. **WP09:** close backend/library correctness and dependency provenance independently;
   test exact app pin before any promotion; live Proton remains gated on verified scope.
3. **WP10:** resolve legacy-work migration without harming manual jobs; add durable
   trigger generations/identity, dispatcher/coalescing/missed-run policy, reboot and
   cancellation behavior; then prove Android lifecycle behavior.
4. **WP11:** only after WP08/WP10 interfaces pass, implement required Bisync/Obsidian UI
   honestly with a manual path and explicit foreground/permission behavior.
5. **WP12:** complete feature-survival and provider/URI authorization tests, not only
   bounded policy slices.
6. **WP13:** close independent CI, dependency/license/advisory review, signing identity,
   artifact provenance, ABI/native inspection and safe diagnostics.
7. **WP14:** execute the full local fault/race/soak/migration matrix, then verified
   disposable Proton and actual Galaxy S26 acceptance; retain exact artifacts and state.
8. **WP15:** reconcile every requirement/finding, refresh repositories/PR/CI, review
   rollback/provenance and issue a concrete no-go/go recommendation for human decision.

### 6. Regression strategy

- Unit-test each pure eligibility/path/version policy; run package tests and race/vet/
  formatting checks independently for changed Go libraries before app integration.
- Add deterministic barriers for alarm/edit/delete/import/worker/native-launch races,
  old WorkManager payload migration, trigger-generation reuse, PendingIntent identity,
  process-lock takeover, child cancellation/join and follow-up chains. Repeat each critical
  race at least 100 times; separately test real OS processes where required.
- Verify exact local byte/directory manifests across two-way create/modify/delete,
  conflicts, rename/case/Unicode, empty/inaccessible roots, partial listings, migration,
  fault injection, restart and exact restore. Run the specified mixed-operation soak and
  measure retained processes, handles, sockets, jobs, memory, logs and wake locks.
- Run Android JVM/instrumentation on the exact changed commit; exercise supported API
  boundaries and inspect merged manifest/packaged native ABI. Emulator results never
  substitute for Galaxy S26 One UI 8.5/9 and actual firmware/API recording.
- Use a unique disposable Proton area only after verifying scope; inspect official-client
  revisions and another-client edits. Provider tests absent credentials/scope are **NOT
  RUN**, not skipped passes.
- Re-run affected package regressions and the appropriate integrated suite after each
  root-cause fix; preserve failed iterations and retain final hashes/certificate evidence.

### 7. Residual risks and release blockers

WP03 preference import now runs a strict, bounded JSON preflight before Android/host JSON parsing, rejects duplicate decoded keys, trailing input, excessive nesting/numeric tokens, and accepts only one initial BOM for compatibility. A standalone JDK 17/JUnit harness passes 39 cases in 25 repetitions; Android-framework behavior and the modified DB instrumentation fixtures were not verified in that slice. Config-secret removal now treats a failed SharedPreferences commit as an error and invalidates the config-derived remote cache before the clear attempt; a separate standalone test passes both commit outcomes. Independent review found this is not safe end-to-end yet: direct import may report invalid after rename, and stale ciphertext may reload after restart. The code slice remains unaccepted until a recovery/revision-binding path and direct/ZIP/restart tests close that gap. Durable journal/key-loss/rollback requirements remain open.

WP08's mandatory verified mutation/recovery boundary, WP09 promotion/live-provider proof,
WP10 legacy scheduler/generation/identity and lifecycle, WP11 Obsidian implementation,
WP12 full feature/auth matrix, WP13 CI/signer/provenance, all WP14 acceptance, and final
WP15 reconciliation remain incomplete. Galaxy S26 access, Proton credentials plus a
verified disposable scope, clean current-source Android migration-test execution, PR/CI
evidence, production signing identity and an inspected release-candidate APK are
unavailable or unverified. `TEST_REPORT.md` lists local debug APKs signed by an ephemeral
debug key; those artifacts do not represent the newest unverified edits or meet release
provenance/signing gates. Therefore the release decision remains **NO-GO**; no
production-signed APK or release is claimed. This plan is not an external security audit
or certification.

### 8. Fresh source-audit findings (2026-09-26)

The current WP02 source audit remains open. It found that the exported share flow accepts
`file://` input and opens it through the app's resolver; current Authorization-header
redaction can leave Basic credentials or Digest attributes; asynchronous log appends can
race past the nominal file cap and `SyncLog.getLog` has no read bound; and exported
`MainActivity` accepts a reauthentication UI intent without a caller capability. The
reauth path was observed to launch UI, not to run a remote command. Final argv/endpoint
validation is still incomplete, but the audit did not demonstrate a dangerous exported
argument exploit. No tests were run for these findings. Close with URI/grant hostile-input
tests, full-header secret canaries, hard append/read bounds and public-route coverage.

For WP03, a bounded fix now preserves a ZIP import's remaining pre-import config snapshot
unless the import committed or config restore completed. Its pure policy test passes 3/3;
the app/Gradle integration remains unverified. This avoids losing one recovery artifact
but does not address the more serious unresolved mismatches among config bytes, separate
secret ciphertext and revision state, nor the lack of process-death reconciliation. Keep
the release **NO-GO**.

### 9. WP02 bounded remediation status (2026-09-27)

Source-level mitigations now reject external share inputs unless they are `content://`
URIs with an authority, before display-name queries or stream opens; redact Basic and
Digest Authorization header lines and quoted JSON authorization values; serialize
diagnostic appends behind byte caps; and bound persisted sync-log reads. Focused helper
tests pass 20/20 and selected log classes compile against Android 35 stubs. The share
Activity itself, provider-grant behavior, public-intent hostility, and full app build were
not verified because the current Gradle run failed on a cached dependency JAR
`AccessDeniedException`. These source slices do not close WP02; retain the residual-route,
argv-validation, caller-authorization, instrumentation, and full-build gates above.

## 2026-09-27 - Follow-up: own-provider boundary and bounded RCD response

- The exported incoming-share path now rejects `content://` authorities belonging to the app itself (including its file, VCP, and startup authorities) before querying or opening the URI. Android documents that an app's provider has full read/write access for components running as that app ([content provider security](https://developer.android.com/guide/topics/providers/content-provider-basics)); this closes the same-UID URI shortcut at this route, but does not prove the route's caller/grant behavior end-to-end. Focused URI-policy tests: **3/3 PASS**.
- RCD success and error response streams now have byte ceilings (4 MiB and 64 KiB) and calls have a 30-second total timeout. The bounded-reader helper passed **3/3** tests; full RCD/app compilation and RPC integration remain unverified.
- Current combined standalone helper/policy suite is **29/29 PASS** (WP02 21, WP03 8). It is not Android instrumentation. Exported reauth/caller capability, hostile public routes, final argv validation, config/secret process-death reconciliation, and provider grants remain unresolved. Full Android build was blocked by cached/transformed AndroidX `AccessDeniedException`; Galaxy S26/One UI and live Proton tests are **NOT RUN**. Release remains **NO-GO**.

## 2026-09-27 - WP03 cross-store import and restart re-audit (NOT ACCEPTED)

- ZIP import still mutates SQLite, SharedPreferences, and `rclone.conf` in separate operations, with preimages held partly in process memory. A process death between stores bypasses the catch/rollback path; startup has no import journal/reconciler. `ConfigRevisionStore` durably sets `mutation_pending`, but no startup recovery clears or classifies a marker left by process death.
- Config-generation coverage is incomplete at runtime boundaries: some `getRuntimeProcess` callers omit expected revision; an already-running RCD process can retain the old config after import and is not stopped/restarted by replacement. A matching remote name may therefore dispatch against stale daemon state.
- Direct import can replace config bytes before revision finalization fails and then report invalid config without restoring the preimage. ZIP rollback restores config bytes but not the prior passphrase binding; restore still has delete-before-rename exposure. Reset may stop after deleting config but before clearing other app state.
- A ZIP supplied through a mutable SAF/cloud URI is reopened separately for database, preferences, and config extraction; source bytes can change between reads. Encrypted import with a new password cannot be validated before the post-import password prompt.
- Existing focused tests cover pure generation and rollback policies only; they do not simulate Android Keystore, durable preferences, multi-store process death, RCD restart, or import/reset fault boundaries. WP03 remains **PARTIAL / NOT ACCEPTED** until a durable journal, truthful direct/ZIP/reset outcomes, immutable staged archive snapshot, restart reconciliation, and end-to-end fault instrumentation exist. Release remains **NO-GO**.

## 2026-09-27 - WP03 config replacement follow-up (PARTIAL; independent review findings addressed in source)

- **Mitigated:** the rollback path no longer deletes the active config before renaming a snapshot. Staged config replacement and restore use Android `AtomicFile`, and the app attempts AtomicFile recovery before cached-config reads/native launches. A failed write is not called safe unless the exact prior bytes are read back. The ZIP rollback token is recorded before revision finalization; config and saved-secret restoration are attempted independently where the file snapshot is known restored.
- **Mitigated, with fail-closed behavior:** an invalidation commit failure carries its opaque preimage to the owner. An attempted credential restore is considered confirmed only when SharedPreferences reports a successful commit and the values match; in-memory mutation after `commit()==false` leaves the revision barrier pending.
- **Validation:** focused JDK 8/JUnit 4.13.2 suite **21/21 PASS** and scoped diff check **PASS**. Android Gradle compilation did **NOT PASS before app sources** because Windows denied access to a transformed AndroidX JAR. No integration or process-death test ran.
- **Residual critical risk:** SQLite, default preferences, config bytes and config-secret metadata still lack one durable encrypted journal written before the first mutation. A process death can leave mixed stores; pending markers have no startup reconciliation; direct-import/reset recovery and key-loss behavior remain incomplete. These remediations close only the reviewed in-process/file-replacement defects, not WP03 acceptance. Release remains **NO-GO**.

## 2026-09-27 - WP03 reset ordering and crash boundary

- Reset's mutation barrier now precedes secret invalidation; uncertain finalization is latched fail-closed in-process, and conditional secret restoration requires a positively present config with no AtomicFile backup. The reset callback avoids API-24-only functional-interface metadata.
- Independent review confirms a remaining recovery boundary: process death after secret invalidation but before config deletion loses the in-memory token. The revision barrier remains pending, preventing stale operations, but no durable journal/startup reconciler can restore or classify the saved-secret/config transaction. This can strand a valid config and blocks WP03 acceptance.
- The 29/29 focused helper suite does not cover process death, Android SharedPreferences disk semantics, AtomicFile framework behavior, or full import/reset callers. Gradle did not reach app source compilation; do not elevate local helper evidence into integration/security acceptance.

## 2026-09-27 - WP03 ZIP import preimage/startup audit (NOT ACCEPTED)

- ZIP import reopens the caller-controlled content URI for database JSON, preference JSON and config extraction. Stage one bounded immutable archive copy and validate it before mutation to avoid inconsistent components from a changing SAF/cloud source.
- Current rollback export is not a full database preimage: it covers task/trigger/filter data while replacement/reconciliation also touches profiles and run state. Snapshot exact affected rows/IDs, preference keys with presence/type/value, config bytes/absence, and encrypted-secret generation/ciphertext tuple.
- Durable encrypted journal must be authenticated before the first store mutation. Use a separate Keystore key; a missing/invalidated key or bad tag must preserve every preimage and block unsafe startup work. Recovery must precede DB/run reconciliation and early provider/worker/config use; `VirtualContentProvider` is an explicit gate, not just a later UI concern. Android documents that `Application.onCreate()` precedes activities/services/receivers but excludes content providers; see [Application API](https://developer.android.com/reference/android/app/Application#onCreate()) and [provider initialization guidance](https://developer.android.com/guide/topics/providers/content-provider-creating).
- A durable commit decision must precede scheduler requeue/finalization; before that decision restart restores the exact preimage, after it restart finishes commit. Keep reconciliation idempotent and retain claims/journal on any unconfirmed state. Proposed storage caps remain review suggestions until measured.
- Reset remains separate because `clearApplicationUserData()` destroys the prior app data; do not describe it as rollback-capable after clearing begins. No process-death or current Android integration tests establish these contracts yet.
