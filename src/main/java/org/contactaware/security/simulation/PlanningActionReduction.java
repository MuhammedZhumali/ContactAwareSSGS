package org.contactaware.security.simulation;

import java.time.Instant;
import java.util.*;

/** Exact reductions that do not execute, rename or mutate a continuation. */
final class PlanningActionReduction {
    private PlanningActionReduction() { }

    /**
     * BranchEngine clips WAIT to the first observation wake before calling the same
     * manualAdvance(false). Equal clipped targets therefore produce identical snapshots,
     * even if a committed frame, operation completion or expiry clips them further.
     * Keep the canonical smallest action in each group and preserve caller traversal order.
     * The input must be candidates generated for this same observation.
     */
    static List<PlanningAction> coalesceWaits(PlanningObservation observation, List<PlanningAction> orderedCandidates) {
        Objects.requireNonNull(observation); Objects.requireNonNull(orderedCandidates);
        var comparator = new PlanningResultComparator();
        var representatives = new HashMap<Instant, PlanningAction>();
        for (var action : orderedCandidates) {
            SearchControl.checkpoint("candidateProcessing.waitReduction");
            if (action.kind() != PlanningAction.Kind.WAIT) continue;
            representatives.merge(effectiveWaitTarget(observation, action), action,
                    (first, second) -> comparator.compareActions(first, second) <= 0 ? first : second);
        }
        return orderedCandidates.stream().filter(action -> action.kind() != PlanningAction.Kind.WAIT
                || action.equals(representatives.get(effectiveWaitTarget(observation, action)))).toList();
    }

    /**
     * A first difference already present cannot be repaired by appending actions.
     * Call only after proving the branch cannot improve the incumbent's metric vector.
     * Equality through the shorter list is deliberately inconclusive: its future suffix
     * can introduce the decisive difference. No operation/key identifiers are renamed.
     */
    static boolean prefixAlreadyWorse(List<PlanningAction> prefix, List<PlanningAction> incumbent) {
        Objects.requireNonNull(prefix); Objects.requireNonNull(incumbent);
        var comparator = new PlanningResultComparator();
        for (int index = 0; index < Math.min(prefix.size(), incumbent.size()); index++) {
            SearchControl.checkpoint("bound.canonicalPrefix");
            int order = comparator.compareActions(prefix.get(index), incumbent.get(index));
            if (order != 0) return order > 0;
        }
        return false;
    }

    private static Instant effectiveWaitTarget(PlanningObservation observation, PlanningAction action) {
        var until = action.until();
        for (var wake : observation.wakeUps()) {
            SearchControl.checkpoint("candidateProcessing.waitWake");
            if (wake.isBefore(until)) until = wake;
        }
        return until;
    }
}
