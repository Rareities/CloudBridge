package ca.pkay.rcloneexplorer.proton

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class ProtonAccountStateTest {
    @Test fun pausePersistsDebouncesAndDoesNotAffectOtherAccounts() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val one = ProtonAccountPolicy.identity("one@example.com")
        val two = ProtonAccountPolicy.identity("two@example.com")
        ProtonAccountState.pause(context, one, ProtonAccountPolicy.Action.TWO_FACTOR)
        assertThrows(ProtonAccountState.NeedsUserAction::class.java) { ProtonAccountState.requireReady(context, one) }
        ProtonAccountState.requireReady(context, two)
        assertTrue(ProtonAccountState.consumeNotification(context, one))
        assertFalse(ProtonAccountState.consumeNotification(context, one))
        ProtonAccountState.pause(context, one, ProtonAccountPolicy.Action.TWO_FACTOR)
        assertFalse(ProtonAccountState.consumeNotification(context, one))
        ProtonAccountState.verified(context, one)
        ProtonAccountState.requireReady(context, one)
    }
}
