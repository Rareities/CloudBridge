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
- **Config and credentials (WP03):** bounded import validates before mutation and uses
  rollback snapshots. The rclone config is not wholly encrypted by CloudBridge. The cached
  config password is AES-GCM ciphertext wrapped by an Android Keystore key. A key-loss
  error preserves ciphertext/config and requires explicit recovery; no silent clear or
  downgrade is intended. Live device Keystore and fault-injection acceptance are **NOT RUN**.
- **Release signing (WP13 slice):** production packaging is fail-closed when the production
  key/credentials are unavailable; the debug key is not an acceptable production fallback.
  The current workflow does not automatically publish a release. No production signer or
  certificate continuity has been verified.
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
| SR-06: Supply-chain and public delivery controls are incomplete | 2026-09-25 refresh found default branches unprotected and no observed Actions runs; signing, dependency/advisory/license and artifact gates remain incomplete | Changes could be promoted without independent checks or traceable provenance | **High / release-blocking** | Both repositories and release workflow | Establish reviewed PR checks and dependency/license/advisory evidence; pin immutable engine input and attest artifact inputs; no blind cherry-picks/history rewrite | Fresh CI on exact PR commits, independent build/test, inventory and artifact provenance verification | WP13 then WP15 |
| SR-07: App config at-rest confidentiality must not be overstated | Current ADR/security docs say rclone.conf itself is not whole-file encrypted; only stored passphrase is Keystore-wrapped | Users could assume stronger at-rest protection than provided | **Medium / user transparency** | CloudBridge config and docs | Keep claims precise; design versioned encrypted config only with migration, rotation and recovery tests; do not silently alter rclone's contract | Device storage/key-loss/migration tests and backup/import review | WP03/WP13; docs accurate meanwhile |
| SR-08: Optional update checker must preserve fork/channel identity | `UpdateWorker.kt` and `AppUpdateNotification.kt` use centralized Rareities/CloudBridge endpoints; preference defaults off and no installer path is present. Stable installs ignore prereleases. The bounded scanner caps pages/body/changelog and preserves prior state on incomplete results; HTTP calls now have a cancellation hook and total timeout. Notifications use the validated selected tag or safe release index. Kotlin compiles, but scanner/worker tests are **NOT RUN**. | A bad tag/API response, channel mix-up, cancellation race or incorrect notification intent could still mislead users | **Medium / distribution integrity** | CloudBridge updater owner and project identity | Keep fork URLs centralized, parse strict semantic versions, honor stable/prerelease metadata, bound pagination and body reads, cancel in-flight requests, use the selected tag URL, and keep the updater notification-only | The prior nine URL/SemVer tests passed before the selected-tag routing follow-up. Current scanner tests and Android HTTP/preferences/notification-intent behavior are unverified; refresh release availability before distribution | WP13 PARTIAL; block distribution until integrated verification |
| SR-09: Legacy trigger work and trigger identity are not migration-safe | Independent review of current WP10 commit `e6097f1` confirms stale alarm configuration is rejected, but pre-upgrade WorkManager requests have no trigger metadata and are indistinguishable from manual requests; SQLite trigger IDs can be reused; `long` IDs are narrowed to `int` for PendingIntent identity. Full Android JUnit/instrumentation did not run. | A legacy scheduled job may run after disable/delete; delete/recreate can admit stale work; colliding request codes can replace/cancel another alarm | **High / release-blocking scheduler safety** | CloudBridge trigger/database/WorkManager owner | Define an evidence-backed migration for existing jobs without blanket-cancelling manual work; add durable unique trigger-generation identity and lossless alarm identity; preserve pending/manual work and explicit missed-run policy | Old-payload migration tests; delete/recreate and same-config generation tests; Long-ID PendingIntent identity/cancellation tests; barrier-driven worker/edit/delete/import races; instrumentation and S26 lifecycle runs | WP10 before WP11/WP14; do not claim complete scheduling until Android and device acceptance |

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

The eight material findings SR-01–SR-08 remain open at their stated scopes. WP10 commit
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

WP08's mandatory verified mutation/recovery boundary, WP09 promotion/live-provider proof,
WP10 legacy scheduler/generation/identity and lifecycle, WP11 Obsidian implementation,
WP12 full feature/auth matrix, WP13 CI/signer/provenance, all WP14 acceptance, and final
WP15 reconciliation remain incomplete. Galaxy S26 access, Proton credentials plus a
verified disposable scope, clean current-source Android Java/JUnit execution, PR/CI
evidence, production signing identity and an inspected native APK are unavailable or
unverified. Therefore the release decision remains **NO-GO**; no APK or release is
claimed. This plan is not an external security audit or certification.
