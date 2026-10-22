package org.contactaware.security.simulation;

import org.contactaware.security.*;
import java.time.*;
import java.util.*;
import static org.contactaware.security.simulation.HorizonWitness.*;

final class BaselinePolicyTestSupport {
    private BaselinePolicyTestSupport() { }
    static SecurityCostProfile costs() {
        var actions = new EnumMap<SecurityAction, SecurityCostProfile.OperationCost>(SecurityAction.class);
        for (var action : List.of(SecurityAction.PREPARE, SecurityAction.INSTALL, SecurityAction.SWITCH, SecurityAction.OPEN, SecurityAction.REUSE))
            actions.put(action, new SecurityCostProfile.OperationCost(List.of(new SecurityCostProfile.Phase(
                    SecurityCostProfile.Resource.GROUND_CPU, Duration.ofSeconds(action == SecurityAction.PREPARE ? 2 : 1), 0))));
        actions.put(SecurityAction.STOP, new SecurityCostProfile.OperationCost(List.of()));
        actions.put(SecurityAction.EXPIRE_CONTEXT, new SecurityCostProfile.OperationCost(List.of()));
        return new SecurityCostProfile("baseline-test-synthetic", SecurityCostProfile.Provenance.SYNTHETIC_ASSUMPTION,
                "Unit-test model: two-second preparation, one-second online operations and frames", actions);
    }
    static SecurityState empty(SecurityConstraints constraints, long time) {
        return new LifecycleTransitions(constraints).createContext(SecurityState.empty(SATELLITE, at(time)), A,
                "baseline-up", "baseline-down", at(time));
    }
    static SecurityState historicalUnused(SecurityConstraints constraints) {
        var transitions = new LifecycleTransitions(constraints);
        var state = empty(constraints, -2);
        state = transitions.prepareKeys(state, A, OLD_A, at(-1));
        var past = window("historical-activation", A, 0, 1);
        state = transitions.confirmInstallation(state, A, OLD_A, past, at(0));
        state = transitions.activateKeys(state, A, OLD_A, past, at(0));
        return transitions.bindKeys(state, A, OLD_A, past, at(0));
    }
    static SecurityConstraints constraints(long cryptoperiod) {
        return new SecurityConstraints(Duration.ofSeconds(cryptoperiod), Duration.ofSeconds(1000), Duration.ofSeconds(1000), Set.of(A), Set.of());
    }
    static SimulationContact contact(String name, long start, long end) {
        return new SimulationContact(window(name, A, start, end),
                new SimulationContact.PayloadProfile(100, 100, Long.MAX_VALUE, 0, 100, 0));
    }
    static SimulationScenario scenario(String name, long start, long end, SecurityState state, SecurityConstraints constraints,
                                       List<SimulationContact> contacts, List<PlannedOperation> plan) {
        return new SimulationScenario(name, at(start), at(end), state, constraints, costs(), contacts, plan);
    }
    static PolicySelection select(SecurityPolicy policy, PlanningObservation observation) {
        var withTimers = observation.withAdditionalWakeUps(policy.additionalWakeUps(observation));
        return policy.select(withTimers, new SecurityActionGenerator().candidates(withTimers));
    }
}
