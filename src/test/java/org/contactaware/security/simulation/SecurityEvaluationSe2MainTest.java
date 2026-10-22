package org.contactaware.security.simulation;

import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
import static org.contactaware.security.simulation.HorizonWitness.*;

class SecurityEvaluationSe2MainTest {
    @Test void attackAndControlShareAllPhysicalInputsAndPreserveHistoricalKeyAge() {
        var original = PolicyExperimentFixtures.witness(Sequence.X, false).scenario();
        var control = SecurityEvaluationSe2Main.incidentScenario(original, A, null, "none");
        var attacked = SecurityEvaluationSe2Main.incidentScenario(original, A, at(18), "detected");
        assertThat(attacked.initialState()).isSameAs(control.initialState());
        assertThat(attacked.constraints()).isSameAs(control.constraints());
        assertThat(attacked.contacts()).isEqualTo(control.contacts());
        assertThat(attacked.costs()).isSameAs(control.costs());
        assertThat(attacked.start()).isEqualTo(control.start());
        assertThat(attacked.end()).isEqualTo(control.end());
        assertThat(attacked.plan()).isEqualTo(control.plan());
        assertThat(control.disclosures()).isEmpty();
        assertThat(attacked.disclosures()).containsExactly(new TrafficKeyDisclosure("disclosure-1", A, at(18), at(20)));
        assertThat(attacked.initialState().keys()).isEqualTo(original.initialState().keys());
    }

    @Test void alternativeTimerAppliesToEveryPolicyAndRetainsFixedRenewalTimer() {
        var base = PolicyExperimentFixtures.witness(Sequence.X, false).scenario();
        var snapshot = new SimulationEngine().start(base);
        for (var name : List.of("fixed", "percontact", "next", "multi")) {
            var normal = SecurityEvaluationSe2Main.policy(name, Duration.ofSeconds(90), DecisionTimeModel.V2_EVENT_BOUNDARIES);
            var alternate = SecurityEvaluationSe2Main.policy(name, Duration.ofSeconds(90), DecisionTimeModel.PREPARE_DURATION_WAIT);
            var observation = PlanningObservation.from(snapshot, normal.configuration().horizon());
            assertThat(alternate.configuration()).isEqualTo(normal.configuration());
            assertThat(alternate.additionalWakeUps(observation)).containsAll(normal.additionalWakeUps(observation))
                    .containsAll(DecisionTimeModel.PREPARE_DURATION_WAIT.additionalWakeUps(observation));
            if (name.equals("fixed")) assertThat(alternate.additionalWakeUps(observation)).contains(at(10));
        }
    }

    @Test void changingUndisclosedFutureAttackDoesNotChangeObservationsCandidatesOrTimers() {
        var physical = PolicyExperimentFixtures.witness(Sequence.X, false).scenario();
        var control = SecurityEvaluationSe2Main.incidentScenario(physical, A, null, "none");
        var early = SecurityEvaluationSe2Main.incidentScenario(physical, A, at(2), "detected");
        var late = SecurityEvaluationSe2Main.incidentScenario(physical, A, at(18), "undetected");
        for (var timing : DecisionTimeModel.values()) for (var policyName : SecurityEvaluationSe2Main.POLICIES) {
            var policy = SecurityEvaluationSe2Main.policy(policyName, Duration.ofSeconds(60), timing);
            var initial = PlanningObservation.from(new SimulationEngine().start(control), policy.configuration().horizon());
            for (var scenario : List.of(early, late)) {
                var other = PlanningObservation.from(new SimulationEngine().start(scenario), policy.configuration().horizon());
                assertThat(other).isEqualTo(initial);
                assertThat(policy.additionalWakeUps(other)).isEqualTo(policy.additionalWakeUps(initial));
                assertThat(new SecurityActionGenerator().candidates(timing.apply(other)))
                        .isEqualTo(new SecurityActionGenerator().candidates(timing.apply(initial)));
            }
        }
    }
}
