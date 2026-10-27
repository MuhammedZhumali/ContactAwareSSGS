package org.contactaware.security.simulation;

import java.util.List;

/** Experiment adapter only. Uses the same actual checkpoint for both solver modes. */
public final class AituInitialState {
    public static ExecutionSnapshot create(SimulationScenario source, String initialization) {
        var engine = new SimulationEngine();
        if (initialization.equals("prepared")) {
            var operation = HorizonWitness.operation("warmup", org.contactaware.security.SecurityAction.PREPARE,
                    -2, HorizonWitness.A, HorizonWitness.NEW_A, null);
            var scenario = new SimulationScenario(source.name(), source.start(), source.end(), source.initialState(),
                    source.constraints(), source.costs(), source.contacts(), List.of(operation),
                    source.disclosures(), source.channelInterruptions());
            return engine.controlledSnapshot(engine.advance(engine.start(scenario), source.contacts().getFirst().window().start()));
        }
        if (!source.plan().isEmpty()) throw new IllegalArgumentException("Autonomous input must have no plan");
        var initial = engine.controlledSnapshot(engine.start(source));
        if (initialization.equals("autonomous-t0"))
            return engine.controlledAdvance(initial, source.contacts().getFirst().window().start(), false);
        if (!initialization.equals("autonomous")) throw new IllegalArgumentException("Unknown initialization");
        return initial;
    }
}
