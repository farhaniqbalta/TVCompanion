package id.poncol.tvcompanion.notifications.presentation
import org.junit.Assert.*
import org.junit.Test
class UrgentContentPolicyTest {
    @Test fun privacyAndUnknownModesNeverExposeUrgentText() {
        for (mode in listOf("privacy", "invalid")) {
            val state = UrgentContentPolicy.from(mode); assertFalse(state.titleVisible); assertFalse(state.bodyVisible)
        }
        assertTrue(UrgentContentPolicy.from("compact").titleVisible); assertFalse(UrgentContentPolicy.from("compact").bodyVisible)
        assertTrue(UrgentContentPolicy.from("expanded").titleVisible); assertTrue(UrgentContentPolicy.from("expanded").bodyVisible)
    }
}
