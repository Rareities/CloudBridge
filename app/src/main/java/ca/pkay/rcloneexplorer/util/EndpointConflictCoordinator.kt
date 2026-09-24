package ca.pkay.rcloneexplorer.util

import android.content.Context
import ca.pkay.rcloneexplorer.Database.ResourceClaimConflictException
import ca.pkay.rcloneexplorer.Database.ResourceClaimLease
import ca.pkay.rcloneexplorer.Database.ResourceClaimRepository
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.security.MessageDigest

/** Classifies native rclone arguments and reserves their endpoint scope before process launch. */
class EndpointConflictCoordinator(context: Context) {
    private val claims = ResourceClaimRepository(context.applicationContext)

    @Throws(IOException::class)
    fun acquireForCommand(command: Array<String>, remotes: JSONObject?, operation: String): ResourceClaimLease {
        val commandIndex = command.indices.firstOrNull { command[it] in COMMANDS }
        val versionIndex = command.indexOf("--version")
        if (versionIndex >= 0 && (commandIndex == null || versionIndex < commandIndex)) {
            return ResourceClaimLease.noop()
        }
        val resources = classify(command, remotes)
        if (resources.isEmpty()) return ResourceClaimLease.noop()
        return acquireResources(operation, resources)
    }

    @Throws(IOException::class)
    fun acquireGlobal(operation: String): ResourceClaimLease =
        acquireResources(operation, global())

    /** Quarantines a native process that breached the mandatory pre-launch claim invariant. */
    fun quarantineUnclaimedProcess(operation: String): ResourceClaimLease =
        claims.recordUnresolvedGlobal(safeLabel(operation))

    @Throws(IOException::class)
    fun acquireRemote(
        operation: String,
        remoteType: String?,
        path: String?,
        wrapped: Boolean,
        localRoot: String?
    ): ResourceClaimLease = acquireResources(
        operation,
        listOf(resourceForRemote(remoteType, path, wrapped, localRoot))
    )

    companion object {
        private const val MAX_CONFIG_FINGERPRINT_BYTES = 16L * 1024L * 1024L
        private val COMMANDS = setOf(
            "sync", "copy", "copyto", "move", "moveto", "bisync", "check", "serve",
            "lsjson", "lsd", "ls", "lsf", "lsl", "size", "about", "link", "md5sum",
            "sha1sum", "hashsum", "cat", "rcat", "delete", "deletefile", "purge", "mkdir",
            "rmdir", "rmdirs", "touch", "config", "version", "obscure", "listremotes", "backend"
        )
        private val GLOBAL_COMMANDS = setOf("serve", "config", "listremotes", "backend")
        private val WRAPPER_TYPES = setOf("alias", "cache", "chunker", "combine", "crypt", "filter", "hasher", "union")
        private val VALUE_OPTIONS = setOf(
            "--max-depth", "--transfers", "--checkers", "--stats", "--stats-log-level",
            "--buffer-size", "--timeout", "--contimeout", "--retries", "--low-level-retries",
            "--user-agent", "--header", "--exclude", "--include", "--filter", "--filter-from",
            "--include-from", "--exclude-from", "--files-from", "--files-from-raw", "--config",
            "--cache-chunk-path", "--cache-db-path", "--log-file", "--log-level",
            "--backup-dir1", "--backup-dir2", "--workdir", "--compare", "--max-delete",
            "--max-delete-count", "--resync-mode", "--preview-state-from"
        )

        /** Content identity for config-cache validation; oversized/unreadable files fail closed. */
        @JvmStatic
        fun fingerprintFile(file: File): String? {
            if (!file.isFile || file.length() > MAX_CONFIG_FINGERPRINT_BYTES) return null
            return try {
                val digest = MessageDigest.getInstance("SHA-256")
                var total = 0L
                FileInputStream(file).use { input ->
                    val buffer = ByteArray(8192)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        total += count
                        if (total > MAX_CONFIG_FINGERPRINT_BYTES) return null
                        digest.update(buffer, 0, count)
                    }
                }
                digest.digest().joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
            } catch (_: Exception) {
                null
            }
        }

        /**
         * Returns every explicit Bisync backup root, or null when the argv is ambiguous or
         * malformed. Known option values are skipped so filter text cannot impersonate a flag.
         */
        internal fun parseBisyncBackupDirTargets(command: Array<String>, commandIndex: Int): List<String>? {
            if (commandIndex !in command.indices || command[commandIndex] != "bisync") return null
            val result = ArrayList<String>(2)
            val seen = HashSet<String>(2)
            var index = commandIndex + 1
            while (index < command.size) {
                val token = command[index]
                if (token == "--") break

                val option = when {
                    token == "--backup-dir1" || token.startsWith("--backup-dir1=") -> "--backup-dir1"
                    token == "--backup-dir2" || token.startsWith("--backup-dir2=") -> "--backup-dir2"
                    else -> null
                }
                if (option != null) {
                    if (!seen.add(option)) return null
                    val value = if (token == option) {
                        command.getOrNull(index + 1).also { index++ }
                    } else {
                        token.substringAfter('=')
                    }
                    if (value.isNullOrBlank() || value.startsWith("-") || value.contains('\u0000')) return null
                    result += value
                } else if (token in VALUE_OPTIONS) {
                    if (command.getOrNull(index + 1) == null) return null
                    index++
                } else if (token.startsWith("-")) {
                    // An unknown option could consume the next token, including a backup flag.
                    return null
                }
                index++
            }
            return result
        }

        /** Extracts positional endpoints; unknown flags before them make classification global. */
        internal fun parsePositionalTargets(command: Array<String>, start: Int, count: Int): List<String>? {
            if (start !in 0..command.size || count < 0) return null
            val result = ArrayList<String>(count)
            var index = start
            while (index < command.size && result.size < count) {
                val value = command[index]
                if (value == "--") {
                    index++
                    while (index < command.size && result.size < count) {
                        result.add(command[index++])
                    }
                    break
                }
                if (value.startsWith("-")) {
                    val option = value.substringBefore('=')
                    if (value.contains('=')) {
                        index++
                    } else if (option in VALUE_OPTIONS) {
                        if (command.getOrNull(index + 1) == null) return null
                        index += 2
                    } else {
                        // Guessing an unknown option's arity can mistake its value for an endpoint.
                        return null
                    }
                    continue
                }
                result.add(value)
                index++
            }
            return if (result.size == count) result else null
        }
    }

    fun resourceForRemote(
        remoteType: String?,
        path: String?,
        wrapped: Boolean,
        localRoot: String?
    ): EndpointResource {
        return if (wrapped || remoteType.isNullOrBlank() || path == null) {
            EndpointResource.global()
        } else if (remoteType.equals("local", ignoreCase = true)) {
            val absolute = when {
                File(path).isAbsolute -> path
                !localRoot.isNullOrBlank() && File(localRoot).isAbsolute -> File(localRoot, path).path
                else -> return EndpointResource.global()
            }
            EndpointResource.localFile(absolute)
        } else if (remoteType.equals("protondrive", ignoreCase = true)) {
            // Proton tokens/config are shared across remotes and may refresh during read probes.
            EndpointResource.remote(remoteType, "")
        } else {
            EndpointResource.remote(remoteType, path)
        }
    }

    @Throws(IOException::class)
    fun acquireResources(operation: String, resources: List<EndpointResource>): ResourceClaimLease {
        return try {
            claims.acquire(safeLabel(operation), resources)
        } catch (_: ResourceClaimConflictException) {
            throw IOException("Rclone operation conflicts with an active or unresolved app operation")
        }
    }

    private fun classify(command: Array<String>, remotes: JSONObject?): List<EndpointResource> {
        val commandIndex = command.indices.firstOrNull { command[it] in COMMANDS } ?: return global()
        val verb = command[commandIndex]
        if (verb == "version" || verb == "obscure") return emptyList()
        if (verb in GLOBAL_COMMANDS) return global()

        val arity = when (verb) {
            "sync", "copy", "copyto", "move", "moveto", "bisync", "check" -> 2
            "serve" -> return global()
            "lsjson", "lsd", "ls", "lsf", "lsl", "size", "about", "link",
            "md5sum", "sha1sum", "hashsum", "cat", "rcat", "delete", "deletefile",
            "purge", "mkdir", "rmdir", "rmdirs", "touch" -> 1
            else -> return global()
        }
        val targets = positionalTargets(command, commandIndex + 1, arity) ?: return global()
        val resources = targets.map { endpoint(it, remotes) }
        if (resources.any { it.isGlobal }) return global()
        if (verb != "bisync") return resources

        // Backup roots are independently mutable endpoints and must stay claimed with both roots.
        val backupTargets = parseBisyncBackupDirTargets(command, commandIndex) ?: return global()
        val backupResources = backupTargets.map { endpoint(it, remotes) }
        return if (backupResources.any { it.isGlobal }) global() else resources + backupResources
    }

    private fun positionalTargets(command: Array<String>, start: Int, count: Int): List<String>? {
        return parsePositionalTargets(command, start, count)
    }

    private fun endpoint(argument: String, remotes: JSONObject?): EndpointResource {
        if (argument.startsWith("content://", ignoreCase = true)) return EndpointResource.global()
        if (File(argument).isAbsolute) return EndpointResource.localFile(argument)
        if (argument.startsWith("file://", ignoreCase = true)) {
            return try {
                EndpointResource.localFile(java.net.URI(argument).path)
            } catch (_: Exception) {
                EndpointResource.global()
            }
        }
        val colon = argument.indexOf(':')
        if (colon <= 0 || remotes == null) return EndpointResource.global()
        val remoteName = argument.substring(0, colon)
        val section = remotes.optJSONObject(remoteName) ?: return EndpointResource.global()
        val type = section.optString("type").trim()
        if (type.isEmpty()) return EndpointResource.global()
        val path = argument.substring(colon + 1)
        if (type.equals("local", ignoreCase = true)) {
            return if (File(path).isAbsolute) EndpointResource.localFile(path) else EndpointResource.global()
        }
        // Alias, crypt, cache and composite backends can target a different physical root.
        // Until the entire wrapper chain is verified, serialize them globally rather than allow
        // a wrapper name to bypass a direct-path claim.
        if (section.has("remote") || type in WRAPPER_TYPES) return EndpointResource.global()
        return if (type.equals("protondrive", ignoreCase = true)) {
            EndpointResource.remote(type, "")
        } else {
            EndpointResource.remote(type, path)
        }
    }

    private fun safeLabel(value: String): String = value.filter {
        it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' || it in "._-"
    }
        .take(64).ifEmpty { "native" }

    private fun global() = listOf(EndpointResource.global())

}
