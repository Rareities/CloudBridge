package ca.pkay.rcloneexplorer.workmanager

import android.app.Application
import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import ca.pkay.rcloneexplorer.R
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class SessionGuardianPreferenceTest {
    @Test fun periodicRemoteProbesRequireExplicitOptIn() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val preferences = PreferenceManager.getDefaultSharedPreferences(context)
        val key = context.getString(R.string.pref_key_session_guardian)
        preferences.edit().remove(key).commit()
        assertFalse(SessionGuardianScheduler.isEnabled(context))
        preferences.edit().putBoolean(key, true).commit()
        assertTrue(SessionGuardianScheduler.isEnabled(context))
        preferences.edit().putBoolean(key, false).commit()
        assertFalse(SessionGuardianScheduler.isEnabled(context))
    }
}
