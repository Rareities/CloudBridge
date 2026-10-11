# Full fork changelog and commit history

This records the Rareities changes on top of the direct upstream repositories.
Commit authors are git metadata, not a claim about who directed each line.
Integration merges are included without counting their features twice.

## 1.1.1-rc.1 — safety and bisync follow-up


### Signed promotion and repository cleanup

The reviewed application source is merged into `master` at
[`fe23bd52`](https://github.com/Rareities/CloudBridge/commit/fe23bd52d90e46ca63384db4b174bc50b4a3cc05).
[`461d911d`](https://github.com/Rareities/CloudBridge/commit/461d911d)
removes temporary debug publishing and the test-branch signing exception;
debug APK builds are now manual-only. No additional app source blocker was
found during the aggregate follow-up review. The pinned engine is unchanged;
rclone's documentation follow-up is merged at
[`63bf067c`](https://github.com/Rareities/rclone/commit/63bf067cab7ceeddd330aaff45b0f8ffa9e51855).

The [signed prerelease](https://github.com/Rareities/CloudBridge/releases/tag/v1.1.1-rc.1)
contains five APKs with the previous release's package and certificate.
176 host tests, lint, dependency checks, native regression checks and signed
APK verification passed. The downloaded ARM64 APK was independently checked
for its identity, certificate, checksum and native engine hash. The user
reports the preceding debug build works perfectly; an independent device or
live-account matrix was not run.

Obsolete integration/test branches and old Actions artifacts were removed.
Unmerged historical tips have archive tags; existing normal signed releases,
current validation artifacts and active engine-review branches were retained.
The exact audit scope, CI runs and cleanup inventory are recorded in
[promotion-audit.json](validation/promotion-audit.json).


Release candidate following 1.1.0-rc.1. Retains the pinned rclone engine
`47048daf3c80e1de633ec9a1f09981f4c30f9a30`; no engine source patch is needed for
these managed-app fixes.

- Serialize bisync configuration/account validation and actual native launch.
  Remote edits and new RC jobs cannot interleave that preparation interval;
  native ownership continues protecting the running child after launch.
- Persist a local configuration generation before manual edits, reconnects,
  deletes, renames and imports. OAuth account replacement now retires old
  approval, even when only token fields change. Automatic refresh does not.
  The generation is excluded from backups and rollback snapshots.
- Resolve transfer defaults once into a full task snapshot, used by both
  approval fingerprint and command construction. The editor uses the same rule.
- Commit restore rollback files, pending marker and directory entries before
  replacing live components; use SQLite FULL synchronization and installed-file
  barriers. Durably retire the pending marker before deleting rollback snapshots.
- Move to approval policy v4. Existing approvals require a fresh preview and
  reviewed initialization; no automatic resync or baseline deletion is added.
- Bump versionName and ABI-safe base versionCode together to 1.1.1-rc.1 / 110.
  Validate tags, checked-in versions and prior ABI codes before signing.
- Add fork ancestry, project purpose, AI-development disclaimer and the complete
  commit-linked changelog at the top of README without removing original content.

Validation results and remaining device/live-account limits are recorded in
this changelog and the signed release
[notes](releases/v1.1.1-rc.1.md).

### Simpler bisync editor follow-up

- Keep Preview and Review/initialise in the main flow; collapse optional backups,
  access markers, manual flags and helper tools under Advanced settings.
- Explain each helper and rename Proton connection to Proton login help.
- Allow opening a completed, unexpired preview for review without waiting for a
  separate background configuration check. Click-time and worker fingerprint
  checks remain mandatory before any initialization.
- Display queued/running work, pre-preview failures and configuration-check errors
  so disabled actions have an actionable explanation.
- Keep saved options when Advanced is collapsed; extend editor regressions and
  adapt disposable-device actions/screenshots to the collapsible controls.

### Task startup and execution feedback follow-up

- Bound Android foreground-service startup and the local, non-mutating rclone
  flag check to 30 seconds. Timeouts fail with an actionable message; normal
  transfers have no new overall deadline.
- Publish task phases and elapsed time to WorkManager and the editor. A quiet
  engine reports time since its last output instead of leaving “Preparing task”
  indefinitely. Transfers still finish according to the native exit result.
- Preserve specific retry reasons for required network, charging, quiet hours,
  competing native operations and competing transfers.
- Keep progress publication advisory: notification failures cannot abandon a
  native child, bypass cancellation or release its ownership before exit.
- Add real child-process regressions for silent-check deadlines and ordinary
  successful completion with a deadline.

These address confirmed feedback and unbounded-startup problems. The exact
cause on the user's installed APK is not established without device logs.

Validation for the editor/execution follow-up: **176 host tests** passed with
no failures, errors or skips. Lint, all five OSS debug APK builds and instrumentation
Kotlin compilation passed. Independent review passed. All APK native payloads
match the pinned engine. See [validation record](validation/bisync-execution-followup.json).
No device or live-account execution was performed for this follow-up.

### First-run UUID error follow-up

Run now checks baseline readiness before resolving its UUID/path. A new task
shows Preview → review → Initialise guidance instead of “Invalid UUID string”.
Malformed baselines cannot enable Run or advisory preview; explicitly saving a
task repairs a malformed task UUID without reusing the old baseline.

Validation: **172 host tests passed**, lint and all five OSS debug APK builds
passed (using the same verified pinned native engines). Three new regressions
cover first Run, malformed baseline identifiers and explicit task-save repair.
Independent source review passed. Device execution remains unrun.

### Follow-up commits

- [d4521de6c1](https://github.com/Rareities/CloudBridge/commit/d4521de6c15a86ee85e8f485299ee0ac239fda9c) —
  opt-in, independently reviewed debug-test prerelease publication from the
  isolated test branch, with fail-fast uploads and no release signing secrets.
- [eb41c11444](https://github.com/Rareities/CloudBridge/commit/eb41c11444ff4b8e2359b94d96ed307ea8bb428e) —
  exact test-branch admission in the signed-release workflow and updated notes.
  The existing environment deployment allowlist remains a required external gate.

- [f59a257740](https://github.com/Rareities/CloudBridge/commit/f59a2577405c16a4ce3ac93cb1ebdf47a0fcb5f9) —
  simpler bisync editor, actionable running/queued feedback, persisted waiting
  reasons, foreground/local-check deadlines and validated regression coverage.
- [dbbe9ee32f](https://github.com/Rareities/CloudBridge/commit/dbbe9ee32f912f7c3060ce617d792b3bf84ca4dc) —
  first-run UUID fix documentation and validation.
- [cbcda693b2](https://github.com/Rareities/CloudBridge/commit/cbcda693b2c924ebc0939fb0099ad02c47d2a28f) —
  personal-fork README introduction and full commit-linked changelog.

- [e8f2a5ad5a](https://github.com/Rareities/CloudBridge/commit/e8f2a5ad5aae553eca7a5fd5d014d413120d9316) —
  first-run initialization guidance and safe task/baseline UUID validation.

- [f5b8f5fc3e](https://github.com/Rareities/CloudBridge/commit/f5b8f5fc3e282116ad1ecfd7eabc1e6c6ce247db) —
  configuration/account approval, process admission, durable restore, resolved
  defaults, versioning, regression tests and validation evidence.
- [852525046b](https://github.com/Rareities/rclone/commit/852525046bf0856349eee51b814d11057f2effb5) —
  engine documentation clarifying credential locking and standalone account
  changes. The Android engine pin stays at the previously reviewed source.
- The README and this changelog are maintained in the accompanying documentation
  commits. The reviewed app changes are promoted together to `master`, preserving
  the full commit history. The rclone documentation follow-up is merged into its
  `master`; the Android engine remains pinned to the previously reviewed source.
  Temporary debug-test publication was used to obtain user acceptance and then
  removed. Normal release APKs retain the existing package and release certificate.

### Promotion audit and cleanup

- Re-review of all app changes since 1.1.0-rc.1 found no new concrete blocker.
  The user reported the debug build works correctly on their device; broader
  live-account and physical-device compatibility gates remain as documented.
- Remove the temporary debug-prerelease publisher and exact test-ref allowance
  in release signing. Keep release signing restricted to `master` and its existing
  protected environment. Debug builds become manual to prevent duplicate builds.
- Retire superseded temporary branches and Actions build artifacts after signed
  release success. Preserve prior signed releases for rollback; archive unmerged
  obsolete branch history before removing the branch. Automation review branches
  remain available for future upstream intake.

### Validation status

- **169 host tests / 48 suites** passed with no failures, errors or skips.
- **6 Python release-version regression tests** passed.
- **Lint and all five OSS debug APK builds** passed with the existing lint baseline.
- Instrumentation Kotlin compilation passed, including the new real Android
  directory-sync/SQLite FULL test wired into the disposable-emulator suite.
- Independent source review passed after all findings were corrected.
- All five APK native payloads match the reviewed engine provenance. These builds
  reused the hash-verified published engines; `:rclone:buildAll` was excluded.
- Device execution, live-account switching and physical power-loss acceptance
  were **not run** in this environment. Host tests inject directory barrier
  failures because Robolectric cannot fsync an Android directory descriptor.
- Detailed local results: [validation record](validation/release-safety-followup.json).


### Upstream comparison bases

- CloudBridge: `a6589372cbf86e0b72fb374b500ff81ac87cc7a6` against
  thies2005/CloudBridge `ece9275441e448ec520a7cbc336c59bfe643b22c`.
- Engine: `47048daf3c80e1de633ec9a1f09981f4c30f9a30` against
  rclone/rclone `4f19e9cd6f442282d7fa8d5fdbd28ebb68be1131`.
- The accompanying engine documentation follow-up clarifies process-shared
  credential persistence and the standalone CLI account-change limitation.
  This is documentation only; the app keeps the reviewed engine pin.

## Published 1.1.0-rc.1 and engine 1.76.0-cloudbridge.1

### CloudBridge

## Bisync and task editing

- Complete two-way setup: persistent UUID baselines, preview, explicit approval,
  initial precedence, conflict preservation, percentage deletion limits,
  backup directories and optional access markers.
- Ready-task previews use a private snapshot and preserve the healthy baseline.
  View timestamped affected-file reports, private history and effective arguments.
- Bind approval and queued work to settings, endpoints, filters, configuration
  identity and the exact engine. Changed or uncertain state requires review;
  never silently add resync, force, listing deletion or trash cleanup.
- Preserve newly saved identity across rotation and unavailable remote selection.
  Resolve configured alias/crypt/cache wrappers correctly when executing tasks.
- Validate custom flags against the bundled engine and use individual argv
  elements. Reject positional commands, credentials and overrides of managed
  safety, state, TLS, listeners or diagnostic controls.
  Connection destinations, authority headers and CA/client certificates belong
  in the remote editor; task flags cannot redirect stored credentials.
- Add Balanced, Low memory and Limited bandwidth templates. Copy settings into
  the task while preserving filters and safety choices. Reject connection caps
  that can deadlock the selected transfers/checkers. No measured energy-saving
  claim is made for a template.
- Add explicit Proton reconnect/verification with transient one-time codes and
  persistent, debounced account pauses. Reconnect does not repair a baseline.
- Run remote creation and editing outside Android's main thread. Editing
  submits one configuration operation; failed or cancelled saves keep an honest
  failure result. Bound authentication steps, response size and process lifetime,
  and drain both native output pipes during setup and OAuth authorisation.
- Restore the remote editor and provider picker safely after rotation without
  placing credential values in Android's saved-state bundle. Unsaved credential
  edits are discarded and an existing remote is reloaded from private storage.
- Preserve queued button taps during task-state refresh. Viewing a completed
  preview or running an eligible task no longer loses the tap when the screen
  recomputes the same enabled state.

## Scheduling and execution

- Timezone/DST-aware daily, weekday and interval schedules, next-run display,
  quiet hours, network/charging/battery constraints and missed-run coalescing.
- Unique admission, saved-revision checks and bounded follow-up chains prevent
  overlap, cycles and running a queued request against newly edited locations.
- Nonzero exit, cancellation, failed streams and uncertain completion cannot
  become success or trigger success follow-ups.
- Retain native ownership until process exit and cleanup. Fix startup-permit
  leaks, competing RC callbacks and early restart. Retire idle RC after its
  grace period; periodic health checks remain opt-in and disabled by default.
- Exercise all six existing copy/sync modes with the real WorkManager and
  bundled executable, including destination-only-file semantics.

## Security and privacy

- Keep native encrypted rclone configuration with a Keystore-wrapped password,
  bounded staging, parse-first restore and journalled crash rollback.
- Encrypt all new backup contents with standard AES-256 ZIP entries, including
  task names, paths, preferences and proxy credentials. Only fixed component
  names and sizes remain visible; rclone.conf is separately encrypted too.
- Retain legacy archive import. Old proxy-enabled archives without a protected
  password require explicit proxy reconfiguration, avoiding leakage of an old
  credential to a restored proxy host or silent direct-network fallback.
- Move proxy passwords out of plaintext preferences into authenticated Android
  Keystore storage. Failed unlock/migration stops affected work; key loss needs
  explicit re-entry rather than plaintext downgrade.
- Reject document-provider parent/dot traversal, malformed root IDs and unsafe
  rename targets. Remove grant-all behaviour that could expand a reader's URI
  permission to write permission. Remove unused unbounded ZIP extractors.
- Bound and batch diagnostic writes, retire the writer when idle, and retain
  redaction, private history, restricted notifications and scoped shortcuts.
- Remove raw authentication responses, malformed JSON and OAuth URL buffers from
  legacy setup diagnostics. Report operation outcomes without retaining secrets
  supplied by a backend in an unexpected error response.
- Keep authenticated loopback RC and require authenticated TLS for supported
  LAN serving. Validate authentication, traversal and untrusted certificates.

## Engine, upstream and build

- Pin to reviewed Rareities/rclone master from the matching
  `v1.76.0-cloudbridge.1` candidate. Packaged provenance records the exact SHA,
  module/toolchain versions and each ABI hash; no moving branch selection.
  The engine source is `47048daf3c80e1de633ec9a1f09981f4c30f9a30`.
- Include atomic Proton credential persistence, authenticated modern-name
  lookup, strict metadata/checksums, Unicode ambiguity rejection, baseline
  identity locks, reader/upload fixes and existing Android/Internxt patches.
  Managed app jobs prohibit unknown-draft replacement. The standalone engine
  retains its documented explicit replacement option, disabled by default;
  enabling it manually does not establish ownership of another client's draft.
- Incorporate current reviewed upstream changes and useful alternate-branch
  work without replacing the tested lifecycle or database architecture.
- Build with Go 1.27.2, NDK 29 and API 23 as Android PIE executables with 16 KiB
  ELF load alignment. Use bionic system DNS instead of raw DNS-server addresses
  that could bypass Private DNS. OEM/VPN/custom trust testing remains external.

## Signing and installation

Dedicated fork signing certificate SHA-256:

`c1b947a38264f1d65f7986c0b65f649ce882fc5232094726777067050dac9bcd`

Release ID: `de.schuelken.cloudbridge`. Debug ID:
`de.schuelken.cloudbridge.debug`. Android will not update an application signed
by somebody else's certificate with this fork key. Export and verify a backup
before any deliberate replacement; do not uninstall merely to bypass signing
protection. The key is private, with protected master-only CI signing.

Test/build runners do not receive publication permission. A separate publisher
uploads verified non-debuggable APKs and checksums. Missing or mismatched signing
prerequisites fail the build. `SHA256SUMS.txt` covers the published assets.
The older duplicate automatic-beta publisher is retired in favour of this one
explicit, verified release workflow.

## Acceptance and remaining gates

See `docs/RELEASE_PLAN_COVERAGE.md`, `docs/INTEGRATION_ACCEPTANCE.md` and the
release workflow evidence. Verification covers native and GUI bisync, all
ordinary task modes, WorkManager charging/cancellation, RC/provider ownership,
force-stop/reopen, encrypted rollback, hostile archives and secret canaries.

No personal Proton credentials were used. Live disposable-account official-client
round trips, actual long-lived refresh/revocation, account/crypto migration,
physical Android/API/ABI/16 KiB hardware, genuine foreground quotas and controlled
battery measurements remain external gates. Photos, Shared With Me, instant
events, desktop and biometric-per-run unlocking are deferred. This candidate is
not a universal security, service-compatibility or battery-efficiency guarantee.

### rclone

## Proton Drive

- Bind auth callbacks to the owning configuration, credential generation, UID
  and mailbox-key identity. Stale callbacks cannot overwrite a newer session.
- Adopt compatible rotated credentials before refresh and after rejection;
  preserve credentials through transient failures and losing refresh races.
- Persist the entire UID/access-token/refresh-token/salted-key tuple with one
  configuration save under a process-shared lock. Reload while locked and reject
  stale generations, changed configuration paths and conflicting credential
  overrides. Persistence failures stop the operation instead of disappearing
  inside a callback. Existing native configuration encryption is retained.
- Preserve original configuration-section identity through generated backend
  override suffixes. Configured aliases and policy overrides do not invent a new
  credential destination.
- Contain the reviewed Proton API source and licence, including same-UID checks,
  spent-token rejection, bounded late-winner adoption, correct callback lock
  ordering and negative crypto tests. No signature bypass or custom SRP/crypto.
- Resolve authenticated decrypted names when modern official-client objects
  are absent from legacy hash lookup, including upload-conflict lookup. Reject
  ambiguous names and incomplete directory metadata.
- Preserve plaintext size/hash validation, download Close propagation, upload
  cancellation, consumed-reader non-replay, timestamp handling and honest app
  identification. Never replace unfamiliar drafts or empty account trash as a
  recovery shortcut.

## Bisync integrity

- Opt-in `--require-checksum` rejects absent common/per-file checksums, unknown
  plaintext sizes and damaged, truncated or duplicated listing rows before
  reconciliation. Strict preflight also rejects ambiguous normalised filenames
  during the existing traversal, without adding a cloud listing pass.
- Lock both the unordered endpoint pair and its actual listing basename, also
  for previews. Reversed paths and legacy filename collisions cannot bypass
  admission or reuse another ordered pair's baseline.
- Bind legacy-compatible listing names to a versioned ordered endpoint identity.
  Only successful explicit initialisation establishes that identity. Preserve
  ownership-safe lock renewal/removal, conflicts and deletion thresholds.
- Retain all upstream Proton/bisync fixes already present, required Android DNS
  support and Internxt compatibility. Integrate the current official master,
  including its additional release-tooling/documentation changes.

## Migration

Older **unbound** bisync listings require explicitly reviewed `--resync`. Confirm
both roots, precedence and backups first. CloudBridge instead offers a new
private baseline after its own preview/approval flow. No automatic resync, force,
listing deletion or trash purge is introduced.

The credential guard coordinates updated processes sharing the same config.
Separate config files and older binaries are not coordinated. Avoid editing
the same configuration with an older external client during a refresh.

## Distribution and verification

Built with **Go 1.27.2 or newer**, including its October 2026 security fixes.
Windows amd64, Linux amd64/arm64 and macOS amd64/arm64 archives include licence,
changelog, exact source SHA, module/toolchain metadata and binary hashes.
`SHA256SUMS.txt` covers published assets. Android executables are built separately
by CloudBridge with NDK 29, bionic system DNS, PIE and 16 KiB load alignment.

The release workflow runs backend/config/march/bisync regressions, race/static
checks, negative crypto and fake-server tests, and disposable CLI acceptance.
Coverage includes competing OS-process credential writes, failed saves, name
ambiguity, both-direction changes, deletion/modification conflicts, missing roots
and markers, thresholds and forced interruption. Windows cannot execute the
upstream privileged symlink fixtures on this build host; the unchanged fixtures
run on Linux CI instead. Test/build runners have read-only repository permissions;
publication runs separately and does not execute candidate code with write access.

**External gates:** live disposable-Proton official-client round trips, actual
long-lived refresh/revocation and account/crypto-migration matrices. No personal
credentials were used. Photos, Shared With Me, instant events and universal
service compatibility are not claimed. Pair this with a CloudBridge build whose
provenance pins this release's exact engine source.

## Exhaustive fork commit ledger

Authors below are git metadata. Merge rows represent integration, not an extra independent feature implementation. Official upstream release-tooling commits d03990188 and 4f19e9cd6 are upstream intake, not credited as fork inventions.

### CloudBridge

| Commit | Author | Change |
|---|---|---|
| [fdf65556f5](https://github.com/Rareities/CloudBridge/commit/fdf65556f596da8798cb1566a3a2e8398b552600) | ChatGPT | feat: add guarded Proton bisync workflows and task scheduling |
| [e24703f3e0](https://github.com/Rareities/CloudBridge/commit/e24703f3e04e4d07dec861d6c9e6111fe1b7c313) | ChatGPT | ci: install supported Android SDK packages |
| [39251088ce](https://github.com/Rareities/CloudBridge/commit/39251088ce172cee10c9b83a3c25fcd2e66545c3) | ChatGPT | fix: validate native bisync lifecycle and complete task workflows |
| [ab161aa76c](https://github.com/Rareities/CloudBridge/commit/ab161aa76cca548dcda728324d899b389f3ba4e1) | ChatGPT | fix: harden privacy boundaries and ship a signed bisync release candidate |
| [a50c7a0784](https://github.com/Rareities/CloudBridge/commit/a50c7a078448d2edfdd80989e46a8c847c7f0327) | ChatGPT | test: validate signed release without test-only shrunk helper calls |
| [e18a057f1c](https://github.com/Rareities/CloudBridge/commit/e18a057f1c925510af6dd08d90f697a5ed9307b0) | ChatGPT | fix: complete remote setup lifecycle and preserve queued task actions |
| [a6589372cb](https://github.com/Rareities/CloudBridge/commit/a6589372cbf86e0b72fb374b500ff81ac87cc7a6) | Rareities | Merge verified Proton bisync, privacy and signed release candidate |

### rclone

| Commit | Author | Change |
|---|---|---|
| [4102e56edc](https://github.com/Rareities/rclone/commit/4102e56edc0c7a43938ab4d025312922e8f796a8) | Rareities | ci: verify Proton Drive and bisync changes in fork |
| [8af9adf738](https://github.com/Rareities/rclone/commit/8af9adf73816aebb26a81409611b24428fa3958e) | Rareities | chore: add selective official and OSS candidate preparation |
| [397ef12001](https://github.com/Rareities/rclone/commit/397ef12001c0a478147acc509f65b653cbd78dd5) | Rareities | ci: check official and OSS sources daily and propose review-only PRs |
| [e42c8ec0a2](https://github.com/Rareities/rclone/commit/e42c8ec0a2e0870d5556f0c57fdd073f51895f06) | Rareities | ci: fetch existing candidate ref before leased updates |
| [aaa9fb2028](https://github.com/Rareities/rclone/commit/aaa9fb2028fcd1f6bb0a94d229a6db5266915a91) | Rareities | docs: describe official and Forgejo Proton source review |
| [3a5758eeaa](https://github.com/Rareities/rclone/commit/3a5758eeaa7250635f756f56c84128445d33ebe2) | Rareities | Merge pull request #1 from Rareities/setup/dual-upstream-review |
| [789cfc5c4f](https://github.com/Rareities/rclone/commit/789cfc5c4fde57ad301485a809e23a4aa84e5e31) | ChatGPT | protondrive: harden sessions and metadata for safe Android bisync |
| [5b1b5c0dcc](https://github.com/Rareities/rclone/commit/5b1b5c0dccfea83712b574a3fefa9d9916d45655) | ChatGPT | ci: isolate upstream candidate tests from publication credentials |
| [2e8c114a56](https://github.com/Rareities/rclone/commit/2e8c114a56dca65cdc5ab5570362864a9b0f2a56) | ChatGPT | fix: guard Proton listings and bisync process boundaries |
| [30c9c95089](https://github.com/Rareities/rclone/commit/30c9c95089eefb893cde2a5446de78d69619bc53) | ChatGPT | test: exercise disposable bisync lifecycle in native CI |
| [14260f9712](https://github.com/Rareities/rclone/commit/14260f97124267bb789eab2ae06e4d9259cee1bb) | ChatGPT | test: remove only the owned hidden access sentinel portably |
| [719ed66b9e](https://github.com/Rareities/rclone/commit/719ed66b9e7e6a76b9bfe3fe1ae194f89ea5ccc9) | ChatGPT | bisync: bind legacy listings to verified endpoint pairs |
| [1cbc4186a6](https://github.com/Rareities/rclone/commit/1cbc4186a67a205de7b323280cffe9245d60fdf7) | ChatGPT | test: fix timestamps in access-marker fixture |
| [c935845b1a](https://github.com/Rareities/rclone/commit/c935845b1a71686f9841500141ab84ce224c77e9) | ChatGPT | test: cover opposing bisync deletions and modifications |
| [6b9a27b7a6](https://github.com/Rareities/rclone/commit/6b9a27b7a6530621bbbcadad9c4a88c52c777b37) | ChatGPT | fix: persist Proton sessions atomically and prepare verified fork release |
| [76f218c559](https://github.com/Rareities/rclone/commit/76f218c559a5be7c423eba7a381e71df260c7b89) | ChatGPT | Merge reviewed official rclone master release tooling |
| [6a9f0fc48c](https://github.com/Rareities/rclone/commit/6a9f0fc48ca23660022fe24615bf5e988710d418) | ChatGPT | build: check known vulnerabilities and preserve executable archive modes |
| [47048daf3c](https://github.com/Rareities/rclone/commit/47048daf3c80e1de633ec9a1f09981f4c30f9a30) | Rareities | Merge reviewed Proton bisync and verified fork release |
