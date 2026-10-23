package org.contactaware.security.simulation;

import java.time.Instant;
import java.util.List;

/** Selects from the common executor's admissible actions using only the permitted observation. */
public interface SecurityPolicy {
    PolicyConfiguration configuration();
    PolicySelection select(PlanningObservation observation, List<PlanningAction> candidates);

    /** Policy timers derived from observed history, e.g. an activation plus a renewal period. */
    default List<Instant> additionalWakeUps(PlanningObservation observation) { return List.of(); }
    /** Only adapters calling unrestricted plan(observation) may supply this provenance. */
    default ContinuationReuseGuard.Rules continuationRules() { return null; }
}
