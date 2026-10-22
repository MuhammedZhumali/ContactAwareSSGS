package org.contactaware.security.simulation;

import org.contactaware.security.ContextId;
import java.time.Instant;
import java.util.Objects;

/** Privileged experiment input. Never copied into PlanningObservation or nominal branches. */
public record TrafficKeyDisclosure(String id, ContextId contextId, Instant at, Instant detectionAt) {
    public TrafficKeyDisclosure {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("Disclosure needs an ID");
        Objects.requireNonNull(contextId); Objects.requireNonNull(at);
        if (detectionAt != null && detectionAt.isBefore(at))
            throw new IllegalArgumentException("Detection precedes disclosure");
    }
}
