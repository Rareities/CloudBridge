package io.github.rareities.protonbridge

import android.os.Build
import android.util.AtomicFile
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.rareities.protonbridge.Database.json.Exporter
import io.github.rareities.protonbridge.Database.json.SharedPreferencesBackup
import io.github.rareities.protonbridge.util.ConfigPasswordStore
import io.github.rareities.protonbridge.util.NativeConfigEncryption
import io.github.rareities.protonbridge.util.NativeSessionGate
import io.github.rareities.protonbridge.util.ProtectedRcloneConfig
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest

/** Creates an authentic encrypted interrupted-restore disk state in an emulator.
 * Run with -e recoveryPhase stage, force-stop the debug app, then -e recoveryPhase verify.
 * The verification uses Application.onCreate recovery, not a direct recovery call.
 */
@RunWith(AndroidJUnit4::class)
class EncryptedCrashRecoveryTest {
    private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it) }
    private fun write(file: File, bytes: ByteArray) {
        val atomic = AtomicFile(file)
        val stream = atomic.startWrite()
        try { stream.write(bytes); atomic.finishWrite(stream) }
        catch (error: Exception) { atomic.failWrite(stream); throw error }
    }

    @Test fun encryptedConfigurationAndKeystoreRecordRecoverAcrossProcessRestart() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        assumeTrue(context.packageName.endsWith(".debug") &&
            (Build.HARDWARE.contains("ranchu") || Build.HARDWARE.contains("goldfish")))
        val phase = InstrumentationRegistry.getArguments().getString("recoveryPhase")
        assumeTrue("Run the explicit stage/verify acceptance script", phase == "stage" || phase == "verify")
        val config = File(context.filesDir, "rclone.conf")
        val wrapped = File(context.filesDir, "config-password.json")
        val journal = File(context.filesDir, "restore-journal")
        val marker = File(context.filesDir, "phase2-crash-verification.json")
        val rclone = Rclone(context)
        val executable = File(context.applicationInfo.nativeLibraryDir, "librclone.so").absolutePath
        if (phase == "stage") {
            assertFalse("Only a quiescent disposable configuration may be staged", NativeSessionGate.GLOBAL.isBusy)
            rclone.prepareProtectedConfig()
            assertTrue(NativeConfigEncryption.isEncrypted(config))
            assertFalse(File(journal, "pending").exists())
            assertTrue(journal.isDirectory || journal.mkdirs())
            val previousConfig = config.readBytes()
            val previousPassword = wrapped.readBytes()
            val previousDatabase = Exporter.create(context)
            val snapshot = JSONObject().put("version", 1).put("hadConfig", true).put("hadPassword", true)
                .put("database", JSONObject(previousDatabase))
                .put("preferences", JSONObject(SharedPreferencesBackup.export(context)))
            write(File(journal, "previous.conf"), previousConfig)
            write(File(journal, "previous.password"), previousPassword)
            write(File(journal, "previous.json"), snapshot.toString().toByteArray())
            marker.writeText(JSONObject().put("configHash", hash(previousConfig)).put("passwordHash", hash(previousPassword))
                .put("databaseHash", hash(previousDatabase.toByteArray())).toString())
            val staged = File(context.cacheDir, "phase2-crash-new.conf")
            staged.writeBytes(previousConfig)
            try {
                NativeConfigEncryption.encrypt(executable, staged, ConfigPasswordStore(context).read(),
                    "disposable-crash-recovery-new-password", rclone.getRcloneEnv())
                // Exact crash boundary: journal is durable, configuration swapped,
                // matching new wrapped password has not yet been installed.
                write(File(journal, "pending"), byteArrayOf(1))
                ProtectedRcloneConfig.install(staged, config)
                assertNotEquals(hash(previousConfig), hash(config.readBytes()))
                assertThrows(java.io.IOException::class.java) {
                    ProtectedRcloneConfig.launch(context, arrayOf(executable, "--version"), rclone.getRcloneEnv())
                }
            } finally { staged.delete() }
        } else {
            assertTrue("Recovery marker from stage is missing", marker.isFile)
            val expected = JSONObject(marker.readText())
            assertFalse("Application startup left an interrupted journal", File(journal, "pending").exists())
            assertFalse(File(journal, "pending.bak").exists())
            assertEquals(expected.getString("configHash"), hash(config.readBytes()))
            assertEquals(expected.getString("passwordHash"), hash(wrapped.readBytes()))
            assertEquals(expected.getString("databaseHash"), hash(Exporter.create(context).toByteArray()))
            assertTrue(NativeConfigEncryption.isEncrypted(config))
            assertTrue("Restored config must work with its original Android Keystore record",
                NativeConfigEncryption.canRead(executable, config, ConfigPasswordStore(context).read(), rclone.getRcloneEnv()))
            marker.writeText(expected.put("verified", true).toString())
        }
    }
}
