package org.contactaware.security.simulation;

import java.util.List;
import java.util.Objects;

/** An evaluated supplied continuation, not a claim that this plan was found by a search. */
public record PlanningResult(BranchEvaluation evaluation, List<PlanningAction> plan) {
    public PlanningResult {
        Objects.requireNonNull(evaluation);
        plan = List.copyOf(plan);
    }
}
