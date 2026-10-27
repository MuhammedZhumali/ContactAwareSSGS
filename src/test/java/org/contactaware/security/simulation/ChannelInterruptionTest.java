package org.contactaware.security.simulation;

import org.contactaware.contacts.JsonFiles;
import org.contactaware.security.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

import java.nio.file.Path;
import java.time.*;
import java.util.*;

import static org.assertj.core.api.Assertions.*;
import static org.contactaware.security.SecurityAction.*;
import static org.contactaware.security.simulation.HorizonWitness.*;
import static org.contactaware.security.simulation.SimulationEvent.Type.*;

class ChannelInterruptionTest {
    private final SimulationEngine engine = new SimulationEngine();
    @TempDir Path temporary;

    private static SimulationContact contact(String id, ContextId context, long from, long until) {
        return new SimulationContact(window(id, context, from, until), SimulationContact.PayloadProfile.syntheticWitness());
    }

    private static SimulationScenario scenario(List<SimulationContact> contacts, List<PlannedOperation> plan,
                                               List<ChannelInterruption> interruptions) {
        return new SimulationScenario("channel-test", at(-2), contacts.getLast().window().end(), initialState(), CONSTRAINTS,
                SecurityCostProfile.syntheticWitness(), contacts, plan, List.of(), interruptions);
    }

    private static SimulationScenario single(List<PlannedOperation> plan, ChannelInterruption... interruptions) {
        return scenario(List.of(contact("C1", A, 0, 20)), plan, List.of(interruptions));
    }

    private static PlannedOperation operationAt(String id, SecurityAction action, Instant time, KeyPairIds keys) {
        return new PlannedOperation(id, new ScheduledSecurityAction(action, time, A, keys, "Channel regression"),
                action.requiresContact() ? "C1" : null);
    }

    private ExecutionSnapshot atTime(SimulationScenario scenario, Instant time) {
        return engine.advance(engine.start(scenario), time);
    }

    @Test void interruptionBoundsAndContactIdentityAreValidatedWithoutChangingForecasts() {
        var contacts = List.of(contact("C1", A, 0, 20));
        assertThatIllegalArgumentException().isThrownBy(() -> new ChannelInterruption("C1", at(2), at(2)));
        assertThatIllegalArgumentException().isThrownBy(() -> scenario(contacts, List.of(),
                List.of(new ChannelInterruption("unknown", at(1), at(2)))));
        assertThatIllegalArgumentException().isThrownBy(() -> scenario(contacts, List.of(),
                List.of(new ChannelInterruption("C1", at(-1), at(2)))));
        assertThatIllegalArgumentException().isThrownBy(() -> scenario(contacts, List.of(),
                List.of(new ChannelInterruption("C1", at(19), at(21)))));
        assertThatIllegalArgumentException().isThrownBy(() -> scenario(contacts, List.of(), List.of(
                new ChannelInterruption("C1", at(1), at(4)), new ChannelInterruption("C1", at(3), at(5)))));
        var input = scenario(contacts, List.of(), List.of(new ChannelInterruption("C1", at(0), at(20))));
        assertThat(input.contacts()).isEqualTo(contacts);
        assertThatThrownBy(() -> input.channelInterruptions().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test void missingJsonListAndOldConstructorsPreserveEmptyInterruptionBehavior() throws Exception {
        var contacts = List.of(contact("C1", A, 0, 20));
        var plan = List.of(operation("open", OPEN, 0, A, null, "C1"));
        var old = new SimulationScenario("channel-test", at(-2), at(20), initialState(), CONSTRAINTS,
                SecurityCostProfile.syntheticWitness(), contacts, plan);
        var explicit = scenario(contacts, plan, List.of());
        assertThat(old).isEqualTo(explicit);
        var tree = JsonFiles.JSON.valueToTree(old);
        assertThat(tree.has("channelInterruptions")).isFalse();
        var restored = ExecutionCheckpointFiles.convert(tree, SimulationScenario.class);
        assertThat(restored.channelInterruptions()).isEmpty();
        assertThat(engine.run(restored)).isEqualTo(engine.run(explicit));
        assertThat(engine.run(restored).events()).noneMatch(e -> e.type() == CHANNEL_DOWN || e.type() == CHANNEL_UP);
    }

    @Test void futureOutageAndRestorationCannotLeakOrReplaceTheRemovedContactWithAFartherOne() throws Exception {
        var contacts = List.of(contact("C1", A, 0, 20), contact("C2", B, 30, 50), contact("C3", A, 80, 85));
        var nominal = scenario(contacts, List.of(), List.of());
        var first = scenario(contacts, List.of(), List.of(new ChannelInterruption("C1", at(5), at(8))));
        var second = scenario(contacts, List.of(), List.of(new ChannelInterruption("C1", at(5), at(11))));
        assertThat(JsonFiles.JSON.writeValueAsBytes(PlanningObservation.from(atTime(nominal, at(2)), 1)))
                .isEqualTo(JsonFiles.JSON.writeValueAsBytes(PlanningObservation.from(atTime(first, at(2)), 1)));
        var observed = PlanningObservation.from(atTime(first, at(6)), 1);
        assertThat(JsonFiles.JSON.writeValueAsBytes(observed))
                .isEqualTo(JsonFiles.JSON.writeValueAsBytes(PlanningObservation.from(atTime(second, at(6)), 1)));
        assertThat(observed.currentContact()).isNull();
        assertThat(observed.visibleContacts()).extracting(c -> c.window().id()).containsExactly("C2");
        assertThat(observed.evaluationEnd()).isEqualTo(at(50));
        assertThat(observed.wakeUps()).doesNotContain(at(8), at(11), at(78), at(80), at(85));
        var branch = engine.planningSnapshot(observed);
        assertThat(branch.scenario().channelInterruptions()).isEmpty();
        assertThat(branch.queuedEvents()).noneMatch(e -> e.kind() == ExecutionSnapshot.EventKind.CHANNEL_DOWN
                || e.kind() == ExecutionSnapshot.EventKind.CHANNEL_UP);
        assertThat(PlanningObservation.from(atTime(first, at(6)), 0).visibleContacts()).isEmpty();
        assertThat(PlanningObservation.from(atTime(first, at(8)), 1).currentContact()).isEqualTo(contacts.getFirst());
    }

    @Test void controlledWaitStopsAtActualChannelEventsWhoseTimesWereNotInTheObservation() {
        var input = single(List.of(), new ChannelInterruption("C1", at(5), at(8)));
        var snapshot = engine.controlledSnapshot(engine.start(input));
        snapshot = engine.controlledAdvance(snapshot, at(20), false);
        assertThat(snapshot.at()).isEqualTo(at(0));
        assertThat(PlanningObservation.from(snapshot, 1).wakeUps()).doesNotContain(at(5), at(8));
        snapshot = engine.controlledAdvance(snapshot, at(20), false);
        assertThat(snapshot.at()).isEqualTo(at(5));
        assertThat(snapshot.channelUnavailable("C1")).isTrue();
        snapshot = engine.controlledAdvance(snapshot, at(20), false);
        assertThat(snapshot.at()).isEqualTo(at(8));
        assertThat(snapshot.channelUnavailable("C1")).isFalse();
        assertThat(snapshot.securityState().context(A).status()).isEqualTo(AssociationStatus.KEYED);
    }

    @Test void abortedFrameConsumesOneNonceWithoutPayloadCreditAndReopeningUsesTheNextCounter() {
        var loss = at(1).plusMillis(125);
        var input = single(List.of(operation("open", OPEN, 0, A, null, "C1"),
                operation("reopen", OPEN, 3, A, null, "C1")), new ChannelInterruption("C1", loss, at(3)));
        var down = atTime(input, loss);
        var aborted = down.journal().stream().filter(e -> e.type() == PAYLOAD_ABORTED).findFirst().orElseThrow();
        assertThat(aborted.frameEvidence().emittedBytes()).isEqualTo(50);
        assertThat(aborted.frameEvidence().delivered()).isFalse();
        assertThat(aborted.uplinkFrames() + aborted.downlinkFrames()).isZero();
        assertThat(aborted.usefulTime()).isZero();
        assertThat(down.payloadProgress().get("C1").uplinkBytes()).isZero();
        assertThat(down.securityState().key(OLD_A.uplink()).nextNonceCounter()).isEqualTo(2);
        assertThat(down.securityState().context(A).uplink().nextSequenceNumber()).isEqualTo(2);
        assertThat(down.securityState().key(OLD_A.uplink()).lastSuccessfulContactEnd()).isEqualTo(at(-60));
        assertThat(down.securityState().context(A).status()).isEqualTo(AssociationStatus.KEYED);
        assertThat(down.inFlightFrame()).isNull();
        assertThat(down.queuedEvents()).noneMatch(e -> e.kind() == ExecutionSnapshot.EventKind.FRAME_COMPLETE);
        var resumed = engine.advance(down, at(4).plusMillis(250));
        assertThat(resumed.payloadProgress().get("C1").uplinkBytes()).isEqualTo(100);
        assertThat(resumed.securityState().key(OLD_A.uplink()).nextNonceCounter()).isEqualTo(3);
        assertThat(resumed.securityState().context(A).uplink().nextSequenceNumber()).isEqualTo(3);
        assertThat(resumed.journal()).filteredOn(e -> e.type() == PAYLOAD_ABORTED).hasSize(1);
        assertThat(resumed.journal()).filteredOn(e -> e.type() == PAYLOAD_TRANSFERRED).hasSize(1);
    }

    @Test void partialInstallRemainsUnconfirmedChargesExecutedWorkAndCanBeRetriedAfterUp() {
        var input = single(List.of(operation("prepare", PREPARE, -2, A, NEW_A, null),
                operation("install", INSTALL, 0, A, NEW_A, "C1"),
                operationAt("blocked", INSTALL, at(2).plusMillis(500), NEW_A),
                operation("retry", INSTALL, 3, A, NEW_A, "C1")),
                new ChannelInterruption("C1", at(2), at(3)));
        var down = atTime(input, at(2));
        assertThat(down.pendingOperations()).isEmpty();
        assertThat(down.uncertainContexts()).isEmpty();
        assertThat(down.securityState().key(NEW_A.uplink()).installedOnBothPeers()).isFalse();
        assertThat(down.journal()).filteredOn(e -> e.type() == STEP_INTERRUPTED).singleElement().satisfies(e -> {
            assertThat(e.failureCode()).isEqualTo("CHANNEL_DOWN");
            assertThat(e.usage()).isEqualTo(input.costs().cost(INSTALL).consumedUntil(Duration.ofSeconds(2)));
        });
        var retried = engine.advance(down, at(7));
        assertThat(retried.securityState().key(NEW_A.uplink()).installedOnBothPeers()).isTrue();
        assertThat(retried.journal()).anyMatch(e -> "blocked".equals(e.operationId())
                && e.type() == ACTION_FAILED && "CHANNEL_DOWN".equals(e.failureCode()));
        assertThat(retried.journal()).anyMatch(e -> "retry".equals(e.operationId()) && e.type() == ACTION_COMPLETED);
    }

    @ParameterizedTest
    @EnumSource(value = SecurityAction.class, names = {"SWITCH", "OPEN", "REUSE"})
    void uncertainSwitchOrOpeningIsNeverReconciledAutomaticallyOnUp(SecurityAction action) {
        var lifecycle = new LifecycleTransitions(CONSTRAINTS);
        var initial = lifecycle.prepareKeys(initialState(), A, NEW_A, at(-2));
        initial = lifecycle.confirmInstallation(initial, A, NEW_A, window("provision", A, -2, 0), at(-1));
        var keys = action == SWITCH ? NEW_A : null;
        var input = new SimulationScenario("uncertain", at(0), at(20), initial, CONSTRAINTS,
                SecurityCostProfile.syntheticWitness(), List.of(contact("C1", A, 0, 20)),
                List.of(operation("first", action, 0, A, keys, "C1"), operation("retry", action, 2, A, keys, "C1")),
                List.of(), List.of(new ChannelInterruption("C1", at(0).plusMillis(500), at(2))));
        var restored = atTime(input, at(3));
        assertThat(restored.uncertainContexts()).containsExactly(A);
        assertThat(restored.channelUnavailable("C1")).isFalse();
        assertThat(restored.journal()).anyMatch(e -> "retry".equals(e.operationId())
                && e.type() == ACTION_FAILED && "CONTEXT_UNCERTAIN".equals(e.failureCode()));
        assertThat(restored.journal()).noneMatch(e -> e.type() == PAYLOAD_TRANSFERRED);
        assertThat(restored.securityState().key(NEW_A.uplink()).activatedAt()).isNull();
    }

    @Test void groundOnlyPreparationContinuesDuringAChannelOutage() {
        var input = single(List.of(operation("prepare", PREPARE, 0, A, NEW_A, null)),
                new ChannelInterruption("C1", at(0), at(3)));
        var prepared = atTime(input, at(2));
        assertThat(prepared.channelUnavailable("C1")).isTrue();
        assertThat(prepared.securityState().key(NEW_A.uplink()).status()).isEqualTo(KeyStatus.PRE_ACTIVE);
        assertThat(prepared.createdKeys()).isEqualTo(2);
        assertThat(prepared.journal()).noneMatch(e -> e.type() == STEP_INTERRUPTED || e.type() == ACTION_FAILED);
    }

    @ParameterizedTest
    @CsvSource({"0,20,0", "0,3,16", "5,20,4"})
    void missedLateAndEarlyContactsPreserveTheirForecastAndOnlyAllowActualService(long from, long until, long useful) {
        var plan = new ArrayList<PlannedOperation>();
        plan.add(operation("open", OPEN, 0, A, null, "C1"));
        if (from == 0 && until < 20) plan.add(operation("reopen", OPEN, until, A, null, "C1"));
        var input = single(plan, new ChannelInterruption("C1", at(from), at(until)));
        var result = engine.run(input);
        assertThat(result.metrics().usefulSecureTime()).isEqualTo(Duration.ofSeconds(useful));
        assertThat(input.contacts().getFirst().window()).isEqualTo(window("C1", A, 0, 20));
        assertThat(result.finalState().context(A).status()).isEqualTo(AssociationStatus.KEYED);
        assertThat(result.events()).filteredOn(e -> e.type() == CHANNEL_DOWN).hasSize(1);
        assertThat(result.events()).filteredOn(e -> e.type() == CHANNEL_UP).hasSize(1);
    }

    @Test void lossAtAckPrecedesConfirmationButCompletePayloadAtLossIsDelivered() {
        var ack = single(List.of(operation("open", OPEN, 0, A, null, "C1")),
                new ChannelInterruption("C1", at(1), at(3)));
        var failed = atTime(ack, at(1));
        assertThat(failed.uncertainContexts()).containsExactly(A);
        assertThat(failed.journal()).noneMatch(e -> e.type() == STEP_COMPLETED && e.action() == OPEN);
        var boundary = single(List.of(operation("open", OPEN, 0, A, null, "C1")),
                new ChannelInterruption("C1", at(1).plusMillis(250), at(3)));
        var delivered = atTime(boundary, at(1).plusMillis(250));
        assertThat(delivered.payloadProgress().get("C1").uplinkBytes()).isEqualTo(100);
        assertThat(delivered.journal()).noneMatch(e -> e.type() == PAYLOAD_ABORTED);
        var types = delivered.journal().stream().filter(e -> e.at().equals(delivered.at())).map(SimulationEvent::type).toList();
        assertThat(types.indexOf(PAYLOAD_TRANSFERRED)).isLessThan(types.indexOf(CHANNEL_DOWN));
    }

    @Test void expiryPrecedesLossAndAdjacentOutagesDoNotCreateAnAvailableInstant() {
        var longContact = scenario(List.of(contact("C1", A, 0, 80)),
                List.of(operation("open", OPEN, 0, A, null, "C1")),
                List.of(new ChannelInterruption("C1", at(70), at(75))));
        var expired = atTime(longContact, at(70));
        assertThat(expired.securityState().key(OLD_A.uplink()).status()).isEqualTo(KeyStatus.DEACTIVATED);
        var types = expired.journal().stream().filter(e -> e.at().equals(at(70))).map(SimulationEvent::type).toList();
        assertThat(types.indexOf(KEY_EXPIRED)).isLessThan(types.indexOf(CHANNEL_DOWN));
        var adjacent = single(List.of(operation("blocked", OPEN, 3, A, null, "C1")),
                new ChannelInterruption("C1", at(2), at(3)), new ChannelInterruption("C1", at(3), at(4)));
        var atJoin = atTime(adjacent, at(3));
        assertThat(atJoin.channelUnavailable("C1")).isTrue();
        assertThat(atJoin.journal()).anyMatch(e -> e.type() == ACTION_FAILED && "CHANNEL_DOWN".equals(e.failureCode()));
    }

    @Test void checkpointRoundTripPreservesAvailabilityAbortedCountersAndTheRemainingPhysicalQueue() throws Exception {
        var input = single(List.of(operation("open", OPEN, 0, A, null, "C1"), operation("reopen", OPEN, 3, A, null, "C1")),
                new ChannelInterruption("C1", at(1).plusMillis(125), at(3)));
        var continuous = engine.run(input);
        int index = 0;
        for (var time : List.of(at(1).plusMillis(100), at(2), at(3).plusMillis(250))) {
            var snapshot = atTime(input, time);
            var path = temporary.resolve("channel-" + index++ + ".json");
            ExecutionCheckpointFiles.write(path, snapshot);
            var restored = ExecutionCheckpointFiles.read(path);
            assertThat(restored).isEqualTo(snapshot);
            assertThat(engine.resume(restored)).isEqualTo(continuous);
        }
    }

    @Test void rejectedCommittedDownlinkConsumesRetiredKeyCounterWithoutResurrectionOrDelivery() {
        var input = new SimulationScenario("retired-downlink", at(0), at(3), initialState(), CONSTRAINTS,
                SecurityCostProfile.syntheticWitness(), List.of(contact("C1", A, 0, 3)),
                List.of(operation("open", OPEN, 0, A, null, "C1")),
                List.of(new TrafficKeyDisclosure("disclosure", A, at(1).plusMillis(100), at(1).plusMillis(375))));
        var noticed = atTime(input, at(1).plusMillis(375));
        var prior = noticed.securityState().key(OLD_A.downlink());
        assertThat(prior.status()).isEqualTo(KeyStatus.DEACTIVATED);
        assertThat(noticed.inFlightFrame().deliveryRejectedAtGround()).isTrue();
        var finished = engine.advance(noticed, at(1).plusMillis(500));
        var key = finished.securityState().key(OLD_A.downlink());
        assertThat(key.nextNonceCounter()).isEqualTo(prior.nextNonceCounter() + 1);
        assertThat(key.status()).isEqualTo(KeyStatus.DEACTIVATED);
        assertThat(key.deactivatedAt()).isEqualTo(prior.deactivatedAt());
        assertThat(key.lastSuccessfulContactEnd()).isEqualTo(prior.lastSuccessfulContactEnd());
        assertThat(finished.securityState().context(A).status()).isEqualTo(AssociationStatus.UNKEYED);
        assertThat(finished.payloadProgress().get("C1").downlinkBytes()).isZero();
        assertThat(finished.journal()).filteredOn(e -> e.type() == PAYLOAD_ABORTED).singleElement()
                .satisfies(e -> assertThat(e.frameEvidence().emittedBytes()).isEqualTo(100));
    }

    @Test void conservativeAbortedNonceAccountingDoesNotInventSuccessfulUseForZeroBytePrefix() {
        var lifecycle = new LifecycleTransitions(CONSTRAINTS);
        var before = lifecycle.startContext(initialState(), A, window("C1", A, 0, 20), at(0));
        var after = lifecycle.recordAbortedTransmission(before, OLD_A.uplink(), at(0));
        assertThat(after.key(OLD_A.uplink()).nextNonceCounter()).isEqualTo(before.key(OLD_A.uplink()).nextNonceCounter() + 1);
        assertThat(after.context(A).uplinkFramesInSession()).isZero();
        assertThat(after.key(OLD_A.uplink()).lastSuccessfulContactEnd()).isEqualTo(at(-60));
        var stopped = lifecycle.stopContext(after, A, at(1));
        assertThat(stopped.key(OLD_A.uplink()).lastSuccessfulContactEnd()).isEqualTo(at(-60));
    }
}
