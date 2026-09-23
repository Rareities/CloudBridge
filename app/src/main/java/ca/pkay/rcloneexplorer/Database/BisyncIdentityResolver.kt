package ca.pkay.rcloneexplorer.Database

import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.Settings
import android.os.storage.StorageManager
import android.os.CancellationSignal
import ca.pkay.rcloneexplorer.Items.RemoteItem
import ca.pkay.rcloneexplorer.Rclone
import java.io.File
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/** Resolves only stable identities; unknown providers/mounts deliberately remain unresolved. */
class BisyncIdentityResolver(context: Context, private val rclone: Rclone) {
    private val appContext = context.applicationContext

    fun local(path: String?): BisyncEndpointEvidence {
        val unknown = BisyncEndpointEvidence(null, BisyncEndpointScope.unknown(), true)
        if (path.isNullOrBlank() || path.contains('\u0000') || !File(path).isAbsolute) return unknown
        return try {
            val canonical = File(path).canonicalFile
            if (!isWithin(canonical, appContext.filesDir.canonicalPath) &&
                !isWithin(canonical, knownExternalRoot(canonical))) return unknown
            val storageIdentity = stableStorageIdentity(canonical) ?: return unknown
            val fingerprint = digest("android-storage-v1", appContext.packageName, storageIdentity)
                ?: return unknown
            BisyncEndpointEvidence(
                accountFingerprint = fingerprint,
                scope = BisyncEndpointScope.from(fingerprint, canonical.path),
                supportsModTimeComparison = true
            )
        } catch (_: Exception) {
            unknown
        }
    }

    fun remote(
        remote: RemoteItem,
        path: String?,
        cancellationSignal: CancellationSignal?,
        inspectCapabilities: Boolean = true
    ): BisyncEndpointEvidence {
        if (path == null || path.contains('\u0000')) {
            return BisyncEndpointEvidence(null, BisyncEndpointScope.unknown(), null)
        }
        val account = rclone.getBisyncRemoteAccountFingerprint(remote)
        if (account == null) return BisyncEndpointEvidence(null, BisyncEndpointScope.unknown(), null)
        val scope = BisyncEndpointScope.from(account, path)
        val precision = if (inspectCapabilities) {
            rclone.getBisyncModTimeCapability(remote, path, cancellationSignal)
        } else null
        return BisyncEndpointEvidence(account, scope, precision)
    }

    fun remoteSupportsModTime(remote: RemoteItem, path: String?, cancellationSignal: CancellationSignal?): Boolean? =
        if (path == null) null else
        rclone.getBisyncModTimeCapability(remote, path, cancellationSignal)

    private fun stableStorageIdentity(path: File): String? {
        val privateRoot = appContext.filesDir.canonicalPath
        if (isWithin(path, privateRoot)) {
            return secureDeviceId()?.let { "app-private:$it" }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            val manager = appContext.getSystemService(Context.STORAGE_SERVICE) as? StorageManager ?: return null
            val volume = manager.getStorageVolume(path) ?: return null
            if (volume.isRemovable) {
                val uuid = volume.uuid?.trim().orEmpty()
                return uuid.takeIf { it.isNotEmpty() }?.let { "removable:$it" }
            }
            if (volume.isPrimary) {
                return secureDeviceId()?.let { "primary:$it" }
            }
            val uuid = volume.uuid?.trim().orEmpty()
            return uuid.takeIf { it.isNotEmpty() }?.let { "volume:$it" }
        }

        // API 23 has no per-volume UUID lookup. Support only its device-owned primary volume;
        // removable paths remain unresolved instead of trusting a reused mount-point name.
        val primary = try { File(Environment.getExternalStorageDirectory().canonicalPath) }
        catch (_: Exception) { return null }
        if (!isWithin(path, primary.canonicalPath)) return null
        return secureDeviceId()?.let { "primary:$it" }
    }

    private fun knownExternalRoot(path: File): String? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            val manager = appContext.getSystemService(Context.STORAGE_SERVICE) as? StorageManager ?: return null
            val volume = manager.getStorageVolume(path) ?: return null
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                return try { volume.directory?.canonicalPath } catch (_: Exception) { null }
            }
            return appContext.getExternalFilesDirs(null)
                .filterNotNull()
                .firstOrNull { candidate ->
                    try { volume.uuid == manager.getStorageVolume(candidate)?.uuid } catch (_: Exception) { false }
                }
                ?.let { appSpecific ->
                    var root = appSpecific
                    repeat(4) { root = root.parentFile ?: root }
                    try { root.canonicalPath } catch (_: Exception) { null }
                }
        }
        return try { Environment.getExternalStorageDirectory().canonicalPath } catch (_: Exception) { null }
    }

    private fun secureDeviceId(): String? {
        val value = Settings.Secure.getString(appContext.contentResolver, Settings.Secure.ANDROID_ID)
            ?.trim().orEmpty()
        if (value.isEmpty() || value == "9774d56d682e549c" || value.all { it == '0' }) return null
        return value
    }

    private fun isWithin(file: File, rootPath: String?): Boolean {
        if (rootPath.isNullOrBlank()) return false
        val root = File(rootPath)
        return file.path == root.path || file.path.startsWith(root.path.trimEnd(File.separatorChar) + File.separator)
    }

    private fun digest(vararg values: String): String? = try {
        val sha = MessageDigest.getInstance("SHA-256")
        for (value in values) {
            val bytes = value.toByteArray(StandardCharsets.UTF_8)
            sha.update(bytes.size.toString().toByteArray(StandardCharsets.US_ASCII))
            sha.update(':'.code.toByte())
            sha.update(bytes)
            sha.update('|'.code.toByte())
        }
        sha.digest().joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
    } catch (_: Exception) {
        null
    }
}
