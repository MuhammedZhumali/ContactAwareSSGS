package org.contactaware.security.simulation;

import org.contactaware.security.*;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.contactaware.security.simulation.HorizonWitness.*;
import static org.contactaware.security.simulation.SimulationEvent.Type.*;

/** Hidden incident scheduling must not create extra optimization opportunities. */
class IncidentNoninterferenceTest {
    @Test void noTargetNoticeCoincidentWithAosPreservesBothHorizonPolicies() {
        assertNoTargetCoincidence(10, at(10), CONTACT_STARTED);
    }

    @Test void noTargetNoticeCoincidentWithPreparationCompletionPreservesBothHorizonPolicies() {
        assertNoTargetCoincidence(0, at(2), STEP_COMPLETED);
    }

    @Test void hiddenFutureIncidentCannotChangeCandidatesCanonicalPlanOrFiveScoresBeforeNotice() {
        for (var policy : List.of(HorizonPolicy.next(), HorizonPolicy.multi(2))) {
            var firstNotice = at(6).plusMillis(125);
            var first = run(policy, active(List.of(new TrafficKeyDisclosure("first", A,
                    at(2).plusMillis(125), firstNotice))));
            var changed = run(policy, active(List.of(new TrafficKeyDisclosure("changed", A,
                    at(3).plusMillis(125), at(8).plusMillis(125)))));
            var left = first.decisions().stream().filter(d -> d.at().isBefore(firstNotice)).toList();
            var right = changed.decisions().stream().filter(d -> d.at().isBefore(firstNotice)).toList();
            assertThat(left).hasSameSizeAs(right).isNotEmpty();
            for (int i = 0; i < left.size(); i++) {
                // resumedAt is a factual outcome, and the last interval may be cut by the notice;
                // the prior observation, candidates and selected full plan may not anticipate it.
                assertSameDecision(left.get(i), right.get(i));
                var branch = new BranchEngine().create(left.get(i).observation());
                assertThat(branch.snapshot().scenario().disclosures()).isEmpty();
                assertThat(branch.snapshot().queuedEvents()).noneMatch(e ->
                        e.kind() == ExecutionSnapshot.EventKind.DISCLOSURE
                                || e.kind() == ExecutionSnapshot.EventKind.REVOCATION_NOTICE);
            }
        }
    }

    private static void assertNoTargetCoincidence(long contactStart, Instant noticeAt, SimulationEvent.Type realEvent) {
        for (var policy : List.of(HorizonPolicy.next(), HorizonPolicy.multi(2))) {
            var nominalScenario = empty(contactStart, List.of());
            var nominal = run(policy, nominalScenario);
            var hidden = run(policy, empty(contactStart, List.of(new TrafficKeyDisclosure("no-target", A, at(0), noticeAt))));
            assertThat(nominal.status()).isEqualTo(ControlledSimulationResult.Status.FINISHED);
            assertThat(hidden.status()).isEqualTo(ControlledSimulationResult.Status.FINISHED);
            assertThat(hidden.snapshot().journal()).filteredOn(e -> e.type() == DISCLOSURE_NO_TARGET).hasSize(1);
            assertThat(hidden.snapshot().journal()).noneMatch(e -> e.type() == REVOCATION_RECEIVED_GROUND
                    || e.type() == REVOCATION_RECEIVED_SPACECRAFT);
            assertThat(hidden.snapshot().journal()).anyMatch(e -> e.type() == realEvent && e.at().equals(noticeAt));
            assertThat(hidden.decisions()).hasSameSizeAs(nominal.decisions());
            for (int i = 0; i < nominal.decisions().size(); i++) {
                assertSameDecision(nominal.decisions().get(i), hidden.decisions().get(i));
                assertThat(hidden.decisions().get(i).resumedAt()).isEqualTo(nominal.decisions().get(i).resumedAt());
            }
            assertThat(hidden.simulation().metrics()).isEqualTo(nominal.simulation().metrics());
            assertThat(hidden.snapshot().securityState()).isEqualTo(nominal.snapshot().securityState());
            // Default v2 journal representation remains unchanged; incident evidence is opt-in.
            assertThat(nominalScenario.disclosures()).isEmpty();
            assertThat(nominal.snapshot().journal()).allSatisfy(e -> assertThat(e.frameEvidence()).isNull());
        }
    }

    private static void assertSameDecision(ControlledSimulationResult.Decision left, ControlledSimulationResult.Decision right) {
        assertThat(left.at()).isEqualTo(right.at());
        assertThat(left.observation()).isEqualTo(right.observation());
        assertThat(new SecurityActionGenerator().candidates(left.observation()))
                .isEqualTo(new SecurityActionGenerator().candidates(right.observation()));
        assertThat(left.executedAction()).isEqualTo(right.executedAction());
        assertThat(left.search()).isNotNull();
        assertThat(right.search()).isNotNull();
        assertThat(left.search().status()).isEqualTo(HorizonPlanResult.Status.COMPLETE).isEqualTo(right.search().status());
        assertThat(left.search().score()).isEqualTo(right.search().score());
        assertThat(left.search().bestPlan()).isEqualTo(right.search().bestPlan());
        assertThat(left.search().exploredNodes()).isEqualTo(right.search().exploredNodes());
    }

    private static ControlledSimulationResult run(HorizonPolicy policy, SimulationScenario scenario) {
        return new RecedingHorizonController(policy,
                new ExactHorizonPlanner(new SearchLimits(100_000, Duration.ofSeconds(5)))).run(scenario);
    }

    private static SimulationScenario empty(long start, List<TrafficKeyDisclosure> incidents) {
        var constraints = new SecurityConstraints(Duration.ofSeconds(150), Duration.ofSeconds(400),
                Duration.ofSeconds(200), Set.of(A), Set.of());
        var state = new LifecycleTransitions(constraints).createContext(SecurityState.empty(SATELLITE, at(0)),
                A, "empty-up", "empty-down", at(0));
        return new SimulationScenario("coincident-no-target", at(0), at(start + 12), state, constraints,
                SecurityCostProfile.syntheticWitness(), List.of(new SimulationContact(window("C1", A, start, start + 12),
                new SimulationContact.PayloadProfile(400, 100, 100, 100, 100, 100))), List.of(), incidents);
    }

    private static SimulationScenario active(List<TrafficKeyDisclosure> incidents) {
        return new SimulationScenario("hidden-next-multi", at(0), at(12), initialState(), CONSTRAINTS,
                SecurityCostProfile.syntheticWitness(), List.of(new SimulationContact(window("C1", A, 0, 12),
                SimulationContact.PayloadProfile.syntheticWitness())), List.of(), incidents);
    }
}
