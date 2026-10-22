package org.contactaware.security.simulation;

import org.contactaware.security.SecurityAction;
import java.time.Instant;
import java.util.List;

/** Explicit experimental timing rule; the default preserves the frozen v2 decision space. */
public enum DecisionTimeModel {
    V2_EVENT_BOUNDARIES,
    /** Permit a cost-free WAIT to the nominal completion time of a preparation started now. */
    PREPARE_DURATION_WAIT;

    public List<Instant> additionalWakeUps(PlanningObservation observation) {
        if (this == V2_EVENT_BOUNDARIES || !observation.at().isBefore(observation.evaluationEnd())) return List.of();
        var duration = observation.costs().cost(SecurityAction.PREPARE).duration();
        if (duration.isZero()) throw new IllegalArgumentException("The experimental timer must have positive duration");
        var candidate = observation.at().plus(duration);
        return candidate.isAfter(observation.evaluationEnd()) ? List.of() : List.of(candidate);
    }

    public PlanningObservation apply(PlanningObservation observation) {
        return this == V2_EVENT_BOUNDARIES ? observation
                : observation.withAdditionalWakeUps(additionalWakeUps(observation));
    }
}
