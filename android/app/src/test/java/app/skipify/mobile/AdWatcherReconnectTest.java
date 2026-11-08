package app.skipify.mobile;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class AdWatcherReconnectTest {
    @Test public void backsOffQuicklyThenCapsAtFifteenMinutes() {
        assertEquals(2_000L, AdWatcher.rebindDelayMs(1));
        assertEquals(5_000L, AdWatcher.rebindDelayMs(2));
        assertEquals(10_000L, AdWatcher.rebindDelayMs(3));
        assertEquals(30_000L, AdWatcher.rebindDelayMs(4));
        assertEquals(60_000L, AdWatcher.rebindDelayMs(5));
        assertEquals(300_000L, AdWatcher.rebindDelayMs(6));
        assertEquals(900_000L, AdWatcher.rebindDelayMs(7));
        assertEquals(900_000L, AdWatcher.rebindDelayMs(50));
    }

    @Test public void suppressesOnlyDuplicateRequestsInsideTwoSecondWindow() {
        assertTrue(AdWatcher.recentRebindRequest(10_999, 9_000));
        assertFalse(AdWatcher.recentRebindRequest(11_000, 9_000));
        assertFalse(AdWatcher.recentRebindRequest(8_000, 9_000));
        assertFalse(AdWatcher.recentRebindRequest(10_000, 0));
    }
}
