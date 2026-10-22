package org.contactaware.security.simulation;

import java.time.Duration;
import java.util.Objects;

/** Incremental objective since one fixed observation; prior work is a common sunk cost. */
public record BranchEvaluation(long servedContacts, Duration usefulSecureTime, Duration controlCpu,
                               long managementBytes, long newKeys) {
    public BranchEvaluation {
        Objects.requireNonNull(usefulSecureTime); Objects.requireNonNull(controlCpu);
        if (servedContacts < 0 || usefulSecureTime.isNegative() || controlCpu.isNegative()
                || managementBytes < 0 || newKeys < 0)
            throw new IllegalArgumentException("Continuation cannot subtract previously realized work");
    }
}
