package org.contactaware.security.simulation;

/** The information boundary: a solver receives only the permitted observation. */
@FunctionalInterface
public interface HorizonPlanner {
    HorizonPlanResult plan(PlanningObservation observation);
    /** Null means provenance/settings are not certified for suffix reuse (e.g. arbitrary adapters). */
    default ContinuationReuseGuard.Rules continuationRules() { return null; }
    default PolicySelection selectUnrestricted(PlanningObservation observation, String reason) {
        var result = plan(observation);
        return new PolicySelection(result.status() == HorizonPlanResult.Status.COMPLETE ? result.firstAction() : null, result, reason);
    }
}
