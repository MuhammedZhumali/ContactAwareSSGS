package org.contactaware.security.simulation;

import java.util.*;

/** Best terminal continuation found; only COMPLETE certifies an optimum of the generated finite space. */
public record HorizonPlanResult(List<PlanningAction> bestPlan, BranchEvaluation score, PlanningAction firstAction,
                                Status status, long exploredNodes, long elapsedNanos, SearchDiagnostics diagnostics) {
    public enum Status {
        COMPLETE,   // All continuations explored or soundly covered/pruned; best terminal plan exists.
        INCOMPLETE, // Budget stopped the search. A terminal incumbent may or may not exist.
        INFEASIBLE  // Exhausted the search without an admissible terminal continuation.
    }
    public HorizonPlanResult {
        bestPlan = List.copyOf(bestPlan);
        Objects.requireNonNull(status);
        Objects.requireNonNull(diagnostics);
        if (exploredNodes < 0 || elapsedNanos < 0) throw new IllegalArgumentException("Negative search accounting");
        if (!Objects.equals(firstAction, bestPlan.isEmpty() ? null : bestPlan.getFirst()))
            throw new IllegalArgumentException("First action must match the best plan");
        if (score == null && (!bestPlan.isEmpty() || status == Status.COMPLETE)
                || score != null && status == Status.INFEASIBLE)
            throw new IllegalArgumentException("Result status and terminal incumbent disagree");
    }
}
