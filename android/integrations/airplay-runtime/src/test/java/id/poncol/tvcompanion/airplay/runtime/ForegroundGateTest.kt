package id.poncol.tvcompanion.airplay.runtime
import android.app.NotificationManager
import org.junit.Assert.*
import org.junit.Test

class ForegroundGateTest {
    @Test fun holdsOnlyWithNotificationsAndAnUnblockedChannel() {
        assertTrue(AirPlayService.foregroundAllowed(true, null))
        assertTrue(AirPlayService.foregroundAllowed(true, NotificationManager.IMPORTANCE_LOW))
        assertFalse(AirPlayService.foregroundAllowed(true, NotificationManager.IMPORTANCE_NONE))
        assertFalse(AirPlayService.foregroundAllowed(false, NotificationManager.IMPORTANCE_LOW))
    }
}
