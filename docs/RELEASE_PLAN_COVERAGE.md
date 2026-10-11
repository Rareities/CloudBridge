# Final master-plan reconciliation

Compared against all 45 work packages in the supplied 9 October 2026 plan.
Implemented mechanisms and external acceptance gates are distinct.

| Package | Final disposition |
|---|---|
| DEP-001 | Immutable reviewed engine pin with packaged SHA/hash verification. |
| DEP-002 | Go 1.27.2, Android NDK/API/bionic PIE, four ABIs, provenance and 16 KiB ELF checks. Physical matrix remains open. |
| DEP-003 | Reviewed upstream intake, isolated candidate CI and separate protected release publisher. |
| PD-001 | Per-session callbacks with owner, UID, mailbox and generation tests. |
| PD-002 | Persisted rotation adoption, guarded atomic tuple saves and competing-process/failure tests. Live longevity remains open. |
| PD-003 | Authenticated exact-name fallback and traversal/upload ambiguity guards. Live modern-client objects remain open. |
| PD-004 | Strict plaintext size/common/per-file hashes; invalid metadata stops before mutation. |
| PD-005 | Conditional: unknown drafts preserved; no automated repair without ownership evidence. |
| PD-006 | Contained reviewed libraries and negative signature/block tests. Cross-client crypto migration remains external. |
| PD-007 | Download Close propagation and cancellation ownership implemented/tested. |
| PD-008 | Account pause, debounced action, transient OTP and explicit reconnect implemented. Real account/challenge matrix remains open. |
| PD-009 | Conservative shared native admission and fresh managed runs; no aggressive health scanning. |
| BS-001 | Versioned serialization, stable task UUIDs and protected legacy/unknown modes. |
| BS-002 | Private durable baselines, atomic app state and retained recovery evidence. |
| BS-003 | Unique work, mutation/native leases, pair/basename guards and ordered baseline identity. |
| BS-004 | Separate preview and explicit time-limited, fingerprint-bound initialisation approval. |
| BS-005 | Strict comparison, retained conflicts, percentage limits, access/backup checks and ambiguous-name rejection. |
| BS-006 | Exit/cleanup barriers, interruption state and reviewed recovery; no automatic resync. |
| BS-007 | Version-validated option/value rows, concrete tuning snapshots, effective argv and connection-budget validation. |
| BS-008 | Shared DST/timezone calculations, intervals, constraints, quiet hours, coalescing and bounded admission retries. |
| BS-009 | State/history, affected-file preview, next run, cancel and connection/recovery controls. |
| CB-001 | Truthful native results in worker, provider and stream paths. |
| CB-002 | Idempotent terminal results, concurrent pipe draining and process-owned cleanup. |
| CB-003 | Missing/unknown mode guards without destructive one-way fallback. |
| CB-004 | Bounded parse-first import, encrypted component validation and journalled rollback. |
| CB-005 | Checked civil/interval scheduling and persisted next occurrence. |
| CB-006 | Bounded follow-ups with self/cycle protection. |
| CB-007 | Provider completion requires confirmed native success; timeouts/cancellation fail. |
| CB-008 | Current URI authority, encoded names, safe descendants, unavailable remote handling and rotation. |
| CB-009 | Notification-only channel-aware release metadata; no unattended APK installer. |
| CB-010 | Nullable references, validated selections and stable persisted identities. |
| SEC-001 | Native encrypted config with Keystore-wrapped unattended access. Optional per-run biometric mode deferred. |
| SEC-002 | Scoped shortcut authorisation and explicit consent. |
| SEC-003 | Collection-time redaction, bounded batched logs and restricted notifications. |
| SEC-004 | Dedicated fork certificate, master-only signing environment, no debug fallback and verified APK identity. |
| SEC-005 | Authenticated loopback RC and explicit authenticated TLS for supported LAN protocols. |
| PRIV-001 | Fully encrypted new backup contents, wrapped proxy passwords and private bounded history. |
| PRIV-002 | System resolver replaces raw DNS bypass. OEM/VPN/IPv6/private-trust matrix remains external. |
| PWR-001 | Demand-driven health; optional periodic probes disabled by default. |
| PWR-002 | Tested idle RC retirement without readiness scans; grace period not claimed optimal. |
| PWR-003 | Accountable active locks, cancellation and timeout handling. Physical quotas remain external. |
| PERF-001 | Conservative bounded native admission, coalesced UI and bounded logging. Hardware-tuned budgets remain external. |
| PERF-002 | Reproducible host/emulator measurements supplied; physical energy/thermal acceptance remains open. |
| ARCH-001 | Small process/config/state/scheduling boundaries, preserving existing Views and SQLite. |
| UX-001 | Layered controls, explicit errors, touch targets, shortened labels and bottom clearance. Broader accessibility remains open. |

## Upstream and alternate work

The alternate app's reconnect, presets, encrypted-backup and Android-resolver
ideas were retained in the tested architecture. Its separate vault format and
optional authenticated unlock were not blindly substituted for recoverable
native configuration encryption. The alternate engine's API lock ordering,
late-winner adoption and negative fixtures were retained; tuple persistence now
uses a complete guarded transaction and exact original section metadata.

Both automated upstream-review branches contained the same two official
release-tooling/documentation changes. These are included through the official
master merge. CloudBridge's parent has not advanced from the original baseline.
No open PR is adopted solely because its title claims a fix. Signature-bypass
proposals remain rejected. Existing consumed-reader, Android/Internxt and bisync
state protections are preserved.

The signed published builds are release candidates. Live Proton and physical
device checks need resources unavailable in this authorised environment; those
gates are not represented as passed or hidden behind helper-only tests.
