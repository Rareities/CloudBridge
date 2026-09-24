package ca.pkay.rcloneexplorer.Database

import ca.pkay.rcloneexplorer.Items.RemoteItem
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

/** Immutable, credential-free endpoint fields decoded from a persisted profile snapshot. */
data class EndpointSnapshot(
    val schemaVersion: Int,
    val remote1: EndpointRemoteSnapshot,
    val localPath: String,
    val remote2: EndpointRemoteSnapshot
) {
    override fun toString(): String = "EndpointSnapshot(schemaVersion=$schemaVersion, endpoints=<redacted>)"
}

data class EndpointRemoteSnapshot(
    val type: Int,
    val remoteName: String,
    val path: String
) {
    override fun toString(): String = "EndpointRemoteSnapshot(type=$type, remoteName=<redacted>, path=<redacted>)"
}

enum class EndpointSnapshotDecodeFailure {
    MALFORMED_SETTINGS,
    UNKNOWN_SCHEMA_VERSION,
    MALFORMED_ENDPOINT_SNAPSHOT
}

data class EndpointSnapshotDecodeResult(
    val snapshot: EndpointSnapshot?,
    val failure: EndpointSnapshotDecodeFailure?
) {
    val isSupported: Boolean
        get() = snapshot != null && failure == null
}

/**
 * Read-only decoder for the existing length-prefixed profile representation. It never touches
 * the filesystem, provider, database, rclone process, or persisted profile. Unsupported or
 * ambiguous inputs fail closed.
 */
object EndpointSnapshotCodec {
    private const val SUPPORTED_SCHEMA_VERSION = LegacyProfileMapper.PROFILE_SCHEMA_VERSION
    private const val MAX_INPUT_BYTES = 1_048_576
    private const val MAX_FIELDS = 64

    fun decode(endpointIdentity: String, settings: String): EndpointSnapshotDecodeResult {
        val settingsFields = parseCanonical(settings)
            ?: return failed(EndpointSnapshotDecodeFailure.MALFORMED_SETTINGS)
        if (settingsFields.size % 2 != 0) {
            return failed(EndpointSnapshotDecodeFailure.MALFORMED_SETTINGS)
        }

        val settingsMap = HashMap<String, String>()
        var index = 0
        while (index < settingsFields.size) {
            val key = settingsFields[index]
            if (key.isEmpty() || settingsMap.put(key, settingsFields[index + 1]) != null) {
                return failed(EndpointSnapshotDecodeFailure.MALFORMED_SETTINGS)
            }
            index += 2
        }
        val schemaVersion = settingsMap["schema"]?.toIntOrNull()
            ?: return failed(EndpointSnapshotDecodeFailure.MALFORMED_SETTINGS)
        if (schemaVersion != SUPPORTED_SCHEMA_VERSION) {
            return failed(EndpointSnapshotDecodeFailure.UNKNOWN_SCHEMA_VERSION)
        }

        val fields = parseCanonical(endpointIdentity)
            ?: return failed(EndpointSnapshotDecodeFailure.MALFORMED_ENDPOINT_SNAPSHOT)
        if (fields.size != 10 || fields[0] != "remote1" || fields[4] != "local" || fields[6] != "remote2") {
            return failed(EndpointSnapshotDecodeFailure.MALFORMED_ENDPOINT_SNAPSHOT)
        }
        val firstType = fields[1].toIntOrNull()
            ?: return failed(EndpointSnapshotDecodeFailure.MALFORMED_ENDPOINT_SNAPSHOT)
        val secondType = fields[7].toIntOrNull()
            ?: return failed(EndpointSnapshotDecodeFailure.MALFORMED_ENDPOINT_SNAPSHOT)

        return EndpointSnapshotDecodeResult(
            snapshot = EndpointSnapshot(
                schemaVersion,
                EndpointRemoteSnapshot(firstType, fields[2], fields[3]),
                fields[5],
                EndpointRemoteSnapshot(secondType, fields[8], fields[9])
            ),
            failure = null
        )
    }

    private fun parseCanonical(value: String): List<String>? {
        val bytes = value.toByteArray(StandardCharsets.UTF_8)
        if (bytes.isEmpty() || bytes.size > MAX_INPUT_BYTES) return null
        val fields = ArrayList<String>()
        var cursor = 0
        while (cursor < bytes.size) {
            if (fields.size >= MAX_FIELDS) return null
            val lengthStart = cursor
            var length = 0
            while (cursor < bytes.size && bytes[cursor] in '0'.code.toByte()..'9'.code.toByte()) {
                val digit = bytes[cursor] - '0'.code.toByte()
                if (length > (MAX_INPUT_BYTES - digit) / 10) return null
                length = length * 10 + digit
                cursor++
            }
            if (cursor == lengthStart || cursor >= bytes.size || bytes[cursor] != ':'.code.toByte()) return null
            if (cursor - lengthStart > 1 && bytes[lengthStart] == '0'.code.toByte()) return null
            cursor++
            if (length > bytes.size - cursor) return null
            val field = try {
                StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes, cursor, length))
                    .toString()
            } catch (_: CharacterCodingException) {
                return null
            }
            if (field.indexOf('\u0000') >= 0) return null
            fields.add(field)
            cursor += length
        }
        return fields
    }

    private fun failed(failure: EndpointSnapshotDecodeFailure) =
        EndpointSnapshotDecodeResult(snapshot = null, failure = failure)
}

enum class EndpointCapability {
    LOCAL_PATH_REQUIRES_RUNTIME_PROOF,
    REMOTE_CONFIG_REQUIRES_RUNTIME_PROOF,
    STORAGE_FRAMEWORK_UNSUPPORTED,
    WRAPPED_REMOTE_UNSUPPORTED,
    UNKNOWN_REMOTE_UNSUPPORTED,
    MISSING_ENDPOINT,
    UNKNOWN_SNAPSHOT_VERSION,
    UNKNOWN_MODE
}

data class EndpointAssessment(val slot: String, val capability: EndpointCapability)

data class EndpointSnapshotAssessment(
    val endpoints: List<EndpointAssessment>
) {
    /** No profile snapshot alone is sufficient to authorize backup placement. */
    val backupPlacementPermitted: Boolean
        get() = false
}

/**
 * Conservative shape classifier only. A "requires runtime proof" result is not approval:
 * account identity, mount identity, sibling placement, collision and durability remain unproven.
 */
object EndpointSnapshotClassifier {
    private val wrappedTypes = setOf(
        RemoteItem.ALIAS,
        RemoteItem.CACHE,
        RemoteItem.CRYPT,
        RemoteItem.CHUNKER,
        RemoteItem.UNION
    )
    private val knownTypes = setOf(
        RemoteItem.FICHIER, RemoteItem.AMAZON_DRIVE, RemoteItem.S3, RemoteItem.B2,
        RemoteItem.BOX, RemoteItem.SHAREFILE, RemoteItem.DROPBOX, RemoteItem.FTP,
        RemoteItem.GOOGLE_CLOUD_STORAGE, RemoteItem.GOOGLE_DRIVE, RemoteItem.GOOGLE_PHOTOS,
        RemoteItem.JOTTACLOUD, RemoteItem.KOOFR, RemoteItem.LOCAL, RemoteItem.MAILRU,
        RemoteItem.MEGA, RemoteItem.AZUREBLOB, RemoteItem.ONEDRIVE, RemoteItem.OPENDRIVE,
        RemoteItem.SWIFT, RemoteItem.PCLOUD, RemoteItem.PUTIO, RemoteItem.QINGSTOR,
        RemoteItem.SFTP, RemoteItem.WEBDAV, RemoteItem.YANDEX, RemoteItem.HTTP,
        RemoteItem.PREMIUMIZEME, RemoteItem.INTERNXT, RemoteItem.DRIME
    )

    fun classify(mode: ProfileMode, snapshot: EndpointSnapshot): EndpointSnapshotAssessment {
        if (snapshot.schemaVersion != LegacyProfileMapper.PROFILE_SCHEMA_VERSION) {
            return EndpointSnapshotAssessment(
                listOf(EndpointAssessment("snapshot", EndpointCapability.UNKNOWN_SNAPSHOT_VERSION))
            )
        }
        val result = ArrayList<EndpointAssessment>(3)
        fun addLocal() {
            val path = snapshot.localPath
            val capability = when {
                path.isBlank() -> EndpointCapability.MISSING_ENDPOINT
                path.startsWith("content:", ignoreCase = true) || path.startsWith("document:", ignoreCase = true) ->
                    EndpointCapability.STORAGE_FRAMEWORK_UNSUPPORTED
                else -> EndpointCapability.LOCAL_PATH_REQUIRES_RUNTIME_PROOF
            }
            result.add(EndpointAssessment("local", capability))
        }
        fun addRemote(slot: String, remote: EndpointRemoteSnapshot) {
            val capability = when {
                remote.remoteName.isBlank() -> EndpointCapability.MISSING_ENDPOINT
                remote.type == RemoteItem.SAFW -> EndpointCapability.STORAGE_FRAMEWORK_UNSUPPORTED
                remote.type in wrappedTypes -> EndpointCapability.WRAPPED_REMOTE_UNSUPPORTED
                remote.type in knownTypes -> EndpointCapability.REMOTE_CONFIG_REQUIRES_RUNTIME_PROOF
                else -> EndpointCapability.UNKNOWN_REMOTE_UNSUPPORTED
            }
            result.add(EndpointAssessment(slot, capability))
        }

        when (mode) {
            ProfileMode.SYNC_ONE_WAY, ProfileMode.COPY_ONE_WAY, ProfileMode.BISYNC -> {
                addLocal()
                addRemote("remote1", snapshot.remote1)
            }
            ProfileMode.CLOUD_TO_CLOUD -> {
                addRemote("remote1", snapshot.remote1)
                addRemote("remote2", snapshot.remote2)
            }
            ProfileMode.UNKNOWN -> result.add(EndpointAssessment("profile", EndpointCapability.UNKNOWN_MODE))
        }
        return EndpointSnapshotAssessment(result.toList())
    }
}
