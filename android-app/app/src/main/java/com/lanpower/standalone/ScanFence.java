package com.lanpower.standalone;

import java.util.concurrent.atomic.AtomicLong;

/** A scan started before a command cannot overwrite its newer outcome. */
final class ScanFence {
    private final AtomicLong epoch = new AtomicLong();
    long capture() { return epoch.get(); }
    void commandStarted() { epoch.incrementAndGet(); }
    boolean accepts(long token) { return token == epoch.get(); }
}
