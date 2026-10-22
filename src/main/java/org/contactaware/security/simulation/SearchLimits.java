package org.contactaware.security.simulation;

import java.time.Duration;
import java.util.Objects;

/** Zero means unlimited. A time budget is checked between node visits, not inside an operation. */
public record SearchLimits(long maxNodes, Duration maxElapsed) {
    public SearchLimits {
        Objects.requireNonNull(maxElapsed);
        if (maxNodes < 0 || maxElapsed.isNegative()) throw new IllegalArgumentException("Negative search budget");
        maxElapsed.toNanos();
    }
    public static SearchLimits unlimited() { return new SearchLimits(0, Duration.ZERO); }
}
