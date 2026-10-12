package io.github.rareities.protonbridge.util
import android.Manifest
import android.app.Application
import android.content.Context
import android.net.wifi.WifiManager
import android.webkit.WebResourceRequest
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowPowerManager

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class BatteryPrivacyPolicyTest {
    private val context = ApplicationProvider.getApplicationContext<Application>()
    @Test fun serviceCpuOwnershipDoesNotForceWifiPerformanceMode() {
        val wifi = context.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val before = Shadows.shadowOf(wifi).activeLockCount
        val locks = requireNotNull(TransferLocks.acquire(context, "fixture"))
        val cpu = ShadowPowerManager.getLatestWakeLock()
        assertTrue(cpu.isHeld)
        assertEquals(before, Shadows.shadowOf(wifi).activeLockCount)
        locks.release()
        locks.release()
        assertFalse(cpu.isHeld)
    }
    @Test fun requiredPermissionsDoNotRequireBatteryExemption() {
        Shadows.shadowOf(context).grantPermissions(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        val permissions = PermissionManager(context)
        assertFalse(permissions.grantedBatteryOptimizationExemption())
        assertTrue(permissions.hasAllPermissions())
    }
    @Test fun localMarkdownCannotFetchTrackingResourcesOrOpenCustomSchemes() {
        val view = MarkdownView(context)
        assertFalse(view.settings.javaScriptEnabled)
        assertFalse(view.settings.allowFileAccess)
        assertFalse(view.settings.allowContentAccess)
        assertTrue(view.settings.blockNetworkLoads)
        val client = view.webViewClient
        val response = client.shouldInterceptRequest(view, "https://tracker.example/image.gif")
        assertNotNull(response)
        assertEquals(-1, response!!.data.read())
        assertTrue(client.shouldOverrideUrlLoading(view, "intent://malicious/#Intent;scheme=example;end"))
        assertNull(Shadows.shadowOf(context).nextStartedActivity)
        view.destroy()
        view.loadAsset("contributors.md") // Late UI work is ignored after destruction.
    }
}
