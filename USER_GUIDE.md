# CloudBridge fork user guide — interim status

**Checked:** 2026-09-25. This guide describes the current Rareities source snapshot; it
does not imply that a release exists or that every source feature passed acceptance.

## Availability and support

There is currently no published Rareities/CloudBridge APK or release. Do not install an
upstream CloudBridge APK expecting it to contain this fork's changes. Development builds
are for contributors; see [BUILD_GUIDE.md](BUILD_GUIDE.md) and
[WINDOWS_BUILD_GUIDE.md](WINDOWS_BUILD_GUIDE.md). Neither a debug build nor a successful
compile is a supported release.

The GitHub issue tracker is disabled and a fork-specific private support/security route
has not been confirmed. Do not include passwords, OAuth tokens, unredacted configuration,
private provider URLs, or raw logs in public issues or pull requests.

## Current Bisync limitation — read before use

The current source contains a read-only Bisync preview/review path. Safe initialization,
apply, and recovery are **not available**. Do not use this fork for two-way Bisync
mutations, do not treat a preview as permission to mutate either endpoint, and do not
manually bypass the app's gate with resync/force/purge operations. Keep independent,
verified copies of important data.

Initialization and recovery require the application to prove endpoint identity, reserve a
fresh run-owned preservation area outside both synchronized roots, survive interruption
and restart, and restore exact bytes without overwriting newer data. Those gates are still
open; no claim of transactional cloud backup is made.

There is no supported setup, initialization, or recovery procedure in this snapshot.
The safe action is to avoid Bisync mutations until a release has passed the documented
preservation, restoration, provider, and device gates.

## Files, remotes, and credentials

CloudBridge uses rclone for remote operations. The available remotes and operations depend
on the configured remote, app UI path, engine revision, provider behavior, and Android
storage permissions. The presence of an rclone backend does not mean every provider or
operation is supported and tested in this fork.

The app does not encrypt the entire rclone configuration file at rest. When the app stores
the config passphrase, source code wraps that passphrase with Android Keystore AES-GCM;
device hardware backing and key-loss behavior still need device acceptance. Treat exported
configurations, OAuth tokens, crypt passwords, logs, and cloud URLs as sensitive. Do not
attach a real config or unredacted log to a bug report.

For SD cards, USB drives, or document providers, grant access only to the intended tree and
use disposable copies while testing. Revoke access only after any active operation has
stopped. Removing a drive, losing a permission, or seeing an empty listing is not proof
that the intended storage is available.

## Schedules and external automation

Schedule and background-work code exists, but dependable timing, missed-run policy,
duplicate coalescing, reboot/force-stop behavior, notification denial, battery restrictions,
and Samsung device behavior have not passed acceptance. Treat scheduled execution as
best-effort and check the app's persisted run result before assuming that work completed.

The legacy direct Tasker/third-party intent recipe is not supported: the referenced sync
service is not exported. Do not configure another app to invoke it. A secure external
automation contract has not yet been accepted.

## Proton Drive and Obsidian

Live Proton Drive behavior is **NOT RUN** in this environment. Do not test against an
existing directory named Proton:RoundSync-Test. Any future provider test must use a
verified, unique disposable subdirectory/account and independently verified source copies.
Never use deletion or permanent-delete behavior as a test shortcut.

The sync-before-open and foreground-lifecycle workflow for Obsidian has not been verified
in the current source. Do not rely on it or infer that an unknown foreground state means
Obsidian is closed.

## Update notifications

The inspected source defaults update notifications off. If enabled, periodic work checks
the upstream thies2005/CloudBridge GitHub release API on a connected network and opens
that upstream release page; it does not install updates. This is not a check for Rareities
fork releases. WorkManager timing is best-effort, and this path has not had device/runtime
acceptance. Do not use it to verify whether a Rareities fork update exists.

## Reporting a problem

Record the app/source revision, Android version and device model, exact operation, and
whether the result is reproduced with disposable data. Remove account names, access tokens,
remote paths and private URLs. The fork's private reporting channel must be established
before a public release; do not send sensitive material to an inherited upstream contact.

## Current evidence

- [Requirements and package status](REQUIREMENTS_MATRIX.md)
- [Feature survival inventory](FEATURE_SURVIVAL_MATRIX.md)
- [Test report](TEST_REPORT.md)
- [Security review](SECURITY_REVIEW.md)
- [Release readiness](RELEASE_READINESS.md)
- Detailed change/test/rollback history: [EXECUTION_LEDGER.md](EXECUTION_LEDGER.md)
