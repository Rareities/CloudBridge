# ADR-001: Credential and rclone-config protection at rest

**Status:** PROPOSAL NOT ACCEPTED / implementation evidence recorded. This record supersedes the inherited proposal that recommended `EncryptedFile`, `EncryptedSharedPreferences`, and clearing config after Keystore failure. No complete credential-storage architecture is accepted here; it does not authorize a new storage design or claim complete at-rest encryption.

**Last checked:** 2026-09-25 against the CloudBridge `codex/luna-implementation` checkout. See `EXECUTION_LEDGER.md` for commit-level evidence and remaining gates.

## Context

CloudBridge stores rclone configuration in app-private storage and launches the native rclone library with that config. Provider tokens and credentials can appear in the config. Rclone's per-value `obscure` encoding is reversible and is not encryption. The app also supports a config passphrase for rclone-encrypted config and can retain that passphrase for native calls.

`android:allowBackup="false"` is set in the manifest. This limits Android backup/restore behavior; it does not encrypt files or protect against root/forensic access.

## Current implementation evidence

| Data/path | Verified implementation | Security limit |
|---|---|---|
| `<app files>/rclone.conf` | App-private file read by the native library. Rclone may obscure individual values; the app does not encrypt the entire file at rest. | A private app directory is not cryptographic protection. Do not describe all provider secrets as encrypted. |
| Stored rclone config passphrase | `ConfigSecretStore` uses Android Keystore AES-GCM and stores IV plus ciphertext in app-private `SharedPreferences`. | Protects this passphrase only, not an otherwise unencrypted config. Hardware backing and S26 behavior have not been established. |
| Keystore read/key failure | `Rclone`/`RcloneRcd` do not load the password and log a sanitized failure; the ciphertext/config are left in place for explicit recovery. | Device key-loss, reinstall, migration, and user recovery behavior still need instrumented/device evidence. Never clear config as generic recovery. |
| Native rclone calls | The passphrase is passed in `RCLONE_CONFIG_PASS` when available. | Memory/process environment lifetime and every command/output path need continued audit. |
| Import/export and logs | Config import uses staged files and configuration can be exported; log-redaction code exists. | Exports are intentionally sensitive; a complete audit of temporary files, logs, and all credential-bearing outputs is still open. |

## Current implementation constraint (not an architecture approval)

The current code includes Keystore-wrapped storage of the rclone config passphrase. This is implementation evidence, not an accepted app-wide at-rest protection decision and not a general credential store. The existing format and app-private location of `rclone.conf` are retained until a tested migration and native-I/O design is reviewed and accepted.

No app-wide whole-file encryption approach is selected. In particular:

- Do not assume a read-only pipe or stdin can replace the native config file; validate the exact librclone integration and all write/refresh flows first.
- Do not adopt `EncryptedFile` or a deprecated Jetpack Security Crypto API by default. Any future library must be actively supported and tested against atomic replacement, concurrent native writers, cancellation, background execution, import/export, and rollback.
- Do not clear a config, ciphertext, or profile when a Keystore key is missing, invalidated, or temporarily inaccessible. Preserve bytes and require an explicit, recoverable user action.
- Keep passwords/tokens out of command arguments, ordinary logs, test fixtures, and execution-ledger details. Do not claim `obscure` is encryption.

## Required design and test gates before expanding implementation

1. Inventory every credential-bearing store and flow, including OAuth refresh, Internxt/Proton auth, SharedPreferences/DataStore, rclone config dump, logs, exports, temporary files, and native environment values.
2. Prove native config read/write and token refresh behavior with the exact immutable app-pinned rclone commit, including independent library tests before app integration.
3. Test first-run creation, existing plaintext/obscured config, rclone-encrypted config, import replacement, cancellation, concurrent writers, process death, atomic replacement, backup/restore, reinstall, and missing/invalidated Keystore keys using disposable data.
4. Preserve old bytes and expose a recoverable state when a key is unavailable. A migration must be staged, integrity-checked, reversible within a bounded window, and must not silently overwrite user data.
5. Record Android API, device model, firmware, and signing identity for device-specific behavior. Emulator/JVM results do not satisfy the Galaxy S26 acceptance gate.

## Current verification status

The 2026-09-25 OSS debug JVM suite passed 130 tests with one platform-capability skip, but it did not exercise Android Keystore behavior, native engine integration, instrumentation, live Proton, or a Galaxy S26. See `SECURITY.md`, `REQUIREMENTS_MATRIX.md`, and the execution ledger; release readiness remains closed.
