package org.contactaware.security.simulation;

import org.contactaware.contacts.JsonFiles;
import org.contactaware.security.*;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.contactaware.security.SecurityAction.*;
import static org.contactaware.security.simulation.HorizonWitness.*;

class PlanningObservationTest {
    private final SimulationEngine engine = new SimulationEngine();

    private SimulationContact contact(String id, ContextId context, long start, long end) {
        return new SimulationContact(window(id, context, start, end), SimulationContact.PayloadProfile.syntheticWitness());
    }

    private SimulationScenario input(String name, long end, List<SimulationContact> contacts,
                                     SecurityCostProfile costs, PlannedOperation... operations) {
        return new SimulationScenario(name, at(-2), at(end), initialState(), CONSTRAINTS, costs, contacts, List.of(operations));
    }

    private SimulationScenario commonPrefix(ContextId thirdStation, long thirdStart, long thirdEnd,
                                            PlannedOperation... extra) {
        var operations = new ArrayList<PlannedOperation>();
        operations.add(operation("warmup", PREPARE, -2, A, NEW_A, null));
        operations.add(operation("C1-reuse", REUSE, 0, A, null, "C1"));
        operations.addAll(List.of(extra));
        return input("private-scenario-" + thirdStation.groundStationId(), 100,
                List.of(contact("C1", A, 0, 20), contact("C2", B, 30, 50), contact("C3", thirdStation, thirdStart, thirdEnd)),
                SecurityCostProfile.syntheticWitness(), operations.toArray(PlannedOperation[]::new));
    }

    private ExecutionSnapshot snapshot(SimulationScenario scenario, Instant time) {
        return engine.advance(engine.start(scenario), time);
    }

    private List<String> ids(PlanningObservation observation) {
        return observation.visibleContacts().stream().map(c -> c.window().id()).toList();
    }

    @Test void hCountsAdditionalContactsInsideAContactAndNearestContactsInGaps() {
        var scenario = commonPrefix(A, 80, 85);
        assertThat(ids(PlanningObservation.from(snapshot(scenario, at(-2)), 1))).containsExactly("C1");
        assertThat(ids(PlanningObservation.from(snapshot(scenario, at(-2)), 2))).containsExactly("C1", "C2");
        assertThat(ids(PlanningObservation.from(snapshot(scenario, at(0)), 1))).containsExactly("C1", "C2");
        assertThat(ids(PlanningObservation.from(snapshot(scenario, at(0)), 2))).containsExactly("C1", "C2", "C3");
        var gap = PlanningObservation.from(snapshot(scenario, at(20)), 1);
        assertThat(gap.currentContact()).isNull();
        assertThat(ids(gap)).containsExactly("C2");
        assertThat(gap.evaluationEnd()).isEqualTo(at(50));
        assertThat(ids(PlanningObservation.from(snapshot(scenario, at(20)), 2))).containsExactly("C2", "C3");
        assertThat(ids(PlanningObservation.from(snapshot(scenario, at(30)), 1))).containsExactly("C2", "C3");
        assertThat(ids(PlanningObservation.from(snapshot(scenario, at(50)), 1))).containsExactly("C3");
        var afterLast = PlanningObservation.from(snapshot(scenario, at(85)), 1);
        assertThat(afterLast.currentContact()).isNull();
        assertThat(afterLast.visibleContacts()).isEmpty();
        assertThat(afterLast.evaluationEnd()).isEqualTo(at(85));
        assertThat(afterLast.wakeUps()).isEmpty();
    }

    @Test void invalidHorizonsAreRejectedAndLargeHorizonsDoNotOverflow() {
        var snapshot = snapshot(commonPrefix(A, 80, 85), at(0));
        assertThat(ids(PlanningObservation.from(snapshot, 0))).containsExactly("C1");
        assertThatThrownBy(() -> PlanningObservation.from(snapshot, -1)).isInstanceOf(IllegalArgumentException.class);
        assertThat(PlanningObservation.from(snapshot, Integer.MAX_VALUE).visibleContacts()).hasSize(3);
        var inGap = PlanningObservation.from(snapshot(commonPrefix(A, 80, 85), at(20)), 0);
        assertThat(inGap.visibleContacts()).isEmpty();
        assertThat(inGap.evaluationEnd()).isEqualTo(at(20));
        assertThat(inGap.wakeUps()).isEmpty();
    }

    @Test void nextHasByteIdenticalInputForXAndYWhileMultiCanSeeTheirDifference() throws Exception {
        var x = snapshot(commonPrefix(A, 80, 85), at(0));
        var y = snapshot(commonPrefix(B, 80, 85), at(0));
        assertThat(JsonFiles.JSON.writeValueAsBytes(PlanningObservation.from(x, 1)))
                .isEqualTo(JsonFiles.JSON.writeValueAsBytes(PlanningObservation.from(y, 1)));
        assertThat(PlanningObservation.from(x, 1).wakeUps()).isEqualTo(PlanningObservation.from(y, 1).wakeUps());
        assertThat(PlanningObservation.from(x, 2).visibleContacts()).isNotEqualTo(PlanningObservation.from(y, 2).visibleContacts());
        assertThat(PlanningObservation.from(x, 2).visibleContacts().getLast().window().groundStationId()).isEqualTo("A");
        assertThat(PlanningObservation.from(y, 2).visibleContacts().getLast().window().groundStationId()).isEqualTo("B");
    }

    @Test void hiddenContactTimesPlansAndNarrativeReasonsDoNotLeakBeforeTheyAffectExecution() throws Exception {
        var hidden = new PlannedOperation("private-later-operation", new ScheduledSecurityAction(REUSE, at(90), B, null,
                "Private expected outcome or station must not be observed"), "C3", at(90).plusMillis(500), Set.of(REUSE));
        var x = PlanningObservation.from(snapshot(commonPrefix(A, 80, 85), at(0)), 1);
        var y = PlanningObservation.from(snapshot(commonPrefix(B, 90, 95, hidden), at(0)), 1);
        assertThat(JsonFiles.JSON.writeValueAsBytes(x)).isEqualTo(JsonFiles.JSON.writeValueAsBytes(y));
        assertThat(JsonFiles.JSON.writeValueAsString(y)).doesNotContain("private-later-operation", "Private expected", "interruptAt", "missingAcknowledgements", "scenario", "journal");
        assertThat(y.wakeUps()).doesNotContain(at(78), at(80), at(85), at(88), at(90), at(95));
    }

    @Test void activeInterruptAndAckControlsDoNotLeakThroughReservationsOrReasons() throws Exception {
        var contacts = List.of(contact("C1", A, 0, 20), contact("C2", B, 30, 50));
        var plain = operation("rotate", ROTATE, 0, A, NEW_A, "C1");
        var controlled = new PlannedOperation(plain.id(),
                new ScheduledSecurityAction(ROTATE, at(0), A, NEW_A, "secret future failure"), "C1", at(3), Set.of(INSTALL));
        var warmup = operation("warmup", PREPARE, -2, A, NEW_A, null);
        var first = snapshot(input("normal", 60, contacts, SecurityCostProfile.syntheticWitness(), warmup, plain), at(2));
        var second = snapshot(input("controlled", 60, contacts, SecurityCostProfile.syntheticWitness(), warmup, controlled), at(2));
        assertThat(first.pendingOperations().getFirst().reservations()).isNotEqualTo(second.pendingOperations().getFirst().reservations());
        var observed = PlanningObservation.from(second, 1);
        assertThat(JsonFiles.JSON.writeValueAsBytes(PlanningObservation.from(first, 1)))
                .isEqualTo(JsonFiles.JSON.writeValueAsBytes(observed));
        assertThat(observed.ongoingOperations().getFirst().expectedStepCompletion()).isEqualTo(at(4));
        assertThat(observed.wakeUps()).contains(at(4)).doesNotContain(at(3));
        assertThat(observed.resourceReservations()).anyMatch(r -> r.from().isAfter(at(4)));
    }

    @Test void fullRuntimeEndDoesNotClipObservedNominalWork() throws Exception {
        var costs = SecurityCostProfile.syntheticWitness();
        var operations = new EnumMap<SecurityAction, SecurityCostProfile.OperationCost>(costs.operations());
        operations.put(PREPARE, new SecurityCostProfile.OperationCost(List.of(
                new SecurityCostProfile.Phase(SecurityCostProfile.Resource.GROUND_CPU, Duration.ofSeconds(200), 0))));
        var slow = new SecurityCostProfile("synthetic-long-preparation", costs.provenance(), "Synthetic observation boundary test", operations);
        var contacts = List.of(contact("C1", A, 0, 20), contact("C2", B, 30, 50));
        var prepare = operation("prepare", PREPARE, -2, A, NEW_A, null);
        var first = snapshot(input("short-runtime", 60, contacts, slow, prepare), at(0));
        var second = snapshot(input("long-runtime", 100, contacts, slow, prepare), at(0));
        assertThat(first.pendingOperations().getFirst().reservations()).isNotEqualTo(second.pendingOperations().getFirst().reservations());
        var observed = PlanningObservation.from(first, 1);
        assertThat(JsonFiles.JSON.writeValueAsBytes(observed)).isEqualTo(JsonFiles.JSON.writeValueAsBytes(PlanningObservation.from(second, 1)));
        assertThat(observed.resourceReservations()).containsExactly(new PlanningObservation.ResourceReservation("prepare", "ground:A", at(0), at(50)));
        assertThat(observed.ongoingOperations().getFirst().expectedStepCompletion()).isEqualTo(at(198));
        assertThat(observed.wakeUps()).doesNotContain(at(60), at(100), at(198));
    }

    @Test void wakeupsUseOnlyVisibleContactsJitPreparationAndKnownKeyDeadlines() {
        var scenario = commonPrefix(A, 80, 85);
        var gap = PlanningObservation.from(snapshot(scenario, at(20)), 1);
        assertThat(gap.wakeUps()).containsExactly(at(28), at(30), at(50));
        var current = PlanningObservation.from(snapshot(scenario, at(30)), 1);
        assertThat(current.wakeUps()).containsExactly(at(50), at(70), at(78), at(80), at(85));
        var started = PlanningObservation.from(snapshot(scenario, at(0)), 1);
        assertThat(started.wakeUps()).containsExactly(at(1), at(20), at(28), at(30), at(50));
    }

    @Test void partialPhaseResourceAndAccruedCostsArePreservedWithoutAssumingInstallation() {
        var contacts = List.of(contact("C1", A, 0, 20));
        var scenario = input("partial-install", 20, contacts, SecurityCostProfile.syntheticWitness(),
                operation("warmup", PREPARE, -2, A, NEW_A, null), operation("install", INSTALL, 0, A, NEW_A, "C1"));
        var snapshot = snapshot(scenario, at(2));
        var observed = PlanningObservation.from(snapshot, 1);
        assertThat(observed.resourceReservations()).containsExactly(
                new PlanningObservation.ResourceReservation("install", "radio", at(2), at(2).plusMillis(300)));
        assertThat(observed.recordedCosts()).isEqualTo(snapshot.recordedCosts());
        assertThat(observed.accruedCosts()).isEqualTo(snapshot.accruedCosts());
        assertThat(observed.accruedCosts().totalCpu()).isEqualTo(Duration.ofMillis(500));
        assertThat(observed.accruedCosts().managementBytes()).isEqualTo(680);
        assertThat(observed.createdKeys()).isEqualTo(2);
        assertThat(observed.securityState().key(NEW_A.uplink()).installedOnBothPeers()).isFalse();
        assertThat(observed.ongoingOperations().getFirst().stepKeys()).isEqualTo(NEW_A);
    }

    @Test void committedFramePayloadDirectionCountersAndUsefulProgressRemainVisible() {
        var scenario = commonPrefix(A, 80, 85);
        var snapshot = snapshot(scenario, at(3).plusMillis(125));
        var observed = PlanningObservation.from(snapshot, 1);
        assertThat(observed.inFlightFrame()).isEqualTo(snapshot.inFlightFrame());
        assertThat(observed.inFlightFrame().from()).isEqualTo(at(3));
        assertThat(observed.inFlightFrame().until()).isEqualTo(at(3).plusMillis(250));
        assertThat(observed.wakeUps()).contains(at(3).plusMillis(250));
        assertThat(observed.payloadProgress().get("C1"))
                .isEqualTo(new ExecutionSnapshot.PayloadProgress(400, 400, true));
        assertThat(observed.usefulSecureTime()).isEqualTo(Duration.ofSeconds(2));
        assertThat(observed.securityState().key(OLD_A.uplink()).nextNonceCounter()).isEqualTo(5);
        assertThat(observed.securityState()).isEqualTo(snapshot.securityState());
    }

    @Test void observedUncertaintyIsRetainedAfterTheFailureActuallyOccurs() {
        var reuse = operation("reuse", REUSE, 0, A, null, "C1");
        var failed = new PlannedOperation(reuse.id(), reuse.intent(), "C1", null, Set.of(REUSE));
        var scenario = input("uncertain", 20, List.of(contact("C1", A, 0, 20)), SecurityCostProfile.syntheticWitness(), failed);
        var observed = PlanningObservation.from(snapshot(scenario, at(1)), 1);
        assertThat(observed.uncertainContexts()).containsExactly(A);
        assertThat(observed.ongoingOperations()).isEmpty();
        assertThat(observed.accruedCosts().totalCpu()).isEqualTo(Duration.ofMillis(60));
    }

    @Test void everyCollectionIsImmutableAndObservationDoesNotRollForwardWithRuntime() {
        var snapshot = snapshot(commonPrefix(A, 80, 85), at(0));
        var observed = PlanningObservation.from(snapshot, 1);
        assertThatThrownBy(() -> observed.visibleContacts().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> observed.ongoingOperations().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> observed.ongoingOperations().getFirst().steps().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> observed.resourceReservations().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> observed.payloadProgress().put("fake", new ExecutionSnapshot.PayloadProgress(1, 1, true)))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> observed.uncertainContexts().add(A)).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> observed.wakeUps().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> observed.securityState().keys().clear()).isInstanceOf(UnsupportedOperationException.class);
        engine.advance(snapshot, at(20));
        assertThat(observed.at()).isEqualTo(at(0));
        assertThat(observed.currentContact().window().id()).isEqualTo("C1");
        assertThat(observed.ongoingOperations()).hasSize(1);
        assertThat(observed.usefulSecureTime()).isZero();
    }
}
