package org.contactaware.security.simulation;

import org.contactaware.security.ScheduledSecurityAction;
import java.util.*;
import static org.contactaware.security.simulation.ControlledSimulationResult.Status.*;

/**
 * Common actual execution for rule and horizon policies: select one action, execute, observe again.
 * Solver wall time is measured separately and does not advance the simulated physical clock.
 */
public final class SecurityPolicyController {
    private final SecurityPolicy policy;

    private final SimulationEngine engine = new SimulationEngine();
    private final SecurityActionGenerator generator = new SecurityActionGenerator();

    public SecurityPolicyController(SecurityPolicy policy) {
        this.policy = Objects.requireNonNull(policy);
        Objects.requireNonNull(policy.configuration());
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
        var current = engine.controlledSnapshot(checkpoint);
        var decisions = new ArrayList<ControlledSimulationResult.Decision>();
        while (current.at().isBefore(current.scenario().end())) {
            var observation = PlanningObservation.from(current, policy.configuration().horizon());
            observation = observation.withAdditionalWakeUps(policy.additionalWakeUps(observation));
            var candidates = generator.candidates(observation);
            var response = IncidentResponse.next(current, observation, candidates);
            if (response != null && response.notice() != null) {
                var next = engine.controlledStart(current, Objects.requireNonNull(outcomes.forOperation(response.notice())).apply(response.notice()));
                decisions.add(new ControlledSimulationResult.Decision(observation, null, null, next.at(),
                        ControlledSimulationResult.DecisionSource.RULE, response.reason()));
                if (next.at().equals(current.at()) && next.pendingOperations().equals(current.pendingOperations()))
                    return new ControlledSimulationResult(policy.configuration(), EXECUTION_STALLED, next, null, decisions);
                current = next;
                continue;
            }
            var selection = response == null ? Objects.requireNonNull(policy.select(observation, candidates))
                    : PolicySelection.rule(response.action(), response.reason());
            var source = selection.search() == null ? ControlledSimulationResult.DecisionSource.RULE
                    : ControlledSimulationResult.DecisionSource.SEARCH;
            var search = selection.search();
            if (search != null && search.status() != HorizonPlanResult.Status.COMPLETE) {
                decisions.add(new ControlledSimulationResult.Decision(observation, search, null, current.at(), source, selection.reason()));
                return new ControlledSimulationResult(policy.configuration(),
                        search.status() == HorizonPlanResult.Status.INCOMPLETE ? SEARCH_INCOMPLETE : NO_FEASIBLE_PLAN,
                        current, null, decisions);
            }
            var action = selection.action();
            ExecutionSnapshot next;
            if (action == null) {
                if (!observation.visibleContacts().isEmpty())
                    throw new IllegalArgumentException("A nonempty horizon needs a first action");
                // No guessed wakeup for hidden contacts. The trusted actual queue determines
                // the next physical event (or the experiment end), then a new observation is made.
                next = engine.controlledAdvance(current, current.scenario().end(), false);
            } else {
                if (!candidates.contains(action))
                    throw new IllegalArgumentException("Policy selected an inadmissible first action");
                if (action.kind() == PlanningAction.Kind.WAIT || action.kind() == PlanningAction.Kind.CONTINUE) {
                    var until = action.until();
                    for (var wake : observation.wakeUps()) if (wake.isBefore(until)) until = wake;
                    next = engine.controlledAdvance(current, until, action.kind() == PlanningAction.Kind.CONTINUE);
                } else {
                    var selected = new PlannedOperation(BranchEngine.nextOperationId(observation.issuedOperationIds()),
                            new ScheduledSecurityAction(action.securityAction(), action.at(), action.contextId(),
                                    action.keys(), "Chosen policy action"), action.contactId());
                    var actual = Objects.requireNonNull(outcomes.forOperation(selected)).apply(selected);
                    next = engine.controlledStart(current, actual);
                }
            }
            decisions.add(new ControlledSimulationResult.Decision(observation, search, action, next.at(), source, selection.reason()));
            // Valid positive-duration primitives create committed work. An immediate rejection
            // or zero-time interruption must not generate an unbounded same-time retry loop.
            if (next.at().equals(current.at()) && next.pendingOperations().equals(current.pendingOperations()))
                return new ControlledSimulationResult(policy.configuration(), EXECUTION_STALLED, next, null, decisions);
            current = next;
        }
        var completed = engine.finishControlled(current);
        return new ControlledSimulationResult(policy.configuration(), FINISHED, completed.snapshot(), completed.result(), decisions);
    }
}

