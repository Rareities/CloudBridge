package ca.pkay.rcloneexplorer

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.util.Base64
import androidx.preference.PreferenceManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import ca.pkay.rcloneexplorer.Services.RcdService
import ca.pkay.rcloneexplorer.util.NativePipeTransfer
import ca.pkay.rcloneexplorer.util.NativeSessionGate
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.ServerSocket
import java.net.URL
import java.util.UUID
import java.util.concurrent.TimeUnit

/** Real DocumentsContract plus native cat/rcat acceptance on an isolated emulator. */
@RunWith(AndroidJUnit4::class)
class ProviderDeviceIntegrationTest {
    private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val resolver get() = context.contentResolver
    private val authority get() = BuildConfig.VCP_AUTHORITY
    private val idColumn = DocumentsContract.Document.COLUMN_DOCUMENT_ID
    private val nameColumn = DocumentsContract.Document.COLUMN_DISPLAY_NAME

    private fun emulatorOnly() = assumeTrue(
        "Only disposable .debug ranchu/goldfish emulator permitted",
        context.packageName.endsWith(".debug") &&
            (Build.HARDWARE.contains("ranchu", ignoreCase = true) ||
                Build.HARDWARE.contains("goldfish", ignoreCase = true)))

    private fun waitUntil(label: String, timeoutMs: Long, condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        while (System.nanoTime() < deadline) {
            if (condition()) return
            Thread.sleep(100)
        }
        assertTrue("Timed out waiting for $label", condition())
    }

    private fun command(label: String, process: Process?) {
        assertNotNull("$label did not launch", process)
        val child = process!!
        try {
            if (!child.waitFor(25, TimeUnit.SECONDS)) {
                child.destroy()
                if (!child.waitFor(5, TimeUnit.SECONDS)) {
                    child.destroyForcibly()
                    assertTrue("$label could not stop", child.waitFor(5, TimeUnit.SECONDS))
                }
                fail("$label timed out")
            }
            assertEquals("$label native exit code", 0, child.exitValue())
        } finally { if (child.isAlive) child.destroyForcibly() }
    }

    private fun uri(id: String): Uri = DocumentsContract.buildDocumentUri(authority, id)

    /** The external disposable WebDAV server deliberately bypasses NativeSessionGate. */
    private fun freeLoopbackPort(): Int = ServerSocket(0, 1,
        InetAddress.getByName("127.0.0.1")).use { it.localPort }

    private fun awaitAuthenticatedFixture(port: Int, marker: String, username: String,
                                          password: String, process: Process) {
        val authorization = "Basic " + Base64.encodeToString(
            "$username:$password".toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        var lastStatus = "connection unavailable"
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (System.nanoTime() < deadline) {
            if (!process.isAlive) {
                fail("Disposable WebDAV server exited before it accepted an authenticated request")
            }
            var connection: HttpURLConnection? = null
            try {
                connection = (URL("http://127.0.0.1:$port/fixture-ready.txt")
                    .openConnection() as HttpURLConnection).apply {
                    connectTimeout = 600
                    readTimeout = 600
                    instanceFollowRedirects = false
                    setRequestProperty("Authorization", authorization)
                }
                val status = connection.responseCode
                lastStatus = "HTTP $status"
                if (status == 200 && connection.inputStream.bufferedReader().use {
                        it.readText() == marker
                    }) return
            } catch (_: IOException) {
                lastStatus = "network connection pending"
            } finally {
                connection?.disconnect()
            }
            Thread.sleep(100)
        }
        fail("Packaged loopback WebDAV server never served authenticated fixture ($lastStatus)")
    }

    private fun stopFixtureServer(process: Process?, transfer: NativePipeTransfer?) {
        if (process == null) return
        if (process.isAlive) {
            if (transfer != null) transfer.cancel() else process.destroy()
        }
        if (!process.waitFor(7, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            assertTrue("Owned WebDAV child survived forced shutdown",
                process.waitFor(5, TimeUnit.SECONDS))
        }
        // The native helper owns stdout/stderr until the child actually exits.
        // An intentionally cancelled long-running serve process is not success.
        if (transfer != null) {
            try { transfer.awaitSuccessfulExit() }
            catch (_: IOException) { /* Expected on cancellation or server exit. */ }
        }
    }

    private fun rootId(): String {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(12)
        var last = "No root returned"
        while (System.nanoTime() < deadline) {
            try {
                resolver.query(DocumentsContract.buildRootsUri(authority),
                    arrayOf(DocumentsContract.Root.COLUMN_ROOT_ID, DocumentsContract.Root.COLUMN_DOCUMENT_ID),
                    null, null, null).use { c ->
                    if (c == null) { last = "Null root cursor"; return@use }
                    if (c.extras.getBoolean(DocumentsContract.EXTRA_LOADING, false)) {
                        last = "Root loading"; return@use
                    }
                    val type = c.getColumnIndexOrThrow(DocumentsContract.Root.COLUMN_ROOT_ID)
                    val doc = c.getColumnIndexOrThrow(DocumentsContract.Root.COLUMN_DOCUMENT_ID)
                    while (c.moveToNext()) if (c.getString(type) == "rclone") return c.getString(doc)
                }
            } catch (failure: RuntimeException) {
                last = failure.javaClass.simpleName + ": " + failure.message
            }
            Thread.sleep(150)
        }
        throw AssertionError("VCP root not exposed after bounded wait: $last")
    }

    // Read-only query retry is safe during VCP async loading and lazy RC restart.
    // Never retry a mutation: its native RC job may already have committed.
    private fun child(parent: String, name: String, timeoutMs: Long = 35_000): String {
        val children = DocumentsContract.buildChildDocumentsUri(authority, parent)
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        var detail = "No terminal query"
        while (System.nanoTime() < deadline) {
            var id: String? = null
            try {
                resolver.query(children, arrayOf(idColumn, nameColumn), null, null, null).use { c ->
                    if (c == null) { detail = "Null cursor"; return@use }
                    if (c.extras.getBoolean(DocumentsContract.EXTRA_LOADING, false)) {
                        detail = "EXTRA_LOADING"; return@use
                    }
                    val error = c.extras.getString(DocumentsContract.EXTRA_ERROR)
                    if (error != null) { detail = error; return@use }
                    val idIndex = c.getColumnIndexOrThrow(idColumn)
                    val nameIndex = c.getColumnIndexOrThrow(nameColumn)
                    while (c.moveToNext()) {
                        val candidate = c.getString(idIndex)
                        if (c.getString(nameIndex) == name ||
                            (name.endsWith(":") && candidate.endsWith("/$name"))) {
                            id = candidate
                            break
                        }
                    }
                    if (id == null) detail = "Absent from " + c.count + " entries"
                }
            } catch (failure: RuntimeException) {
                detail = failure.javaClass.simpleName + ": " + failure.message
            }
            if (id != null) return id!!
            Thread.sleep(150)
        }
        throw AssertionError("VCP did not discover $name below $parent: $detail")
    }

    private fun assertMissing(parent: String, name: String) {
        resolver.query(DocumentsContract.buildChildDocumentsUri(authority, parent),
            arrayOf(nameColumn), null, null, null).use { c ->
            assertNotNull("Cannot query after deletion", c)
            assertFalse("Post-delete listing still loading",
                c!!.extras.getBoolean(DocumentsContract.EXTRA_LOADING, false))
            val column = c.getColumnIndexOrThrow(nameColumn)
            while (c.moveToNext())
                assertNotEquals("Deleted document remains visible", name, c.getString(column))
        }
    }

    @Test(timeout = 210_000)
    fun realSafRoundTripMutationAndLazyRcRestart() {
        emulatorOnly()
        assertNotEquals("Instrumented waits must leave Android main looper runnable",
            android.os.Looper.getMainLooper().thread, Thread.currentThread())
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val enabledKey = context.getString(R.string.pref_key_enable_vcp)
        val remoteName = "phase2_provider_" + UUID.randomUUID().toString().replace("-", "")
        val fixture = File(context.filesDir, "phase2-provider-" + UUID.randomUUID())
        assertTrue("Cannot create isolated fixture", fixture.mkdirs())
        val serverConfig = File(context.cacheDir, "phase2-webdav-" + UUID.randomUUID() + ".conf")
        val rclone = Rclone(context)
        var remoteCreated = false
        var serverProcess: Process? = null
        var serverTransfer: NativePipeTransfer? = null
        var cancelledDescriptor: ParcelFileDescriptor? = null
        var cancellation: CancellationSignal? = null
        try {
            waitUntil("native processes to release admission", 15_000) {
                !NativeSessionGate.GLOBAL.isBusy() && !RcloneRcd.hasRunningProcess()
            }
            rclone.prepareProtectedConfig()
            assertTrue("Could not prepare empty fake server config", serverConfig.createNewFile())
            val port = freeLoopbackPort()
            val username = "fixture-user"
            val password = "fixture-" + UUID.randomUUID().toString().replace("-", "")
            val marker = "fixture-ready-" + UUID.randomUUID()
            File(fixture, "fixture-ready.txt").writeText(marker)
            val packagedRclone = File(context.applicationInfo.nativeLibraryDir, "librclone.so")
            assertTrue("Packaged native rclone binary missing", packagedRclone.isFile)
            val server = ProcessBuilder(packagedRclone.absolutePath,
                "--config", serverConfig.absolutePath,
                "serve", "webdav", fixture.absolutePath,
                "--addr", "127.0.0.1:$port", "--dir-cache-time=0s").apply {
                environment()["RCLONE_USER"] = username
                environment()["RCLONE_PASS"] = password
            }.start()
            serverProcess = server
            // Upload direction drains both child's stdout and bounded/redacted
            // stderr. The separate server is an external fake service, not a
            // protected-config command and never acquires NativeSessionGate.
            serverTransfer = NativePipeTransfer(server, true)
            awaitAuthenticatedFixture(port, marker, username, password, server)

            // Unlike local aliases, WebDAV is exposed by the production provider.
            // Its localhost URL intentionally differs from the SAFW URL prefix.
            val createRemote = rclone.configCreateNoInteract(mutableListOf(
                remoteName, "webdav", "url", "http://127.0.0.1:$port/",
                "vendor", "other", "user", username, "pass", password))
            remoteCreated = createRemote != null
            command("create disposable loopback WebDAV remote", createRemote)
            assertTrue("Failed to enable provider", prefs.edit().putBoolean(enabledKey, true).commit())
            val rootId = rootId()
            assertEquals("rclone/remotes", rootId)
            val remoteId = child(rootId, "$remoteName:")
            assertEquals("rclone/remotes/$remoteName:", remoteId)
            val remote = uri(remoteId)
            assertEquals(remoteId, DocumentsContract.getDocumentId(remote))

            val created = requireNotNull(DocumentsContract.createDocument(
                resolver, remote, "application/octet-stream", "uploaded.bin")) {
                "DocumentsContract.createDocument returned null"
            }
            val nativeFile = File(fixture, "uploaded.bin")
            waitUntil("zero-byte create to complete", 15_000) {
                nativeFile.isFile && nativeFile.length() == 0L
            }
            val bytes = ByteArray(256 * 1024) { ((it * 31 + (it ushr 8)) % 251).toByte() }
            ParcelFileDescriptor.AutoCloseOutputStream(requireNotNull(
                resolver.openFileDescriptor(created, "wt")) { "No writable VCP pipe" }
            ).use { it.write(bytes) }
            waitUntil("native rcat exit and actual file contents", 30_000) {
                !NativeSessionGate.GLOBAL.isBusy() && nativeFile.isFile &&
                    nativeFile.length() == bytes.size.toLong()
            }
            assertArrayEquals("Native upload bytes differ", bytes, nativeFile.readBytes())

            // After rcat invalidates RC, only provider APIs may restart it.
            assertEquals(DocumentsContract.getDocumentId(created), child(remoteId, "uploaded.bin"))
            val download = requireNotNull(resolver.openFileDescriptor(created, "r")) {
                "No readable VCP pipe"
            }
            val fetched = ParcelFileDescriptor.AutoCloseInputStream(download).use { it.readBytes() }
            assertArrayEquals("ContentResolver downloaded incorrect bytes", bytes, fetched)
            waitUntil("native cat exit", 15_000) { !NativeSessionGate.GLOBAL.isBusy() }

            // This is the first mutation after cat; no explicit getLocalRcd or wait
            // for the old RC daemon, so the provider must lazily restart itself.
            val renamed = requireNotNull(DocumentsContract.renameDocument(
                resolver, created, "renamed.bin")) { "SAF rename returned null" }
            assertFalse("Rename left original", nativeFile.exists())
            assertArrayEquals(bytes, File(fixture, "renamed.bin").readBytes())
            assertEquals(DocumentsContract.getDocumentId(renamed), child(remoteId, "renamed.bin"))
            val destination = requireNotNull(DocumentsContract.createDocument(
                resolver, remote, DocumentsContract.Document.MIME_TYPE_DIR, "destination")) {
                "SAF destination creation returned null"
            }
            assertTrue(File(fixture, "destination").isDirectory)
            val copied = requireNotNull(DocumentsContract.copyDocument(
                resolver, renamed, destination)) { "SAF copy returned null" }
            assertArrayEquals(bytes, File(fixture, "destination/renamed.bin").readBytes())
            assertEquals(DocumentsContract.getDocumentId(copied),
                child(DocumentsContract.getDocumentId(destination), "renamed.bin"))
            DocumentsContract.deleteDocument(resolver, copied)
            assertFalse(File(fixture, "destination/renamed.bin").exists())
            assertMissing(DocumentsContract.getDocumentId(destination), "renamed.bin")
            DocumentsContract.deleteDocument(resolver, renamed)
            assertFalse(File(fixture, "renamed.bin").exists())
            assertMissing(remoteId, "renamed.bin")

            // Fill an unread 16 MiB native cat pipe and request cancellation.
            val large = File(fixture, "cancelled.bin")
            large.outputStream().buffered().use { out ->
                val block = ByteArray(65536) { (it % 251).toByte() }
                repeat(256) { out.write(block) }
            }
            val largeId = child(remoteId, "cancelled.bin")
            cancellation = CancellationSignal()
            cancelledDescriptor = requireNotNull(resolver.openFileDescriptor(
                uri(largeId), "r", cancellation)) { "No cancellable VCP pipe" }
            assertTrue("Cat never owned native command admission", NativeSessionGate.GLOBAL.isBusy())
            cancellation.cancel()
            cancelledDescriptor.close()
            cancelledDescriptor = null
            waitUntil("cancelled native process actual exit", 20_000) {
                !NativeSessionGate.GLOBAL.isBusy()
            }
            assertTrue("Cancel deleted source", large.isFile)
        } finally {
            cancellation?.cancel()
            try { cancelledDescriptor?.close() } catch (_: IOException) { }
            prefs.edit().putBoolean(enabledKey, false).commit()
            try {
                context.startService(Intent(context, RcdService::class.java)
                    .setAction(RcdService.ACTION_STOP_FOREGROUND))
            } catch (_: RuntimeException) { /* No service if bind never happened. */ }
            var nativeIdle = false
            var remoteRemoved = !remoteCreated
            try {
                waitUntil("all native children to exit before deleting remote", 25_000) {
                    !RcloneRcd.hasRunningProcess() && !NativeSessionGate.GLOBAL.isBusy()
                }
                nativeIdle = true
                if (remoteCreated) {
                    command("delete only our disposable WebDAV remote",
                        rclone.config("delete", mutableListOf(remoteName)))
                    remoteRemoved = true
                }
            } finally {
                stopFixtureServer(serverProcess, serverTransfer)
                // Never remove a still-served fixture or active provider target.
                if (nativeIdle && remoteRemoved && serverProcess?.isAlive != true) {
                    assertTrue("Could not remove only owned fixture directory", fixture.deleteRecursively())
                    if (serverConfig.exists()) assertTrue("Could not remove empty test server config",
                        serverConfig.delete())
                }
            }
        }
    }
}
