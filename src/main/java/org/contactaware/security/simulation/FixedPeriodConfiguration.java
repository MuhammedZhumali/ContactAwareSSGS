package org.contactaware.security.simulation;

import java.time.Duration;
import java.util.Objects;

/** A period is measured from the latest confirmed pair activation, including initial history. */
public record FixedPeriodConfiguration(Duration period) implements PolicyConfiguration {
    public FixedPeriodConfiguration {
        Objects.requireNonNull(period);
        if (period.isNegative() || period.isZero()) throw new IllegalArgumentException("Renewal period must be positive");
        period.toNanos();
    }
    @Override public String name() { return "FIXED_PERIOD"; }
    @Override public int horizon() { return 1; }
}
