package org.contactaware.security.simulation;

import java.time.Instant;
import java.util.Objects;

/** Privileged scenario input: actual unavailability [from,until), not a forecast update. */
public record ChannelInterruption(String contactId, Instant from, Instant until) {
    public ChannelInterruption {
        if (contactId == null || contactId.isBlank()) throw new IllegalArgumentException("Contact ID is required");
        Objects.requireNonNull(from); Objects.requireNonNull(until);
        if (!from.isBefore(until)) throw new IllegalArgumentException("Channel interruption must have positive duration");
    }
}
