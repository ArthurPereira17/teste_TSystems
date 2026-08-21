package com.tsystems.challenge.orders.testsupport;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/** A test-only Clock that can be advanced explicitly, to make backoff/scheduling logic deterministic. */
public class MutableClock extends Clock {
    private Instant instant;
    private final ZoneId zone;

    public MutableClock(Instant instant) {
        this(instant, ZoneOffset.UTC);
    }

    public MutableClock(Instant instant, ZoneId zone) {
        this.instant = instant;
        this.zone = zone;
    }

    public void advance(java.time.Duration duration) {
        this.instant = this.instant.plus(duration);
    }

    @Override
    public ZoneId getZone() {
        return zone;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return new MutableClock(instant, zone);
    }

    @Override
    public Instant instant() {
        return instant;
    }
}
