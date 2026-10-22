package org.contactaware.security.simulation;

import org.contactaware.security.SecurityAction;
import java.time.Instant;
import java.util.*;

/** Trusted execution-only environment. Called once when a selected operation actually starts. */
@FunctionalInterface
public interface OperationOutcomes {
    Outcome forOperation(PlannedOperation selected);

    static OperationOutcomes nominal() { return ignored -> new Outcome(null, Set.of()); }

    /** Cannot replace the chosen action, keys, station, identifier or start time. */
    record Outcome(Instant interruptAt, Set<SecurityAction> missingAcknowledgements) {
        public Outcome { missingAcknowledgements = Set.copyOf(missingAcknowledgements); }
        PlannedOperation apply(PlannedOperation selected) {
            return new PlannedOperation(selected.id(), selected.intent(), selected.contactId(), interruptAt, missingAcknowledgements);
        }
    }
}
