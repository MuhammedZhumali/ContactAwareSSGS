package org.contactaware.security.simulation;

import org.contactaware.security.*;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.contactaware.security.simulation.BaselinePolicyTestSupport.*;
import static org.contactaware.security.simulation.HorizonWitness.*;

class PerContactPolicyTest {
    @Test void zeroTrafficHistoricalActivePairIsNotFreshForANewContact() {
        var constraints = constraints(150);
        var state = historicalUnused(constraints);
        assertThat(state.key(OLD_A.uplink()).nextNonceCounter()).isZero();
        assertThat(state.key(OLD_A.uplink()).lastSuccessfulContactEnd()).isNull();
        var source = scenario("unused-but-historical", 10, 18, state, constraints, List.of(contact("C1", 10, 18)), List.of());
        var observation = PlanningObservation.from(new SimulationEngine().start(source), 1);
        assertThat(new SecurityActionGenerator().candidates(observation)).anyMatch(a -> a.kind() == PlanningAction.Kind.OPEN);
        var policy = new PerContactPolicy(PerContactPolicy.AdvancePreparation.CONTACT_ONLY);
        assertThat(policy.ownsCurrentPair(observation, observation.currentContact())).isFalse();
        assertThat(select(policy, observation).action().kind()).isEqualTo(PlanningAction.Kind.PREPARE);
        assertThat(select(policy, observation).search()).isNull();
    }

    @Test void currentContactOwnershipSurvivesCheckpointAndDoesNotRequirePayloadSuccess() {
        var constraints = constraints(150);
        var source = scenario("current-activation-without-payload", 10, 38, historicalUnused(constraints), constraints,
                List.of(contact("C1", 10, 20), contact("C2", 30, 38)), List.of(
                        operation("prepare", SecurityAction.PREPARE, 10, A, NEW_A, null),
                        operation("install", SecurityAction.INSTALL, 12, A, NEW_A, "C1"),
                        operation("switch", SecurityAction.SWITCH, 13, A, NEW_A, "C1")));
        var simulation = new SimulationEngine();
        var snapshot = simulation.advance(simulation.start(source), at(14));
        var policy = new PerContactPolicy(PerContactPolicy.AdvancePreparation.CONTACT_ONLY);
        var current = PlanningObservation.from(snapshot, 1);
        assertThat(snapshot.securityState().key(NEW_A.uplink()).nextNonceCounter()).isZero();
        assertThat(policy.ownsCurrentPair(current, current.currentContact())).isTrue();
        assertThat(select(policy, current).action().kind()).isEqualTo(PlanningAction.Kind.OPEN);
        var later = PlanningObservation.from(simulation.advance(snapshot, at(30)), 1);
        assertThat(policy.ownsCurrentPair(later, later.currentContact())).isFalse();
        assertThat(select(policy, later).action().kind()).isEqualTo(PlanningAction.Kind.PREPARE);
    }

    @Test void eachContactGetsOneDifferentPairInsteadOfRenewingAfterEveryFrame() {
        var constraints = constraints(150);
        var source = scenario("two-per-contact-pairs", 10, 38, historicalUnused(constraints), constraints,
                List.of(contact("C1", 10, 18), contact("C2", 30, 38)), List.of());
        var result = new SecurityPolicyController(new PerContactPolicy(PerContactPolicy.AdvancePreparation.CONTACT_ONLY)).run(source);
        assertThat(result.status()).isEqualTo(ControlledSimulationResult.Status.FINISHED);
        assertThat(result.simulation().metrics().servedContacts()).isEqualTo(2);
        assertThat(result.simulation().metrics().usefulSecureTime()).isEqualTo(Duration.ofSeconds(6));
        assertThat(result.simulation().metrics().createdKeys()).isEqualTo(4);
        assertThat(result.simulation().metrics().startedOperations().get(SecurityAction.PREPARE)).isEqualTo(2);
        assertThat(result.simulation().metrics().completedOperations().get(SecurityAction.SWITCH)).isEqualTo(2);
        var switchedKeys = result.decisions().stream().map(ControlledSimulationResult.Decision::executedAction)
                .filter(Objects::nonNull).filter(a -> a.kind() == PlanningAction.Kind.SWITCH).map(PlanningAction::keys).toList();
        assertThat(switchedKeys).hasSize(2).doesNotHaveDuplicates();
        assertThat(result.totalExploredNodes()).isZero();
    }

    @Test void justInTimePreparationIsPaidButPreservesTwoMoreContactSeconds() {
        var constraints = constraints(150);
        var source = scenario("preparation-timing", -2, 8, empty(constraints, -2), constraints,
                List.of(contact("C1", 0, 8)), List.of());
        var contactOnly = new SecurityPolicyController(new PerContactPolicy(PerContactPolicy.AdvancePreparation.CONTACT_ONLY)).run(source);
        var jit = new SecurityPolicyController(new PerContactPolicy(PerContactPolicy.AdvancePreparation.JUST_IN_TIME)).run(source);
        assertThat(contactOnly.status()).isEqualTo(ControlledSimulationResult.Status.FINISHED);
        assertThat(jit.status()).isEqualTo(ControlledSimulationResult.Status.FINISHED);
        assertThat(contactOnly.simulation().metrics().usefulSecureTime()).isEqualTo(Duration.ofSeconds(3));
        assertThat(jit.simulation().metrics().usefulSecureTime()).isEqualTo(Duration.ofSeconds(5));
        assertThat(contactOnly.simulation().metrics().controlCosts()).isEqualTo(jit.simulation().metrics().controlCosts());
        assertThat(jit.simulation().metrics().controlCosts().totalCpu()).isEqualTo(Duration.ofSeconds(5));
        assertThat(jit.simulation().metrics().createdKeys()).isEqualTo(2);
        assertThat(jit.decisions().stream().map(ControlledSimulationResult.Decision::executedAction).filter(Objects::nonNull)
                .filter(a -> a.kind() == PlanningAction.Kind.PREPARE).findFirst().orElseThrow().at()).isEqualTo(at(-2));
    }

    @Test void unusedPreparedInventoryCanSurviveAMissedContactWithoutBeingStranded() {
        var constraints = constraints(150);
        var state = new LifecycleTransitions(constraints).prepareKeys(empty(constraints, -2), A, NEW_A, at(-1));
        // C1 can install but cannot switch/open. That still-unactivated pair remains eligible in C2.
        var source = scenario("unactivated-spare-survives", 0, 12, state, constraints,
                List.of(contact("C1", 0, 2), contact("C2", 5, 12)), List.of());
        var result = new SecurityPolicyController(new PerContactPolicy(PerContactPolicy.AdvancePreparation.CONTACT_ONLY)).run(source);
        assertThat(result.status()).isEqualTo(ControlledSimulationResult.Status.FINISHED);
        assertThat(result.simulation().metrics().servedContacts()).isEqualTo(1);
        assertThat(result.simulation().metrics().createdKeys()).isZero();
        var switched = result.decisions().stream().map(ControlledSimulationResult.Decision::executedAction)
                .filter(Objects::nonNull).filter(a -> a.kind() == PlanningAction.Kind.SWITCH).toList();
        assertThat(switched).hasSize(1);
        assertThat(switched.getFirst().contactId()).isEqualTo("C2");
        assertThat(switched.getFirst().keys()).isEqualTo(NEW_A);
    }
}
