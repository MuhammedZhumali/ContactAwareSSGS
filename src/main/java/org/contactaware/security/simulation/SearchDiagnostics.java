package org.contactaware.security.simulation;

import java.util.*;

/** Actual work counters, not a count of all conceptual leaves represented by reused suffixes. */
public record SearchDiagnostics(long terminalPlans, long expandedNodes, int maxDepth,
                                long generatedActions, long executedTransitions,
                                long repeatedTransitions, long collapsedWaitTransitions,
                                long memoHits, long memoEntries, long memoStoreSkips,
                                long generationNanos, long executionNanos, long boundNanos, long boundPrunedNodes,
                                long preExecutionWaitSkips, long canonicalBoundPrunedNodes,
                                long rootCandidateCount, StopReason stopReason,
                                Map<PlanningAction.Kind, Long> rootGeneratedByAction,
                                Map<PlanningAction.Kind, Long> generatedByAction,
                                Map<PlanningAction.Kind, Long> executedByAction,
                                SearchRuntimeTelemetry runtimeTelemetry) {
    public enum StopReason { NONE, NODE_LIMIT, TIME_LIMIT, CONTROLLER_TIME_LIMIT, DECISION_TIME_LIMIT, CANCELLED, INTERRUPTED }
    public SearchDiagnostics {
        rootGeneratedByAction = Collections.unmodifiableMap(new EnumMap<>(rootGeneratedByAction));
        generatedByAction = Collections.unmodifiableMap(new EnumMap<>(generatedByAction));
        executedByAction = Collections.unmodifiableMap(new EnumMap<>(executedByAction));
    }

    static final class Counter {
        long terminalPlans, expandedNodes, generatedActions, executedTransitions;
        long repeatedTransitions, collapsedWaitTransitions, memoHits, memoEntries, memoStoreSkips;
        long generationNanos, executionNanos, boundNanos, boundPrunedNodes;
        long preExecutionWaitSkips, canonicalBoundPrunedNodes, rootCandidateCount;
        StopReason stopReason = StopReason.NONE;
        SearchRuntimeTelemetry runtimeTelemetry;
        int maxDepth;
        final Map<PlanningAction.Kind, Long> rootGeneratedByAction = counts();
        final Map<PlanningAction.Kind, Long> generatedByAction = counts();
        final Map<PlanningAction.Kind, Long> executedByAction = counts();

        private static Map<PlanningAction.Kind, Long> counts() {
            var map = new EnumMap<PlanningAction.Kind, Long>(PlanningAction.Kind.class);
            for (var kind : PlanningAction.Kind.values()) map.put(kind, 0L);
            return map;
        }

        SearchDiagnostics snapshot() {
            return new SearchDiagnostics(terminalPlans, expandedNodes, maxDepth, generatedActions, executedTransitions,
                    repeatedTransitions, collapsedWaitTransitions, memoHits, memoEntries, memoStoreSkips,
                    generationNanos, executionNanos, boundNanos, boundPrunedNodes,
                    preExecutionWaitSkips, canonicalBoundPrunedNodes, rootCandidateCount, stopReason,
                    rootGeneratedByAction, generatedByAction, executedByAction, runtimeTelemetry);
        }
    }
}
