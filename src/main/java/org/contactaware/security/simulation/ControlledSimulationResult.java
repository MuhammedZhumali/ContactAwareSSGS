package org.contactaware.security.simulation;

import java.time.Instant;
import java.util.*;

/** Actual full-run accounting is separate from the successive, differently bounded forecasts. */
public record ControlledSimulationResult(HorizonPolicy policy, Status status, ExecutionSnapshot snapshot,
                                         SimulationResult simulation, List<Decision> decisions) {
    public enum Status { FINISHED, SEARCH_INCOMPLETE, NO_FEASIBLE_PLAN, EXECUTION_STALLED }

    public record Decision(PlanningObservation observation, HorizonPlanResult search,
                           PlanningAction executedAction, Instant resumedAt) {
        public Decision {
            Objects.requireNonNull(observation); Objects.requireNonNull(search); Objects.requireNonNull(resumedAt);
            if (resumedAt.isBefore(observation.at())) throw new IllegalArgumentException("Decision moved time backwards");
            if (executedAction != null && !executedAction.equals(search.firstAction()))
                throw new IllegalArgumentException("Only the first selected action may be executed");
        }
        public Instant at() { return observation.at(); }
    }

    public ControlledSimulationResult {
        Objects.requireNonNull(policy); Objects.requireNonNull(status); Objects.requireNonNull(snapshot);
        decisions = List.copyOf(decisions);
        if ((status == Status.FINISHED) != (simulation != null))
            throw new IllegalArgumentException("Only an actually finished experiment has final metrics");
    }

    public long totalExploredNodes() {
        return decisions.stream().mapToLong(d -> d.search().exploredNodes()).reduce(0, Math::addExact);
    }
    public long totalSearchNanos() {
        return decisions.stream().mapToLong(d -> d.search().elapsedNanos()).reduce(0, Math::addExact);
    }
}
