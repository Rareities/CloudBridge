# ADR-001: Native encrypted configuration and Keystore-wrapped unlock

Status: PARTIALLY IMPLEMENTED. See [implementation status](IMPLEMENTATION_STATUS.md).

rclone natively supports encryption of its entire configuration file. CloudBridge
keeps that native format on disk and gives the native process its unlock password
through its environment. Unlock no longer decrypts the configuration to a plaintext
file. Android Keystore AES-GCM protects the stored unlock password, using a fresh
IV and an app-private preference file. The native subprocess performs provider
cryptography and signature verification.

The implemented mode permits unattended background work; it does not require
biometric authentication. Hardware-backed storage depends on the device. Key loss
fails closed and requires unlocking again; CloudBridge must not silently clear a
user's configuration. No encryption or authentication bypass is introduced.

A credential backup requires a native encrypted configuration. Exporting an
existing plaintext configuration prompts for a user-chosen password, stages and
verifies native encryption, then journal-installs the encrypted file and wrapped
unlock. The password is entered twice through masked fields, is not put in argv,
logs or saved UI state, and is not included in the archive. The user must retain
it for restore on another installation. Task/path metadata in the ZIP remains
readable. Existing plaintext configurations are not automatically migrated.

Imports validate bounded candidates before changing data. The restore journal
includes database, exported settings, native config and the wrapped unlock;
operation admission and a private generation prevent active/queued wrapper jobs
from executing through a configuration change. Native config and metadata changes
have rollback records with data and directory fsync barriers. Device fault testing
remains a release prerequisite. `allowBackup=false` continues to apply.

Remaining work: optional authenticated vault mode, automatic plaintext migration,
full shared-preference secret inventory/migration, encrypted backup metadata and
coordination with direct streams/external clients. Do not revive the earlier
proposal to decrypt a whole configuration into a temporary plaintext file.
