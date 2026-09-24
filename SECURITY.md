# Security policy

## Reporting a vulnerability

This file is inherited from upstream and the fork-specific disclosure route has not yet been confirmed. As checked on 2026-09-25, the Rareities/CloudBridge repository has its public issue tracker disabled; private vulnerability reporting was not verified. Do not post exploit details, credentials, or proof-of-concept data in a public issue or pull request. The inherited upstream contact is [136268370+thies2005@users.noreply.github.com](mailto:136268370+thies2005@users.noreply.github.com), but it is not a confirmed contact for the Rareities fork. Maintainers must establish a fork-specific private reporting route before a release.

No verified Rareities/CloudBridge release artifact was identified in the current status snapshot, so this policy does not establish a supported fork release version. Upstream releases are not releases of this fork.

## Security scope

CloudBridge handles credentials for remote storage and can read or modify local files that the user grants it access to. Treat unauthorized credential access, unintended local/cloud file access, unsafe native-process handling, and disclosure through logs or temporary files as security issues. The app relies on Android's app sandbox and does not claim protection against rooted devices or a compromised operating system.

## Current implementation facts and limits

- The manifest sets `android:allowBackup="false"`. This is a backup-policy control, not encryption and not protection from root or forensic access.
- The rclone config is stored in app-private storage. The app does not encrypt the entire config file at rest; rclone's per-value `obscure` encoding is reversible and is not cryptographic protection. A user-encrypted rclone config is a separate mode.
- When a config passphrase is stored, `ConfigSecretStore` wraps that passphrase with Android Keystore AES-GCM and stores ciphertext in app-private preferences. This does **not** encrypt an otherwise unencrypted `rclone.conf`, and this code path has not received Galaxy S26/device acceptance in the current handoff.
- The config passphrase is supplied to the native rclone process through `RCLONE_CONFIG_PASS`; process lifecycle, memory lifetime, import/export, and all log/output paths require continued review. Do not assume all secrets or app state are encrypted because this passphrase is.
- The SAF/WebDAV bridge exists. Access is based on Android document-tree grants; give those grants only to apps you trust. The complete cross-app and temporary-file threat surface remains under audit.
- Exported configuration files and logs may contain secrets or identifying data. Never share raw exports or logs. Create synthetic test data or redact and manually inspect every line before sharing.

The credential-storage design record is [`docs/ADR-001-credential-storage-at-rest.md`](docs/ADR-001-credential-storage-at-rest.md). It records implemented scope and open decisions; it is not an assertion of complete at-rest encryption.
