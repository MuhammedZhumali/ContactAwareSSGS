package org.contactaware.security.simulation;

import org.contactaware.security.ScheduledSecurityAction;
import java.time.Duration;
import java.util.*;
import static org.contactaware.security.simulation.ControlledSimulationResult.Status.*;

/**
 * Common actual execution for rule and horizon policies: select one action, execute, observe again.
 * Solver wall time is measured separately and does not advance the simulated physical clock.
 */
public final class SecurityPolicyController {
    private final SecurityPolicy policy;
    private final Duration maxElapsed;
    private final CheckpointListener listener;

    /** Called only with a complete immutable event-boundary snapshot; may persist it atomically. */
    @FunctionalInterface public interface CheckpointListener {
        void save(ExecutionSnapshot snapshot, int completedDecisions);
    }

    private final SimulationEngine engine = new SimulationEngine();
    private final SecurityActionGenerator generator = new SecurityActionGenerator();

    public SecurityPolicyController(SecurityPolicy policy) {
        this(policy, Duration.ZERO, (snapshot, count) -> {});
    }

    public SecurityPolicyController(SecurityPolicy policy, Duration maxElapsed, CheckpointListener listener) {
        this.policy = Objects.requireNonNull(policy);
        Objects.requireNonNull(policy.configuration());
        this.maxElapsed = Objects.requireNonNull(maxElapsed);
        this.listener = Objects.requireNonNull(listener);
        if (maxElapsed.isNegative()) throw new IllegalArgumentException("Negative controller budget");
        maxElapsed.toNanos();
    }

    public ControlledSimulationResult run(SimulationScenario scenario) {
        if (!scenario.plan().isEmpty())
            throw new IllegalArgumentException("Use a real checkpoint for handoff after prescribed warmup");
        return run(engine.start(scenario));
    }

    public ControlledSimulationResult run(ExecutionSnapshot checkpoint) {
        return run(checkpoint, OperationOutcomes.nominal());
    }

    public ControlledSimulationResult run(ExecutionSnapshot checkpoint, OperationOutcomes outcomes) {
        Objects.requireNonNull(outcomes);
        var current = checkpoint;
        var decisions = new ArrayList<ControlledSimulationResult.Decision>();
        try (var control = SearchControl.open(maxElapsed, SearchDiagnostics.StopReason.CONTROLLER_TIME_LIMIT)) {
        current = engine.controlledSnapshot(current);
        while (current.at().isBefore(current.scenario().end())) {
            listener.save(current, decisions.size());
            SearchControl.checkpoint("controller.observation");
            var observation = PlanningObservation.from(current, policy.configuration().horizon());
            observation = observation.withAdditionalWakeUps(policy.additionalWakeUps(observation));
            SearchControl.checkpoint("controller.candidates");
            var candidates = generator.candidates(observation);
            var response = IncidentResponse.next(current, observation, candidates);
            if (response != null && response.notice() != null) {
                SearchControl.checkpoint("controller.notification");
                var next = engine.controlledStart(current, Objects.requireNonNull(outcomes.forOperation(response.notice())).apply(response.notice()));
                decisions.add(new ControlledSimulationResult.Decision(observation, null, null, next.at(),
                        ControlledSimulationResult.DecisionSource.RULE, response.reason()));
                if (next.at().equals(current.at()) && next.pendingOperations().equals(current.pendingOperations()))
                    return new ControlledSimulationResult(policy.configuration(), EXECUTION_STALLED, next, null, decisions);
                current = next;
                continue;
            }
            SearchControl.checkpoint("controller.selection");
            var selection = response == null ? Objects.requireNonNull(policy.select(observation, candidates))
                    : PolicySelection.rule(response.action(), response.reason());
            var source = selection.search() == null ? ControlledSimulationResult.DecisionSource.RULE
                    : ControlledSimulationResult.DecisionSource.SEARCH;
            var search = selection.search();
            if (search != null && search.status() != HorizonPlanResult.Status.COMPLETE) {
                decisions.add(new ControlledSimulationResult.Decision(observation, search, null, current.at(), source, selection.reason()));
                return new ControlledSimulationResult(policy.configuration(),
                        search.status() == HorizonPlanResult.Status.INCOMPLETE ? stoppedStatus(search.diagnostics().stopReason()) : NO_FEASIBLE_PLAN,
                        current, null, decisions);
            }
            var action = selection.action();
            if (action != null && !candidates.contains(action))
                throw new IllegalArgumentException("Policy selected an inadmissible first action");
            SearchControl.checkpoint("controller.execution");
            var next = executeAction(current, observation, action, outcomes);
            decisions.add(new ControlledSimulationResult.Decision(observation, search, action, next.at(), source, selection.reason()));
            // Valid positive-duration primitives create committed work. An immediate rejection
            // or zero-time interruption must not generate an unbounded same-time retry loop.
            if (next.at().equals(current.at()) && next.pendingOperations().equals(current.pendingOperations()))
                return new ControlledSimulationResult(policy.configuration(), EXECUTION_STALLED, next, null, decisions);
            current = next;
        }
        listener.save(current, decisions.size());
        SearchControl.checkpoint("controller.finish");
        var completed = engine.finishControlled(current);
        return new ControlledSimulationResult(policy.configuration(), FINISHED, completed.snapshot(), completed.result(), decisions);
        } catch (SearchControl.Stopped stopped) {
            // The attempted transition works on a private runtime. Only committed immutable
            // snapshots replace current; cancellation creates no physical failure/expiry event.
            listener.save(current, decisions.size());
            return new ControlledSimulationResult(policy.configuration(), stoppedStatus(stopped.reason()), current, null, decisions);
        }
    }

    private static ControlledSimulationResult.Status stoppedStatus(SearchDiagnostics.StopReason reason) {
        return switch (reason) {
            case CONTROLLER_TIME_LIMIT -> CONTROLLER_TIME_LIMIT;
            case CANCELLED -> CANCELLED;
            case INTERRUPTED -> INTERRUPTED;
            default -> SEARCH_INCOMPLETE;
        };
    }

    /** Shared actual action execution for the controller and factual journal replay; no search. */
    static ExecutionSnapshot executeAction(ExecutionSnapshot current, PlanningObservation observation,
                                           PlanningAction action, OperationOutcomes outcomes) {
        var engine = new SimulationEngine();
        if (action == null) {
            if (!observation.visibleContacts().isEmpty())
                throw new IllegalArgumentException("A nonempty horizon needs a first action");
            return engine.controlledAdvance(current, current.scenario().end(), false);
        }
        if (action.kind() == PlanningAction.Kind.WAIT || action.kind() == PlanningAction.Kind.CONTINUE) {
            var until = action.until();
            for (var wake : observation.wakeUps()) if (wake.isBefore(until)) until = wake;
            return engine.controlledAdvance(current, until, action.kind() == PlanningAction.Kind.CONTINUE);
        }
        var selected = new PlannedOperation(BranchEngine.nextOperationId(observation.issuedOperationIds()),
                new ScheduledSecurityAction(action.securityAction(), action.at(), action.contextId(),
                        action.keys(), "Chosen policy action"), action.contactId());
        return engine.controlledStart(current, Objects.requireNonNull(outcomes.forOperation(selected)).apply(selected));
    }
}

