package dev.agentkit.accessdesk.web;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * The application's clock: real time plus an offset a demo can push forward, so a grant that lasts hours can
 * be watched expiring in seconds. Everything in the desk reads time from here.
 */
public final class DemoClock implements Supplier<Instant> {

    private final AtomicReference<Duration> offset = new AtomicReference<>(Duration.ZERO);

    @Override
    public Instant get() {
        return Instant.now().plus(offset.get());
    }

    /** Moves the clock forward by {@code by}, which must not be negative. */
    public Instant advance(Duration by) {
        if (by.isNegative()) {
            throw new IllegalArgumentException("The clock only moves forward");
        }
        offset.updateAndGet(current -> current.plus(by));
        return get();
    }

    public Duration offset() {
        return offset.get();
    }
}
