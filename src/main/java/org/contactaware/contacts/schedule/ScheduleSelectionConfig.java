package org.contactaware.contacts.schedule;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.Objects;

public record ScheduleSelectionConfig(BigDecimal switchGuardSeconds) {
    public static final String VERSION = "1.0";
    public static final ScheduleSelectionConfig DEFAULT = new ScheduleSelectionConfig(BigDecimal.ZERO);

    public ScheduleSelectionConfig {
        Objects.requireNonNull(switchGuardSeconds, "switchGuardSeconds");
        switchGuardSeconds = switchGuardSeconds.stripTrailingZeros();
        switchGuardSeconds = switchGuardSeconds.setScale(Math.max(0, switchGuardSeconds.scale()));
        if (switchGuardSeconds.signum() < 0 || switchGuardSeconds.scale() > 9)
            throw new IllegalArgumentException("Switch guard must be nonnegative with at most nanosecond precision");
        switchGuardSeconds.toBigInteger().longValueExact();
    }

    public Duration guard() {
        long seconds = switchGuardSeconds.toBigInteger().longValueExact();
        int nanos = switchGuardSeconds.subtract(BigDecimal.valueOf(seconds)).movePointRight(9).intValueExact();
        return Duration.ofSeconds(seconds, nanos);
    }
}
