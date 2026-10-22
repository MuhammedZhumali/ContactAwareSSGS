package org.contactaware.security.simulation;

import org.contactaware.security.*;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.contactaware.security.simulation.HorizonWitness.*;

class SecurityPolicyControllerTest {
    @Test void timerDuringPendingOpenPreservesActualMissingAckAndChargesTheOperationOnce() {
        var engine = new SimulationEngine();
        var checkpoint = engine.start(scenario());
        OperationOutcomes missingAck = selected -> new OperationOutcomes.Outcome(null, Set.of(SecurityAction.OPEN));
        var timer = at(0).plusMillis(500);
        var timed = new SecurityPolicyController(serviceRule(List.of(timer))).run(checkpoint, missingAck);
        var untimed = new SecurityPolicyController(serviceRule(List.of())).run(checkpoint, missingAck);
        assertThat(timed.status()).isEqualTo(ControlledSimulationResult.Status.FINISHED);
        assertThat(timed.simulation()).isEqualTo(untimed.simulation());
        assertThat(timed.simulation().metrics().operationFailures()).containsEntry("MISSING_ACKNOWLEDGEMENT", 1L);
        assertThat(timed.simulation().metrics().controlCosts().totalCpu()).isEqualTo(Duration.ofMillis(60));
        assertThat(timed.simulation().uncertainContexts()).contains(A);
        var middle = timed.decisions().stream().filter(d -> d.at().equals(timer)).findFirst().orElseThrow();
        assertThat(middle.observation().ongoingOperations()).hasSize(1);
        assertThat(middle.observation().ongoingOperations().getFirst().expectedStepCompletion()).isEqualTo(at(1));
        assertThat(middle.observation().uncertainContexts()).isEmpty();
        assertThat(middle.executedAction().kind()).isEqualTo(PlanningAction.Kind.WAIT);
        var nominal = new SecurityPolicyController(serviceRule(List.of(timer))).run(checkpoint);
        assertThat(middle.observation()).isEqualTo(nominal.decisions().stream().filter(d -> d.at().equals(timer))
                .findFirst().orElseThrow().observation());
    }

    @Test void ruleTimerInsideAFramePreservesCommittedWorkAndNeverClaimsSearchCompleteness() {
        var timer = at(1).plusMillis(125);
        var result = new SecurityPolicyController(serviceRule(List.of(timer))).run(scenario());
        assertThat(result.status()).isEqualTo(ControlledSimulationResult.Status.FINISHED);
        assertThat(result.totalExploredNodes()).isZero();
        assertThat(result.totalSearchNanos()).isZero();
        assertThat(result.decisions()).allSatisfy(d -> {
            assertThat(d.source()).isEqualTo(ControlledSimulationResult.DecisionSource.RULE);
            assertThat(d.search()).isNull();
        });
        var midFrame = result.decisions().stream().filter(d -> d.at().equals(timer)).findFirst().orElseThrow();
        assertThat(midFrame.observation().inFlightFrame()).isNotNull();
        assertThat(midFrame.executedAction().kind()).isEqualTo(PlanningAction.Kind.WAIT);
        assertThat(result.simulation().metrics().usefulSecureTime()).isEqualTo(Duration.ofSeconds(3));
        assertThat(result.simulation().events()).filteredOn(e -> e.type() == SimulationEvent.Type.PAYLOAD_TRANSFERRED)
                .hasSize(12);
        var withoutTimer = new SecurityPolicyController(serviceRule(List.of())).run(scenario());
        assertThat(result.simulation()).isEqualTo(withoutTimer.simulation());
    }

    @Test void additionalTimersAreSortedUniqueAndCannotExtendTheVisibleHorizon() {
        var observation = PlanningObservation.from(new SimulationEngine().start(scenario()), 1);
        var supplemented = observation.withAdditionalWakeUps(List.of(at(2), at(2), at(-1), at(100), at(0)));
        assertThat(supplemented.visibleContacts()).isEqualTo(observation.visibleContacts());
        assertThat(supplemented.evaluationEnd()).isEqualTo(at(4));
        assertThat(supplemented.wakeUps()).contains(at(2)).doesNotContain(at(-1), at(100), at(0));
        assertThat(supplemented.wakeUps()).isSorted().doesNotHaveDuplicates();
    }

    @Test void ruleCannotBypassCommonAdmissibility() {
        var policy = new SecurityPolicy() {
            public PolicyConfiguration configuration() { return HorizonPolicy.next(); }
            public PolicySelection select(PlanningObservation o, List<PlanningAction> candidates) {
                return PolicySelection.rule(new PlanningAction(PlanningAction.Kind.INSTALL, o.at(), o.at().plusSeconds(4),
                        A, NEW_A, "C1"), "Invalid: keys were never prepared");
            }
        };
        assertThatIllegalArgumentException().isThrownBy(() -> new SecurityPolicyController(policy).run(scenario()))
                .withMessageContaining("inadmissible");
    }

    private SecurityPolicy serviceRule(List<Instant> timers) {
        return new SecurityPolicy() {
            public PolicyConfiguration configuration() { return HorizonPolicy.next(); }
            public List<Instant> additionalWakeUps(PlanningObservation o) { return timers; }
            public PolicySelection select(PlanningObservation o, List<PlanningAction> candidates) {
                var chosen = candidates.stream().filter(a -> a.kind() == PlanningAction.Kind.OPEN
                        || a.kind() == PlanningAction.Kind.CONTINUE).findFirst()
                        .orElseGet(() -> candidates.stream().filter(a -> a.kind() == PlanningAction.Kind.WAIT)
                                .min(Comparator.comparing(PlanningAction::until)).orElse(null));
                return PolicySelection.rule(chosen, "Test service rule");
            }
        };
    }

    private SimulationScenario scenario() {
        return new SimulationScenario("rule-timer", at(0), at(4), initialState(), CONSTRAINTS,
                SecurityCostProfile.syntheticWitness(), List.of(new SimulationContact(window("C1", A, 0, 4),
                SimulationContact.PayloadProfile.syntheticWitness())), List.of());
    }
}
