package org.contactaware.security.simulation;

import org.contactaware.security.*;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.contactaware.security.simulation.BaselinePolicyTestSupport.*;
import static org.contactaware.security.simulation.HorizonWitness.*;

class FixedPeriodPolicyTest {
    @Test void periodIsPositiveAndConfigurationDoesNotPretendToBeSearch() {
        assertThatIllegalArgumentException().isThrownBy(() -> new FixedPeriodPolicy(Duration.ZERO));
        assertThatIllegalArgumentException().isThrownBy(() -> new FixedPeriodPolicy(Duration.ofSeconds(-1)));
        var policy = new FixedPeriodPolicy(Duration.ofSeconds(30));
        assertThat(policy.configuration().name()).isEqualTo("FIXED_PERIOD");
        assertThat(policy.configuration().horizon()).isEqualTo(1);
        assertThat(policy.configuration().period()).isEqualTo(Duration.ofSeconds(30));
        var observed = HorizonPlannerMain.observation(Sequence.X, 1);
        assertThat(select(policy, observed).search()).isNull();
    }

    @Test void historicalActivationRatherThanRunStartInitializesTheTimer() {
        var policy = new FixedPeriodPolicy(Duration.ofSeconds(60));
        var observation = HorizonPlannerMain.observation(Sequence.X, 1);
        assertThat(policy.lastSuccessfulActivationAt(observation, A)).contains(at(-80));
        assertThat(policy.lastSuccessfulActivationAt(observation, B)).contains(at(-30));
        assertThat(policy.renewalDue(observation, A)).isTrue();
        assertThat(policy.renewalDue(observation, B)).isFalse();
        assertThat(policy.additionalWakeUps(observation)).containsExactly(at(30));
        assertThat(select(policy, observation).action().kind()).isEqualTo(PlanningAction.Kind.INSTALL);
    }

    @Test void lateConfirmedSwitchAt75MovesTheNextDueTo105() {
        var policy = new FixedPeriodPolicy(Duration.ofSeconds(30));
        var simulation = new SimulationEngine();
        var source = lateSwitch(false);
        var snapshot = simulation.advance(simulation.start(source), at(75));
        var observation = PlanningObservation.from(snapshot, 1);
        assertThat(policy.lastSuccessfulActivationAt(observation, A)).contains(at(75));
        assertThat(policy.renewalDue(observation, A)).isFalse();
        assertThat(policy.additionalWakeUps(observation)).containsExactly(at(105));
        assertThat(policy.renewalDue(PlanningObservation.from(simulation.advance(snapshot, at(104)), 1), A)).isFalse();
        assertThat(policy.renewalDue(PlanningObservation.from(simulation.advance(snapshot, at(105)), 1), A)).isTrue();
    }

    @Test void preparationInstallationAndFailedSwitchDoNotResetTheAnchor() {
        var policy = new FixedPeriodPolicy(Duration.ofSeconds(30));
        var simulation = new SimulationEngine();
        var initial = simulation.start(lateSwitch(true));
        for (long time : List.of(62L, 74L, 75L)) {
            var observation = PlanningObservation.from(simulation.advance(initial, at(time)), 1);
            assertThat(policy.lastSuccessfulActivationAt(observation, A)).contains(at(0));
            assertThat(policy.renewalDue(observation, A)).isTrue();
        }
        assertThat(simulation.advance(initial, at(75)).uncertainContexts()).contains(A);
    }

    @Test void expiryRetainsTheAnchorAndDoesNotCreateAnEarlyEmergencyRenewal() {
        var constraints = constraints(10);
        var source = scenario("strict-period-after-expiry", 0, 80, historicalUnused(constraints), constraints,
                List.of(contact("late", 70, 80)), List.of());
        var simulation = new SimulationEngine();
        var snapshot = simulation.advance(simulation.start(source), at(11));
        var observation = PlanningObservation.from(snapshot, 1);
        var policy = new FixedPeriodPolicy(Duration.ofSeconds(60));
        assertThat(snapshot.securityState().key(OLD_A.uplink()).status()).isEqualTo(KeyStatus.DEACTIVATED);
        assertThat(policy.lastSuccessfulActivationAt(observation, A)).contains(at(0));
        assertThat(policy.renewalDue(observation, A)).isFalse();
        assertThat(select(policy, observation).action().kind()).isEqualTo(PlanningAction.Kind.WAIT);
        assertThat(policy.additionalWakeUps(observation)).containsExactly(at(60));
        var due = PlanningObservation.from(simulation.advance(snapshot, at(60)), 1);
        assertThat(select(policy, due).action().kind()).isEqualTo(PlanningAction.Kind.PREPARE);
    }

    @Test void missedPeriodsCoalesceAndOnlyActualSwitchStartsTheNextPeriod() {
        var constraints = constraints(10);
        var source = scenario("many-overdue-intervals", 69, 80, historicalUnused(constraints), constraints,
                List.of(contact("late", 70, 80)), List.of());
        var policy = new FixedPeriodPolicy(Duration.ofSeconds(10));
        var result = new SecurityPolicyController(policy).run(source);
        assertThat(result.status()).isEqualTo(ControlledSimulationResult.Status.FINISHED);
        assertThat(result.simulation().metrics().startedOperations().get(SecurityAction.PREPARE)).isEqualTo(1);
        assertThat(result.simulation().metrics().completedOperations().get(SecurityAction.SWITCH)).isEqualTo(1);
        assertThat(result.simulation().metrics().createdKeys()).isEqualTo(2);
        var finalObservation = PlanningObservation.from(result.snapshot(), 1);
        assertThat(policy.lastSuccessfulActivationAt(finalObservation, A)).contains(at(73));
        assertThat(policy.renewalDue(finalObservation, A)).isFalse();
        assertThat(result.decisions()).allSatisfy(d -> {
            assertThat(d.source()).isEqualTo(ControlledSimulationResult.DecisionSource.RULE);
            assertThat(d.search()).isNull();
        });
        assertThat(result.totalExploredNodes()).isZero();
    }

    @Test void openAndPayloadDoNotResetPeriodAndOldValidMaterialCanServeBeforeItIsDue() {
        var constraints = constraints(150);
        var source = scenario("period-old-material-service", 10, 14, historicalUnused(constraints), constraints,
                List.of(contact("service", 10, 14)), List.of());
        var policy = new FixedPeriodPolicy(Duration.ofSeconds(60));
        var result = new SecurityPolicyController(policy).run(source);
        assertThat(result.status()).isEqualTo(ControlledSimulationResult.Status.FINISHED);
        assertThat(result.simulation().metrics().servedContacts()).isEqualTo(1);
        assertThat(result.simulation().metrics().createdKeys()).isZero();
        assertThat(policy.lastSuccessfulActivationAt(PlanningObservation.from(result.snapshot(), 1), A)).contains(at(0));
    }

    private SimulationScenario lateSwitch(boolean missingAck) {
        var constraints = constraints(150);
        var switching = operation("late-switch", SecurityAction.SWITCH, 74, A, NEW_A, "C1");
        if (missingAck) switching = new PlannedOperation(switching.id(), switching.intent(), switching.contactId(), null, Set.of(SecurityAction.SWITCH));
        return scenario("late-switch-anchor", 0, 110, historicalUnused(constraints), constraints,
                List.of(contact("C1", 70, 90), contact("C2", 100, 110)), List.of(
                        operation("prepare", SecurityAction.PREPARE, 60, A, NEW_A, null),
                        operation("install", SecurityAction.INSTALL, 73, A, NEW_A, "C1"), switching));
    }
}
