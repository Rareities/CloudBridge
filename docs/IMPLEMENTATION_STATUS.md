# Proton and bisync implementation status

Review branch: `codex/proton-bisync-integration`. This is an implementation and
validation record, not a claim that the entire master roadmap is finished.
The existing audit backlog remains useful; the record below identifies delivered
work and release gates. A full rewrite is not justified by these changes.

## Fork linkage and build

CloudBridge uses `https://github.com/Rareities/rclone.git` at immutable commit
`26f6a1c441ed154de77ae7fb9e5f67d45b25bfc5`, published in
[Rareities/rclone#2](https://github.com/Rareities/rclone/pull/2).
No fetched source patches are applied. Cache origin, cleanliness and resolved SHA
are checked. Go 1.26 and Android NDK API 23 build native PIE executables for all four
ABIs with 16 KiB alignment. The packaged `rclone-build.json` records engine identity,
binary SHA-256, dependency input hashes and toolchain settings. Defaults advance
together to app version 1.0.2 and version code 30.

Release signing rejects absent credentials and Android debug certificates. Signing
credentials and production signing-key rotation are external release prerequisites.
This branch does not merge, publish a release, or change installed applications.

## Delivered behavior

| Area | Implementation | Limit |
| --- | --- | --- |
| Proton session isolation | Per-session config callbacks and stale-generation write/clear guards | Sibling-rotated refresh-token adoption still requires upstream work |
| Proton lookup/integrity | Authenticated decrypted-name fallback, ambiguous-match rejection, plaintext size/hash checks, real download Close | Bridge internal upload name lookup unchanged; signatures remain verified |
| Existing fork fixes | Internxt token renewal, optional TOTP windows and bounded backoff; explicit network DNS override | No live Internxt, VPN, private DNS or provider validation |
| Worker outcomes | Concurrent pipe draining, exit-code based result, cancellation cleanup, failure stops batches, bounded follow-up chains and per-task unique work | Admission contention fails safely; no automatic retry promised; serving/pipe sessions block other native work until stopped |
| Bisync | Native local-to-Proton pilot, stable UUID and private state, real dry-run and explicit LOCAL precedence approval | SAF, other providers, cloud-to-cloud bisync and filters rejected |
| Bisync safety | Approval binds account/options, engine, endpoints and baseline generation; interruption requires explicit recovery; size/SHA-1 preflight, conflict copy preservation and 0–25% delete ceiling | Preview is advisory, not an atomic provider snapshot; no access-sentinel provisioning or automatic backup policy |
| Admission | Stored/ephemeral transfers, standalone CLI processes and pipes, previews, restore and wrapped RC provider calls share a process-spanning lease; child leases survive parent cancellation until confirmed native exit; async RC retains ownership through terminal completion | Direct HTTP/WebDAV streams and external clients are outside this wrapper boundary; configuration changes stop RC and interrupt existing streams |
| Restore | Bounded, validated archive components; transactional DB replacement, rollback journal for config/settings/unlock, directory fsync barriers, RC stop/reap before config replacement | Android process-kill/power-loss behavior needs device testing |
| Queue identity | Restore/encryption changes invalidate queued operations through a private generation; follow-ups keep their original generation | Old queued work from before this upgrade fails safely and must be queued again |
| References/scheduling | Nullable references preserved, delete clears references, civil-time daily/weekday scheduling and wide interval arithmetic | OS alarm/Doze behavior needs device acceptance |
| Credentials/backup | Native encrypted config stays encrypted; unlock password is Keystore wrapped; full credential backup requires encryption and prompts before protecting plaintext config | Existing plaintext configs are not automatically migrated; unattended mode only; backup task/path metadata remains readable |
| Shortcut authorization | Random task-scoped capability, durable revocation on identity changes/import | Previously pinned ID-only shortcuts must be recreated |
| Privacy/background | Bounded private redacted logs, no native raw serve/RC diagnostic files, health checks default off, conservative auth-error classification | Filename/path diagnostics may still reveal context; old external log files are not automatically erased |
| Updates | Rareities release source, draft/channel filtering and refreshed metadata | Automatic install and signing migration require separate acceptance |

## Validation

Go race tests pass for Proton and Internxt packages, HTTP/DNS and the bisync lock
regressions, including concurrent renewal/removal. `go vet` passes for all four
affected packages. Android JVM tests and lint pass. All four native ABIs build, and the five debug
APKs have been extracted: every engine hash matches packaged provenance, and Go
build information confirms the pinned commit, Android target and CGO. The final
test count is recorded in the pull request. Missing-signing and debug-certificate tests fail
closed as intended. Native PIE smoke builds verify all four ABI ELF layouts.
No live cloud account, real user data or production synchronization was used.

## Remaining roadmap and release gates

- Full bisync flag schema/capability discovery, advanced presets, filter migration,
  access sentinels, backups, structured history and broader backend support.
- Coordinated Proton refresh-token adoption and bridge upload lookup; missing
  metadata and modern-name behavior against actual disposable Proton data.
- Optional user-authenticated vault mode, automatic plaintext migration, complete
  secret inventory/migration and encrypted backup metadata.
- TLS for LAN serving, provider cancellation/timeouts and direct stream admission.
- Android launch on each ABI, real cancellation, app/process death, reboot,
  Keystore loss, restore fault injection, DST/alarm/Doze and scoped-storage tests.
- Battery, CPU, memory, network and transfer benchmarks on representative devices.
  No measured efficiency improvement is asserted by these source changes.
- Release-key provisioning/rotation and human review before merging or shipping.

These are explicit unfinished work packages. The draft integration is intended
for review and disposable-data acceptance, not unconditional production bisync.
