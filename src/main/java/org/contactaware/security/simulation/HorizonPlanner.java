package org.contactaware.security.simulation;

/** The information boundary: a solver receives only the permitted observation. */
@FunctionalInterface
public interface HorizonPlanner {
    HorizonPlanResult plan(PlanningObservation observation);
}
