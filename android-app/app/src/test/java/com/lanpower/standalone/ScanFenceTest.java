package com.lanpower.standalone;

import org.junit.Test;
import static org.junit.Assert.*;

public final class ScanFenceTest {
    @Test public void delayedPreCommandScanCannotReplaceConfirmedScreenState() {
        ScanFence fence = new ScanFence();
        long oldScan = fence.capture();
        fence.commandStarted();
        String screen = "unlocked"; // The command's signed ACK arrived first.
        if (fence.accepts(oldScan)) screen = "locked"; // Delayed old discovery.
        assertEquals("unlocked", screen);
        long newScan = fence.capture();
        assertTrue(fence.accepts(newScan));
        fence.commandStarted();
        assertFalse(fence.accepts(oldScan));
        assertFalse(fence.accepts(newScan));
    }
}
