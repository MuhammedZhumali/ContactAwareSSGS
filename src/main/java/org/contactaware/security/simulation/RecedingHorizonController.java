package org.contactaware.security.simulation;

import java.util.List;
import java.util.Objects;

/** Next and Multi adapter; the shared controller executes only the first completed-search action. */
public final class RecedingHorizonController {
    private final SecurityPolicyController controller;

    public RecedingHorizonController(HorizonPolicy policy, HorizonPlanner planner) {
        this(policy, planner, false);
    }

    public RecedingHorizonController(HorizonPolicy policy, HorizonPlanner planner, boolean continuationReuse) {
        Objects.requireNonNull(policy); Objects.requireNonNull(planner);
        controller = new SecurityPolicyController(new SecurityPolicy() {
            @Override public PolicyConfiguration configuration() { return policy; }
            @Override public ContinuationReuseGuard.Rules continuationRules() { return planner.continuationRules(); }
            @Override public PolicySelection select(PlanningObservation observation, List<PlanningAction> candidates) {
                return planner.selectUnrestricted(observation, "First horizon action");
            }
        }, java.time.Duration.ZERO, (snapshot, count) -> {}, continuationReuse);
    }

    public ControlledSimulationResult run(SimulationScenario scenario) { return controller.run(scenario); }
    public ControlledSimulationResult run(ExecutionSnapshot checkpoint) { return controller.run(checkpoint); }
    public ControlledSimulationResult run(ExecutionSnapshot checkpoint, OperationOutcomes outcomes) {
        return controller.run(checkpoint, outcomes);
    }
}
