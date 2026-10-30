package id.poncol.tvcompanion.uxplay

import org.junit.Assert.*
import org.junit.Test

class HlsProgressTest {
    @Test fun progressAdvancesWithoutAPollingLoopAndClampsAtDuration() {
        val progress = HlsProgress(20.0, 5.0, 1.0, true, false, false, 1_000_000_000)
        assertEquals(8.0, progress.values(4_000_000_000)[1], 0.001)
        assertEquals(20.0, progress.values(100_000_000_000)[1], 0.001)
        assertEquals(5.0, progress.values(0)[1], 0.001)
    }
    @Test fun pauseBufferingAndEndNeverInventPlaybackProgress() {
        val active = HlsProgress(20.0, 5.0, 1.0, true, false, false, 1_000_000_000)
        for (progress in listOf(active.copy(rate = 0.0), active.copy(buffering = true), active.copy(ended = true), active.copy(ready = false))) {
            assertEquals(5.0, progress.values(9_000_000_000)[1], 0.001)
        }
        assertEquals(0.0, HlsProgress().values(9_000_000_000)[3], 0.0)
    }
    @Test fun requestsAllowProxyAndHttpsButRejectOtherSchemesPortsAndUnboundedInput() {
        assertTrue(HlsRequestPolicy.accepts("http://localhost:7000/test/master.m3u8", 7000, 0.0))
        assertTrue(HlsRequestPolicy.accepts("https://example.org/master.m3u8?token=private", 7000, 25.0))
        for (url in listOf("file:///private/video", "content://video/1", "http://example.org/a.m3u8", "http://localhost:7001/a.m3u8",
                "http://localhost.evil:7000/a.m3u8", "https://user:password@example.org/a", "https://example.org/a#fragment", "x".repeat(8193))) {
            assertFalse(HlsRequestPolicy.accepts(url, 7000, 0.0))
        }
        assertFalse(HlsRequestPolicy.accepts("https://example.org/a", 7000, Double.NaN))
        assertFalse(HlsRequestPolicy.accepts("https://example.org/a", 7000, -1.0))
        assertFalse(HlsRequestPolicy.accepts("https://example.org/a", 7000, 604801.0))
    }
}
