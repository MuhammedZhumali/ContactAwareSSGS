package org.contactaware.security.simulation;

import java.time.Instant;
import java.util.*;

/** Actual full-run accounting is separate from the successive, differently bounded forecasts. */
public record ControlledSimulationResult(PolicyConfiguration policy, Status status, ExecutionSnapshot snapshot,
                                         SimulationResult simulation, List<Decision> decisions, Computation computation) {
    public ControlledSimulationResult(PolicyConfiguration policy, Status status, ExecutionSnapshot snapshot,
                                      SimulationResult simulation, List<Decision> decisions) {
        this(policy, status, snapshot, simulation, decisions, Computation.EMPTY);
    }
    public enum Status { FINISHED, SEARCH_INCOMPLETE, NO_FEASIBLE_PLAN, EXECUTION_STALLED,
        CONTROLLER_TIME_LIMIT, CANCELLED, INTERRUPTED }
    public enum DecisionSource { SEARCH, SUFFIX_REUSE, RULE }
    public record ContinuationAudit(String rootProofReference, int suffixOffset, String checkedProofReference, int checkedOffset,
                                    ContinuationReuseGuard.Result guard, long guardNanos, long preparationNanos) { }
    public record Computation(long elapsedNanos, long guardNanos, long preparationNanos, long selectionNanos,
                              long searches, long suffixReuses, Map<String, Long> guardReasons) {
        static final Computation EMPTY = new Computation(0, 0, 0, 0, 0, 0, Map.of());
        public Computation { guardReasons = Map.copyOf(guardReasons); }
    }

    public record Decision(PlanningObservation observation, HorizonPlanResult search,
                           PlanningAction executedAction, Instant resumedAt, DecisionSource source, String reason,
                           ContinuationAudit continuation) {
        public Decision(PlanningObservation observation, HorizonPlanResult search, PlanningAction executedAction,
                        Instant resumedAt, DecisionSource source, String reason) {
            this(observation, search, executedAction, resumedAt, source, reason, null);
        }
        public Decision(PlanningObservation observation, HorizonPlanResult search,
                        PlanningAction executedAction, Instant resumedAt) {
            this(observation, search, executedAction, resumedAt, DecisionSource.SEARCH, "First horizon action");
        }
        public Decision {
            Objects.requireNonNull(observation); Objects.requireNonNull(resumedAt);
            Objects.requireNonNull(source); Objects.requireNonNull(reason);
            if ((source == DecisionSource.SEARCH) != (search != null))
                throw new IllegalArgumentException("Only search decisions have a search result");
            if (resumedAt.isBefore(observation.at())) throw new IllegalArgumentException("Decision moved time backwards");
            if (search != null && executedAction != null && !executedAction.equals(search.firstAction()))
                throw new IllegalArgumentException("Only the first selected action may be executed");
            if (source == DecisionSource.SUFFIX_REUSE && (continuation == null || executedAction == null
                    || continuation.guard().status() != ContinuationReuseGuard.Status.ALLOWED
                    || continuation.rootProofReference() == null || continuation.suffixOffset() < 1))
                throw new IllegalArgumentException("Suffix reuse requires a linked successful guard and an action");
        }
        public Instant at() { return observation.at(); }
    }

    public ControlledSimulationResult {
        Objects.requireNonNull(policy); Objects.requireNonNull(status); Objects.requireNonNull(snapshot);
        decisions = List.copyOf(decisions);
        if (computation == null) computation = Computation.EMPTY;
        if ((status == Status.FINISHED) != (simulation != null))
            throw new IllegalArgumentException("Only an actually finished experiment has final metrics");
    }

    public long totalExploredNodes() {
        return decisions.stream().filter(d -> d.search() != null).mapToLong(d -> d.search().exploredNodes()).reduce(0, Math::addExact);
    }
    public long totalSearchNanos() {
        return decisions.stream().filter(d -> d.search() != null).mapToLong(d -> d.search().elapsedNanos()).reduce(0, Math::addExact);
    }
}
