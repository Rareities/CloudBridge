# ADR-002: Native rclone configuration encryption

Status: implemented in the bisync candidate; Android device and power-loss
validation remain release requirements. Supersedes ADR-001.

## Decision

Keep `files/rclone.conf` in rclone's native encrypted configuration format.
Use the bundled rclone executable for encryption, validation and password
changes. Do not implement a second configuration encryption algorithm or
decrypt the live configuration to a reusable plaintext file.

An app-generated random password is wrapped with an AES-GCM Android Keystore
key. `ConfigPasswordStore` stores only the wrapped record in private storage,
uses authenticated associated data, and supports its original record format.
Fresh recovery records use fresh aliases; previous keys remain available for
journal rollback. Neither the Keystore key nor its wrapped record is exported.

The implemented policy is **unattended background access**. It does not require
a biometric prompt for every scheduled transfer. A separate user-authenticated
unlock policy is not implemented by this change. Private storage and Keystore
do not protect against a compromised running app process; authorised native
children necessarily receive the configuration password in their environment.
It is never inserted into their command arguments or diagnostic output.

## Migration and process startup

`ProtectedRcloneConfig` owns the configuration write lock. Plaintext migration
encrypts a private staged copy and verifies it with the native CLI. It installs
the wrapped password before atomically replacing the live file. A failed or
interrupted migration preserves the readable original or an encrypted file
with a matching wrapping record. An unusable wrapping record does not prevent
re-protecting an otherwise readable plaintext configuration.

If the live file is missing after an interrupted native save, a single valid
native backup can be recovered. Ambiguous or unreadable backups and the
existing password record are preserved for explicit recovery; they are not
silently replaced with an empty configuration.

Startup migration and initial remote loading run off the UI thread. Metadata
commands such as provider help do not trigger migration. Active native children
prevent configuration replacement, and a pending restore journal prevents
new native launches until recovery finishes.

`ConfigStaging` tracks active temporary files. On the next process startup it
removes abandoned app-owned migration/import copies, including native atomic
save leftovers. It preserves current operations, journals and unrelated files.
This is deletion, not a promise of secure erasure from flash storage.

## Import, export and rollback

A full ZIP backup contains encrypted `rclone.conf`, task/filter/schedule JSON,
and selected non-secret preferences. **The ZIP itself is not encrypted**: task
names, paths and the selected preferences remain readable. Export requires an
explicit backup password and re-encrypts a staged config under that password.
Proxy passwords and the Keystore record are not included. Store the backup
password independently of the Android installation.

Configuration, database and preference inputs are captured while configuration
changes are excluded; the database collections come from one transaction and
preferences from one snapshot. Large or malformed imports are rejected before
replacing live state. Import verifies the supplied password using rclone and
stages any replacement wrapping record without modifying the active one.

The restore journal covers the configuration, wrapped password, task database
and selected preferences. Generation checks reject a concurrent configuration
replacement. Recovery restores the previous generation after an interrupted
swap. Restored bisync tasks receive fresh identities and disabled schedules;
their previous engine baselines are not imported or assumed safe.

Queued transfer work is cancelled for the replaced configuration. Unrelated
update and optional session-health work is retained. Schedule reconciliation
runs after restoration or rollback. Recovery never automatically resyncs.

## Key loss

A wrong password or inaccessible Keystore key cannot silently downgrade the
file to plaintext. The user can unlock an existing encrypted file with its
password or import a verified encrypted backup. A new wrapping key can then
be staged. Existing records and recovery material are retained until commit.

Device-only gates include actual Keystore invalidation, reinstall/reboot,
power loss at each journal boundary, live token refresh persistence, locked
device scheduling and native executable compatibility on supported Android
versions. JVM tests and a desktop native encryption smoke test do not replace
these checks.

## References

- [rclone configuration encryption](https://rclone.org/docs/#configuration-encryption)
- [rclone config encryption set](https://rclone.org/commands/rclone_config_encryption_set/)
- [Android Keystore](https://developer.android.com/privacy-and-security/keystore)
