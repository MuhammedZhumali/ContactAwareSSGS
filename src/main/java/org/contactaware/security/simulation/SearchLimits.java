package org.contactaware.security.simulation;

import java.time.Duration;
import java.util.Objects;

/** Zero means unlimited. Elapsed limits are cooperative, including initialization and inner loops;
 * host pauses and indivisible library/native calls still require an external watchdog. */
public record SearchLimits(long maxNodes, Duration maxElapsed) {
    public SearchLimits {
        Objects.requireNonNull(maxElapsed);
        if (maxNodes < 0 || maxElapsed.isNegative()) throw new IllegalArgumentException("Negative search budget");
        maxElapsed.toNanos();
    }
    public static SearchLimits unlimited() { return new SearchLimits(0, Duration.ZERO); }
}
