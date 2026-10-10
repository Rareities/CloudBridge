# CloudBridge / rclone roadmap execution record

This record reconciles all 45 work packages in [the attached master roadmap](MASTER_ROADMAP.md). The roadmap is retained verbatim as the specification; its original audit evidence and proposed tests are historical. Implementation status below supersedes the earlier core-only delivery boundary and the historical audit's “nothing implemented” statement.

CloudBridge's review branch `codex/proton-bisync-integration` pins **Rareities/rclone `fc35e4a8c75211abf6f1bca367af482a0aec68f7`** through the existing URL/SHA settings. No generated source patches are applied. Review proposals: [CloudBridge #1](https://github.com/Rareities/CloudBridge/pull/1), [rclone #2](https://github.com/Rareities/rclone/pull/2). Neither proposal is merged or released.

“Implemented” means code and available local verification exist, not that every device/provider acceptance criterion has passed. “Partial” identifies remaining functionality or unavailable acceptance evidence. “Fail closed” identifies the roadmap's conservative alternative when service ownership or compatibility cannot be proven.

## Work-package reconciliation

| Package | State | Concrete result and remaining boundary |
| --- | --- | --- |
| DEP-001 | Implemented | Immutable owned engine pin; origin/SHA/dirty-cache validation; retained Internxt/DNS fixes. |
| DEP-002 | Partial: device gate | Go 1.26, Android CGO PIE, four ABIs, 16 KiB ELF alignment, source/dependency/toolchain hashes and APK verifier. Android 23–36 execution and real 16 KiB devices still require acceptance. |
| DEP-003 | Implemented; workflow execution gate | Immutable actions; separate read-only preparation/testing runners and trusted proposal publication; mandatory artifacts and lease checks. Four static trust-boundary regressions pass. Real malicious-candidate workflow execution has not run. |
| PD-001 | Implemented | Per-session callbacks; account/password/salted-key/token-generation guards; stale callback isolation and race tests. |
| PD-002 | Partial: live gate | Contained API/Bridge patches; bounded rotation adoption; canonical cross-process config guard; atomic tuple save; persistence failure propagation; refresh rejection preserves a delayed winner. Fake-server and separate-process tests pass. Live refresh-interval/challenge/revocation matrix remains. |
| PD-003 | Partial: live gate | Verified exact decrypted-name lookup for backend and bridge active/draft upload paths; mixed legacy/modern ambiguity rejection; signature errors remain fatal. Official-client overwrite/history fixtures remain. |
| PD-004 | Implemented; live gate | Plaintext size/SHA1 validation and explicit missing metadata failure; bisync preflight rejects unsafe metadata and normalized-name collisions. Live provider metadata coverage remains. |
| PD-005 | Fail closed | Unproven draft replacement is disabled even when the legacy option is set. No committed-object/trash deletion. Owned-draft recovery is unavailable until ownership can be authenticated. |
| PD-006 | Partial | Existing crypto fixes retained with round-trip/negative fixtures. No signature bypass. Future official crypto migration and official-client/account-key interoperability remain unresolved external dependencies. |
| PD-007 | Implemented | Actual reader Close preserved across limited reads, once-only cleanup regression fixtures. |
| PD-008 | Partial | Existing password/2FA forms, private secret handling and conservative error classification retained; no challenge bypass or unattended password loop. Unified per-account NeedsUserAction/reconnect UI and disposable account matrix remain unfinished. |
| PD-009 | Conservative alternative | Global app admission bounds initialization; fresh CLI contexts avoid stale reconciliation; confirmed provider mutations invalidate caches. Unsafe shared-client pooling was removed. Per-compatible-remote singleflight is not delivered. |
| BS-001 | Implemented | Versioned stable UUID job/options/state/run models, database migration and serialized options; legacy unsupported modes fail safely. |
| BS-002 | Implemented; device gate | Private durable UUID workdirs, ordered endpoint/options/config/engine fingerprints; separate previews; preserved interrupted state. Reboot/disk-full/process-kill acceptance remains. |
| BS-003 | Implemented; stress gate | Unique/coalesced work, retained parent/child admission, exclusive engine guards with owner-aware renewal/removal. Conservative one-native-operation policy; contention fails visibly rather than automatically queues. |
| BS-004 | Implemented | Native dry-run initialization, explicit LOCAL precedence and digest-bound approval; changes require review again; preview is not an atomic provider snapshot. |
| BS-005 | Partial | Size/checksum preflight, preserved conflict copies, percent deletion ceiling, explicit no-follow access-marker setup and paired external per-run backup roots. Pilot supports native local↔Proton; SAF, other pairs, filters and rename-aware modes remain blocked. |
| BS-006 | Implemented; device gate | Durable running/recovery state; bounded TERM/force cleanup; cancellation never promotes a baseline or triggers automatic resync. Real platform termination and recovery fault injection remain. |
| BS-007 | Partial | Versioned typed options and positive custom allowlist, deterministic override precedence, SHA-keyed native capability discovery, reserved dangerous flags blocked, redacted effective argv. Full filter migration/global option schema and preset snapshots remain unfinished. |
| BS-008 | Implemented; device gate | Shared durable due/signature/epoch scheduling, quiet hours/timezone/constraints, optional exact alarms with permission fallback, missed-run coalescing, classified non-bisync retry. Doze/force-stop/exact-alarm device acceptance remains. |
| BS-009 | Implemented | Bounded atomic private run history; phases/counters/native terminal exit; terminal record before baseline promotion; status and history UI; throttled notification updates. |
| CB-001 | Implemented | Exit-based worker outcomes, simultaneous pipe draining, batch stops on failure, observed terminal outcomes. |
| CB-002 | Implemented; device gate | Idempotent cleanup, retained child ownership until confirmed exit, bounded native deadlines and transfer-lock lifecycle. Android foreground-service deadline acceptance remains. |
| CB-003 | Implemented | Missing/deleted/unknown task and mode guards prevent native execution and phantom success. |
| CB-004 | Implemented; fault-injection gate | Bounded component validation, transactional restore journal, config/settings/unlock/proxy-secret rollback, epoch invalidation, RC stop/reap and directory fsync barriers. Real process-kill/power-loss tests remain. |
| CB-005 | Implemented; device gate | Civil-time/DST scheduling, wide arithmetic, persisted next due, boot/timezone reconciliation and constrained WorkManager dispatch. |
| CB-006 | Implemented | Follow-up budget/cycle protection, unique work and original config epoch retention. |
| CB-007 | Implemented; provider gate | Async mutation joins real terminal result; timeout/cancellation requests native job stop; failed operations do not revoke grants or invalidate success caches. Upload close/download EOF check native exit. Real Android document callers remain untested. |
| CB-008 | Implemented; picker gate | BuildConfig authority and DocumentsContract parsing, null/malformed validation, first-colon split preserving Unicode/backend name semantics; API 23 compatible tree detection. Device picker/grant acceptance remains. |
| CB-009 | Implemented | Owned release source, draft/channel filtering and refreshed eligible-version metadata; notification-only update flow retained. |
| CB-010 | Implemented | Nullable reference handling, deletion reference clearing, validated imports and coordinated versioned database fields. Android SQLite migration/fault acceptance remains. |
| SEC-001 | Implemented; Keystore gate | Automatic native plaintext-to-encrypted migration before process launch; wrapped unlock; optional device-credential authentication window; locked background operations fail closed; imports preserve authenticated policy. Device key loss, expiry and reboot acceptance remains. |
| SEC-002 | Implemented | Random scoped shortcut capability and durable revocation on task identity/import. Old ID-only shortcuts require recreation. |
| SEC-003 | Implemented; retention boundary | Private bounded redacted logs/support summary, secret fields excluded from state/autofill, proxy password migrated into Keystore-wrapped storage, private lockscreen notifications. Historic external logs are not automatically erased. |
| SEC-004 | Implemented; external signing gate | Release builds reject absent credentials and Android debug certificates. Production key provisioning/rotation and CI secrets were not performed. |
| SEC-005 | Conservative alternative | Local serving rejects LAN until TLS is supported, validates bind/root/port and keeps passwords out of argv/state. RC remains loopback/authenticated. LAN TLS is not implemented. |
| PRIV-001 | Implemented | AES-encrypted complete backups include task/path/settings metadata and unlock entry; bounded exact entry whitelist, integrity validation and wrong-password/tamper tests; minimal secret-free support summary and bounded history. Legacy ZIP import remains separately validated. |
| PRIV-002 | Partial: network gate | Platform DNS/private DNS behavior preserved unless explicit override; proxy secret wrapper shared by CLI/RC; Android Conscrypt/system CA fallback with certificate verification. VPN/IPv6/private DNS/proxy/custom trust device matrix remains. |
| PWR-001 | Implemented | Health checks default off; bounded selected work and conservative auth-error handling; no always-on health timer. |
| PWR-002 | Implemented; device gate | Demand-counted RC requests/jobs/provider streams, 60-second idle grace, lazy restart, bounded completed summaries and handler cleanup. Real idle/stream lifecycle traces remain. |
| PWR-003 | Implemented; device gate | Partial lock-acquisition cleanup, release after native exit, task deadlines and Android-compatible force termination; WorkManager upgraded for foreground deadlines. Actual OS callbacks/Doze remain. |
| PERF-001 | Conservative alternative | One native operation admission, bounded typed transfer/buffer/checker budgets, bounded pipes and coalesced progress with terminal events retained. Low-memory/device throughput acceptance remains; no speed or battery improvement claimed. |
| PERF-002 | Partial | Repeated disposable host benchmark and native safety fixtures, exact binary/source hashes, CPU/RSS/context-switch measurements; Android acceptance suite and capture scripts. Physical energy/network/cold-cache/provider benchmarks remain unavailable. Native NFC/NFD collision fixture still fails and app preflight blocks it. |
| ARCH-001 | Partial | Narrow admission/process deadline/async result/demand/capability/history/schedule interfaces extracted while retaining facade/SQLite/Views. A complete ProcessRunner/JobCoordinator migration remains staged work. |
| UX-001 | Partial | Bisync safety approval, advanced grouped controls, effective redacted command/history/access-marker setup, schedule constraints/next due and vault/backup controls. Device accessibility/rotation/usability tests and fuller endpoint/recovery presentation remain. |

## Verification evidence

Available checks include Go race tests for Proton, Internxt, DNS and bisync lock behavior; explicit nested API/Bridge fake-server, modern-name, ambiguity, draft-safety and negative-crypto fixtures; a two-process token persistence race; Go vet; workflow trust regressions; Android JVM tests/lint; four native ABI builds; APK hash/source/ELF verification; and compilation of the Android instrumented test APK. Final counts/results are recorded in the draft PR. Compiling instrumentation is not running it.

Final pinned verification: `testOssDebugUnitTest lint assembleOssDebug assembleOssDebugAndroidTest` succeeds at engine `fc35e4a8c75211abf6f1bca367af482a0aec68f7`; **106 JVM tests, zero failures/errors/skips**. Lint reports zero current errors and 170 warnings; the existing baseline suppresses six errors and 427 warnings. All five APKs pass [independent extraction/hash/ELF/build-info verification](validation/apk-verification.json), including source identity, Android CGO PIE, platform interpreter and 16 KiB alignment. A prior stale incremental packaging result was rejected, fixed with engine-SHA consumer invalidation, and verified again across an actual pin change in one invocation. Instrumentation is compiled, not executed.

The [final paired benchmark evidence](validation/PERF-002.md) has 48 completed host samples and 14/16 native safety cases passing. Two normalization-collision cases fail, so the benchmark exits nonzero and is not acceptance. The app's collision-preflight regression passes. No battery or universal efficiency claim is made.

The parent rclone commit contains released dependency baselines, licenses, exact baseline hashes and reviewed patch lineage in `third_party/PROTON_PATCHES.md`. No moving external development branch supplies dependencies. Shared-client pooling was explicitly removed after review found unsafe lifetime ownership.

[Validation scripts](../scripts/validation/README.md) distinguish warm-cache host measurements from Android/device/provider acceptance. Native local benchmark normalization collision failures are retained as failures; application `BisyncListingSafety` rejects this case before mutation. No production data, configured live cloud account, installed application, merge, release or signing-key rotation was used.

## Remaining execution and release gates

The entire roadmap's acceptance is **not complete**. Functional gaps are explicitly PD-008 unified account action/reconnect, broader BS-005 pair/filter support, remaining BS-007 schema/presets/filter migration, staged ARCH-001 orchestration migration and fuller UX-001. Conditional draft recovery and LAN TLS remain safely disabled. Future Proton crypto migration needs supported interfaces and verified official-client fixtures.

No Android device/emulator or disposable Proton credentials are provisioned in this workspace. Therefore device execution, Keystore/restore crash fault injection, Android scheduling/SAF/cancellation behavior, official-client compatibility, long-lived refresh, ownership evidence and physical power/network acceptance cannot be claimed. Production signing and human release review remain external gates. The local-to-Proton pilot stays constrained until those gates pass.
