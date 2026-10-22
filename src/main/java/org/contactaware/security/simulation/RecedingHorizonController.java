package org.contactaware.security.simulation;

import org.contactaware.security.ScheduledSecurityAction;
import java.util.*;
import static org.contactaware.security.simulation.ControlledSimulationResult.Status.*;

/**
 * Offline receding-horizon execution: select once, execute only the first action, observe again.
 * Solver wall time is measured separately and does not advance the simulated physical clock.
 */
public final class RecedingHorizonController {
    private final HorizonPolicy policy;
    private final HorizonPlanner planner;
    private final SimulationEngine engine = new SimulationEngine();
    private final SecurityActionGenerator generator = new SecurityActionGenerator();

    public RecedingHorizonController(HorizonPolicy policy, HorizonPlanner planner) {
        this.policy = Objects.requireNonNull(policy);
        this.planner = Objects.requireNonNull(planner);
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
            var observation = PlanningObservation.from(current, policy.horizon());
            var search = Objects.requireNonNull(planner.plan(observation));
            if (search.status() != HorizonPlanResult.Status.COMPLETE) {
                decisions.add(new ControlledSimulationResult.Decision(observation, search, null, current.at()));
                return new ControlledSimulationResult(policy,
                        search.status() == HorizonPlanResult.Status.INCOMPLETE ? SEARCH_INCOMPLETE : NO_FEASIBLE_PLAN,
                        current, null, decisions);
            }
            var action = search.firstAction();
            ExecutionSnapshot next;
            if (action == null) {
                if (!observation.visibleContacts().isEmpty())
                    throw new IllegalArgumentException("A nonempty horizon needs a first action");
                // No guessed wakeup for hidden contacts. The trusted actual queue determines
                // the next physical event (or the experiment end), then a new observation is made.
                next = engine.controlledAdvance(current, current.scenario().end(), false);
            } else {
                if (!generator.candidates(observation).contains(action))
                    throw new IllegalArgumentException("Planner selected an inadmissible first action");
                if (action.kind() == PlanningAction.Kind.WAIT || action.kind() == PlanningAction.Kind.CONTINUE) {
                    var until = action.until();
                    for (var wake : observation.wakeUps()) if (wake.isBefore(until)) until = wake;
                    next = engine.controlledAdvance(current, until, action.kind() == PlanningAction.Kind.CONTINUE);
                } else {
                    var selected = new PlannedOperation(BranchEngine.nextOperationId(observation.issuedOperationIds()),
                            new ScheduledSecurityAction(action.securityAction(), action.at(), action.contextId(),
                                    action.keys(), "Chosen first receding-horizon action"), action.contactId());
                    var actual = Objects.requireNonNull(outcomes.forOperation(selected)).apply(selected);
                    next = engine.controlledStart(current, actual);
                }
            }
            decisions.add(new ControlledSimulationResult.Decision(observation, search, action, next.at()));
            // Valid positive-duration primitives create committed work. An immediate rejection
            // or zero-time interruption must not generate an unbounded same-time retry loop.
            if (next.at().equals(current.at()) && next.pendingOperations().equals(current.pendingOperations()))
                return new ControlledSimulationResult(policy, EXECUTION_STALLED, next, null, decisions);
            current = next;
        }
        var completed = engine.finishControlled(current);
        return new ControlledSimulationResult(policy, FINISHED, completed.snapshot(), completed.result(), decisions);
    }
}
