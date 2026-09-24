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
| SR-01: Bisync preservation and recovery gate is incomplete | WP08 ledger and app source: endpoint classification does not authorize backup placement; complete app-owned run manifest/reservation/restore lifecycle is not evidenced | Initialization/recovery could overwrite data without a collision-free, restart-surviving and verifiable copy, or act on stale endpoint identity | **Critical / release-blocking** | CloudBridge orchestration + rclone native semantics | Keep initialization/apply/recovery unavailable; prove path-specific placement and implement unique run-owned backups, durable manifest, restart/restore and mutation-boundary checks | Exact bytes/listings across empty/populated roots, conflicts, late edits, collisions, permission/disk/network failure, kill boundaries, restart and restore; then integrated/device tests | WP08 after WP07/prerequisites; WP09 and WP14 before release |
| SR-02: Production signing identity and artifact provenance are unknown | No production APK, certificate fingerprint, signer continuity, native binary hash or release provenance is present | Artifact could be signed by the wrong identity or contain an unexpected engine; publisher continuity cannot be verified | **Critical / release-blocking** | CloudBridge release/build pipeline and key custody | Obtain authorized production key through the defined secure channel; record intended certificate, immutable sources/toolchains, ABI/native provenance and hashes | Authorized release build; inspect signer, manifest, ID/version, ABI/native binary and notices; verify provenance and rollback artifact | WP13 after core/WP14; human release decision last |
| SR-03: VCP/URI-grant and local serving surfaces lack the required authorization audit | FEATURE_SURVIVAL_MATRIX.md marks VirtualContentProvider, broad grant option and serving modes as present but unaccepted; caller/grant and network-binding matrix is absent | Overbroad URI access, path escape, unintended LAN exposure or data disclosure | **High / open** | CloudBridge provider, IPC and serving owners | Trace every provider/service surface and grant lifetime; define caller, URI containment, read/write and bind policy; fail closed on unknown callers/paths | Instrumented cross-app grant/revocation/path tests, permission denial and localhost/LAN/auth tests across supported APIs | WP12 after WP05-WP11 contracts stabilize; before WP15 |
| SR-04: Proton correctness and credential/session behavior are not integrated/accepted | WP09 partial; candidate dependency tests are separate from app-pinned engine; live Proton NOT RUN | Session collision/refresh or revision behavior could cause auth loops, lost updates, orphaned drafts or unsafe retry | **High / release-blocking** | rclone Proton backend/libraries; CloudBridge consumes typed outcomes | Close independent backend/library fixes and compatibility review before integration; never wipe credentials; verify via official client using verified disposable scope | Multiple accounts, refresh races, cancellation/worker cleanup, revisions, external rename, crypt layering, rate/auth errors; then disposable-vault round trips and restore | WP09 before integration; WP14/15 held without live evidence |
| SR-05: Scheduler background behavior and Obsidian workflow are unaccepted | WP10 partial; no complete persisted dispatcher/coalescing/missed-run policy. Source search found no explicit Obsidian integration | Duplicate/missed/late work or launch after failure; required workflow may be absent or misrepresented | **High / release-blocking core feature risk** | CloudBridge scheduler/UI and app integration | Finish one persisted dispatcher and run ownership; identify and implement/test Obsidian entry/lifecycle without continuous polling or AccessibilityService abuse | Deterministic races, process recreation, cancellation, reboot/force-stop/doze/permissions and actual Galaxy S26 API/firmware | WP10 then WP11; device gate WP14 |
| SR-06: Supply-chain and public delivery controls are incomplete | 2026-09-25 refresh found default branches unprotected and no observed Actions runs; signing, dependency/advisory/license and artifact gates remain incomplete | Changes could be promoted without independent checks or traceable provenance | **High / release-blocking** | Both repositories and release workflow | Establish reviewed PR checks and dependency/license/advisory evidence; pin immutable engine input and attest artifact inputs; no blind cherry-picks/history rewrite | Fresh CI on exact PR commits, independent build/test, inventory and artifact provenance verification | WP13 then WP15 |
| SR-07: App config at-rest confidentiality must not be overstated | Current ADR/security docs say rclone.conf itself is not whole-file encrypted; only stored passphrase is Keystore-wrapped | Users could assume stronger at-rest protection than provided | **Medium / user transparency** | CloudBridge config and docs | Keep claims precise; design versioned encrypted config only with migration, rotation and recovery tests; do not silently alter rclone's contract | Device storage/key-loss/migration tests and backup/import review | WP03/WP13; docs accurate meanwhile |
| SR-08: Optional update checker targets the historical upstream project | `UpdateWorker.kt` requests `api.github.com/repos/thies2005/CloudBridge/releases`; `AppUpdateNotification.kt` links to `github.com/thies2005/CloudBridge/releases/latest`; preference defaults off and no installer path is present. The worker comment now matches `UpdateManager`'s configured 14-day connected-network interval. | Users could receive a notification for the wrong project/version or mistake upstream releases for this fork | **Medium / distribution integrity** | CloudBridge updater owner and project identity | Before distribution, retarget both URLs to Rareities and verify parsing/channel semantics, disabled state, empty/error responses and notification destination; keep notification-only behavior | Unit tests for exact owner/repository URLs, version/tag edge cases, API failures, opt-out, and notification link; device test before release | WP13 after core packages; block fork distribution until verified |

## Explicitly unperformed review work

No external penetration test, dynamic app scan, device permission exercise, production-key
review, SBOM/advisory/license closure, full URI/provider audit, network-service exposure
test, native APK inspection, Galaxy S26 test or live Proton test was performed. These are
open work, not implicit passes. See TEST_REPORT.md and RELEASE_READINESS.md.
