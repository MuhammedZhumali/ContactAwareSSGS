package org.contactaware.security.simulation;

import java.util.Objects;

/** A rule decision has no search result and makes no completeness/optimality claim. */
public record PolicySelection(PlanningAction action, HorizonPlanResult search, String reason) {
    public PolicySelection {
        Objects.requireNonNull(reason);
        if (search != null && action != null && !action.equals(search.firstAction()))
            throw new IllegalArgumentException("Only the first selected search action may be executed");
    }
    public static PolicySelection rule(PlanningAction action, String reason) {
        return new PolicySelection(action, null, reason);
    }
}
