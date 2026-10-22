package org.contactaware.security.simulation;

import org.contactaware.security.*;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.contactaware.security.SecurityAction.*;
import static org.contactaware.security.simulation.HorizonWitness.*;
import static org.contactaware.security.simulation.SimulationEvent.Type.*;

class SimulationEngineTest {
    final SimulationEngine engine = new SimulationEngine();
    final SimulationContact.PayloadProfile payload = SimulationContact.PayloadProfile.syntheticWitness();

    SimulationContact contact(String id, ContextId context, long start, long end) {
        return new SimulationContact(window(id, context, start, end), payload);
    }

    SimulationScenario input(long end, List<SimulationContact> contacts, PlannedOperation... operations) {
        return new SimulationScenario("boundary-test", at(-2), at(end), initialState(), CONSTRAINTS,
                SecurityCostProfile.syntheticWitness(), contacts, List.of(operations));
    }

    PlannedOperation warmup() { return operation("warmup", PREPARE, -2, A, NEW_A, null); }

    PlannedOperation controlled(PlannedOperation operation, Instant interruption, SecurityAction... noAck) {
        return new PlannedOperation(operation.id(), operation.intent(), operation.contactId(), interruption, Set.of(noAck));
    }

    @Test void keyExpiryInsideContactClosesAfterLastAdmissibleFrameAndBeforeFurtherUse() {
        var result = engine.run(input(100, List.of(contact("A", A, 60, 100)), operation("open", REUSE, 60, A, null, "A")));
        assertThat(result.metrics().usefulSecureTime()).isEqualTo(Duration.ofSeconds(9));
        assertThat(result.finalState().context(A).status()).isEqualTo(AssociationStatus.UNKEYED);
        var key = result.finalState().key(OLD_A.uplink());
        assertThat(key.status()).isEqualTo(KeyStatus.DEACTIVATED);
        assertThat(key.deactivatedAt()).isEqualTo(at(70));
        assertThat(key.lastSuccessfulContactEnd()).isEqualTo(at(70));
        assertThat(key.nextNonceCounter()).isEqualTo(19);
        var finalFrame = result.events().stream().filter(e -> e.type() == PAYLOAD_TRANSFERRED).reduce((a,b) -> b).orElseThrow();
        var expiry = result.events().stream().filter(e -> e.type() == KEY_EXPIRED).findFirst().orElseThrow();
        assertThat(finalFrame.at()).isEqualTo(at(70));
        assertThat(finalFrame.sequence()).isLessThan(expiry.sequence());
    }

    @Test void partialInstallationAtContactEndMatchesHandCalculatedPhaseProgress() {
        var result = engine.run(input(5, List.of(contact("A", A, 0, 2)), warmup(),
                operation("install", INSTALL, 0, A, NEW_A, "A")));
        var partial = result.events().stream().filter(e -> e.type() == STEP_INTERRUPTED).findFirst().orElseThrow();
        assertThat(partial.failureCode()).isEqualTo("CONTACT_ENDED");
        assertThat(partial.usage()).isEqualTo(new ResourceUsage(Duration.ofSeconds(2), Duration.ofMillis(200),
                Duration.ofMillis(100), Duration.ofMillis(1700), 600, 80));
        assertThat(result.finalState().key(NEW_A.uplink()).installedOnBothPeers()).isFalse();
        assertThat(result.metrics().controlCosts().totalCpu()).isEqualTo(Duration.ofMillis(500));
        assertThat(result.metrics().completedOperations()).doesNotContainKey(INSTALL);
    }

    @Test void completionExactlyAtContactEndPaysFullCostButDoesNotConfirm() {
        var result = engine.run(input(5, List.of(contact("A", A, 0, 4)), warmup(),
                operation("install", INSTALL, 0, A, NEW_A, "A")));
        assertThat(result.finalState().key(NEW_A.uplink()).installedOnBothPeers()).isFalse();
        assertThat(result.metrics().controlCosts().managementBytes()).isEqualTo(800);
        assertThat(result.metrics().operationFailures()).containsEntry("CONTACT_ENDED", 1L);
    }

    @Test void explicitInterruptionAtCompletionWinsOverAcknowledgement() {
        var install = controlled(operation("install", INSTALL, 0, A, NEW_A, "A"), at(4));
        var result = engine.run(input(20, List.of(contact("A", A, 0, 20)), warmup(), install));
        assertThat(result.finalState().key(NEW_A.uplink()).installedOnBothPeers()).isFalse();
        assertThat(result.metrics().controlCosts().managementBytes()).isEqualTo(800);
        assertThat(result.metrics().operationFailures()).containsEntry("INTERRUPTED", 1L);
    }

    @Test void missingInstallationAckCannotActivateButOldContextCanStillBeUsed() {
        var install = controlled(operation("install", INSTALL, 0, A, NEW_A, "A"), null, INSTALL);
        var result = engine.run(input(20, List.of(contact("A", A, 0, 20)), warmup(), install,
                operation("switch", SWITCH, 4, A, NEW_A, "A"), operation("reuse", REUSE, 5, A, null, "A")));
        assertThat(result.metrics().operationFailures()).containsEntry("MISSING_ACKNOWLEDGEMENT", 1L).containsEntry("KEY_NOT_INSTALLED", 1L);
        assertThat(result.finalState().key(NEW_A.uplink()).installedOnBothPeers()).isFalse();
        assertThat(result.finalState().context(A).boundKeys()).isEqualTo(OLD_A);
        assertThat(result.metrics().usefulSecureTime()).isEqualTo(Duration.ofSeconds(14));
        assertThat(result.uncertainContexts()).isEmpty();
    }

    @Test void interruptedSwitchRetainsInstallationButBlocksUnsafeFallback() {
        var rotate = controlled(operation("rotate", ROTATE, 0, A, NEW_A, "A"), at(4).plusMillis(500));
        var result = engine.run(input(20, List.of(contact("A", A, 0, 20)), warmup(), rotate,
                operation("fallback", REUSE, 6, A, null, "A")));
        assertThat(result.finalState().key(NEW_A.uplink()).installedOnBothPeers()).isTrue();
        assertThat(result.finalState().key(NEW_A.uplink()).status()).isEqualTo(KeyStatus.PRE_ACTIVE);
        assertThat(result.finalState().context(A).boundKeys()).isEqualTo(OLD_A);
        assertThat(result.uncertainContexts()).containsExactly(A);
        assertThat(result.metrics().operationFailures()).containsEntry("INTERRUPTED", 1L).containsEntry("CONTEXT_UNCERTAIN", 1L);
        assertThat(result.metrics().usefulSecureTime()).isZero();
        assertThat(result.metrics().completedOperations()).containsEntry(INSTALL, 1L).doesNotContainKeys(SWITCH, OPEN);
    }

    @Test void missingSwitchAckChargesFullWorkWithoutActivatingOrOpeningCandidate() {
        var rotate = controlled(operation("rotate", ROTATE, 0, A, NEW_A, "A"), null, SWITCH);
        var result = engine.run(input(20, List.of(contact("A", A, 0, 20)), warmup(), rotate));
        assertThat(result.metrics().controlCosts().totalCpu()).isEqualTo(Duration.ofMillis(650));
        assertThat(result.metrics().controlCosts().managementBytes()).isEqualTo(1000);
        assertThat(result.finalState().key(NEW_A.uplink()).activatedAt()).isNull();
        assertThat(result.metrics().usefulSecureTime()).isZero();
        assertThat(result.uncertainContexts()).containsExactly(A);
    }

    @Test void missingOpenAckCannotCreateUsefulTimeOrIncrementNonces() {
        var reuse = controlled(operation("reuse", REUSE, 0, A, null, "A"), null, REUSE);
        var result = engine.run(input(20, List.of(contact("A", A, 0, 20)), reuse));
        assertThat(result.metrics().controlCosts().totalCpu()).isEqualTo(Duration.ofMillis(60));
        assertThat(result.metrics().usefulSecureTime()).isZero();
        assertThat(result.finalState().key(OLD_A.uplink()).nextNonceCounter()).isEqualTo(1);
        assertThat(result.finalState().context(A).status()).isEqualTo(AssociationStatus.KEYED);
    }

    @Test void incompleteOfflinePreparationPaysConsumedWorkButCreatesNoKeys() {
        var result = engine.run(input(-1, List.of(), warmup()));
        assertThat(result.metrics().createdKeys()).isZero();
        assertThat(result.finalState().keys()).doesNotContainKey(NEW_A.uplink());
        assertThat(result.metrics().controlCosts()).isEqualTo(new ResourceUsage(Duration.ofSeconds(1), Duration.ZERO,
                Duration.ofMillis(200), Duration.ZERO, 0, 0));
        assertThat(result.metrics().operationFailures()).containsEntry("SIMULATION_ENDED", 1L);
    }

    @Test void expiredReuseGapIsRejectedWithoutExecutionCosts() {
        var scenario = input(20, List.of(contact("A", A, 0, 20)), operation("reuse", REUSE, 0, A, null, "A"));
        var tight = new SecurityConstraints(CONSTRAINTS.cryptoperiod(), CONSTRAINTS.maxMaterialAge(), Duration.ofSeconds(50),
                CONSTRAINTS.authorizedContexts(), Set.of());
        var result = engine.run(new SimulationScenario(scenario.name(), scenario.start(), scenario.end(), scenario.initialState(),
                tight, scenario.costs(), scenario.contacts(), scenario.plan()));
        assertThat(result.metrics().operationFailures()).containsEntry("REUSE_GAP_EXCEEDED", 1L);
        assertThat(result.metrics().controlCosts()).isEqualTo(ResourceUsage.ZERO);
        assertThat(result.metrics().usefulSecureTime()).isZero();
        assertThat(result.finalState()).isEqualTo(initialState());
    }

    @Test void stationBDoesNotInstallThePreparedStationACandidate() {
        var result = engine.run(input(50, List.of(contact("B", B, 30, 50)), warmup(),
                operation("wrong-route", INSTALL, 30, A, NEW_A, "B")));
        assertThat(result.metrics().operationFailures()).containsEntry("CONTACT_MISMATCH", 1L);
        assertThat(result.finalState().key(NEW_A.uplink()).installedOnBothPeers()).isFalse();
        assertThat(result.metrics().controlCosts().managementBytes()).isZero();
    }

    @Test void independentGroundPreparationDoesNotFragmentOrDelayPayloadFrames() {
        var prepareB = new PlannedOperation("prepare-B", new ScheduledSecurityAction(PREPARE, at(1).plusMillis(100), B,
                new KeyPairIds("B-next-up", "B-next-down"), "Independent ground work"), null);
        var result = engine.run(input(20, List.of(contact("A", A, 0, 20)), operation("reuse", REUSE, 0, A, null, "A"), prepareB));
        assertThat(result.metrics().usefulSecureTime()).isEqualTo(Duration.ofSeconds(19));
        assertThat(result.metrics().operationFailures()).isEmpty();
        assertThat(result.finalState().key(OLD_A.uplink()).nextNonceCounter()).isEqualTo(39);
        assertThat(result.metrics().createdKeys()).isEqualTo(2);
        assertThat(result.metrics().controlCosts().elapsed()).isEqualTo(Duration.ofSeconds(3));
        assertThat(result.metrics().controlCosts().totalCpu()).isEqualTo(Duration.ofMillis(260));
    }

    @Test void overlappingUseOfSameGroundCpuIsRejectedInStableIdOrder() {
        var result = engine.run(input(20, List.of(contact("A", A, 0, 20)),
                operation("01-reuse", REUSE, 0, A, null, "A"), operation("02-prepare", PREPARE, 0, A, NEW_A, null)));
        assertThat(result.metrics().operationFailures()).containsEntry("RESOURCE_BUSY", 1L);
        assertThat(result.metrics().createdKeys()).isZero();
        assertThat(result.metrics().usefulSecureTime()).isEqualTo(Duration.ofSeconds(19));
    }

    @Test void finiteOfferedPayloadDoesNotBecomeVisibleTimeOrEmptyUsefulTime() {
        var finite = new SimulationContact.PayloadProfile(400, 100, 400, 400, 400, 400);
        var result = engine.run(input(20, List.of(new SimulationContact(window("A", A, 0, 20), finite)),
                operation("reuse", REUSE, 0, A, null, "A")));
        assertThat(result.metrics().servedContacts()).isEqualTo(1);
        assertThat(result.metrics().usefulSecureTime()).isEqualTo(Duration.ofSeconds(2));
        assertThat(result.metrics().contacts().getFirst().uplinkBytes()).isEqualTo(400);
        assertThat(result.finalState().key(OLD_A.uplink()).nextNonceCounter()).isEqualTo(5);
    }

    @Test void nonceExhaustionStopsWithoutWrappingOrResettingCounters() {
        var base = initialState();
        var ctx = base.context(A);
        var keys = new TreeMap<>(base.keys());
        for (var keyId : List.of(OLD_A.uplink(), OLD_A.downlink())) {
            var key = keys.get(keyId);
            keys.put(keyId, new KeyState(key.id(), key.contextId(), key.direction(), key.createdAt(), key.notAfter(),
                    key.activatedAt(), key.status(), key.installedOnSpacecraft(), null, null, null, Long.MAX_VALUE - 1, key.lastSuccessfulContactEnd()));
        }
        var contexts = new TreeMap<>(base.contexts());
        var up = ctx.uplink(); var down = ctx.downlink();
        contexts.put(A, new SecurityContext(A,
                new SecurityAssociation(up.id(), A, Direction.UPLINK, up.status(), up.keyId(), up.generation(), Long.MAX_VALUE - 1, true),
                new SecurityAssociation(down.id(), A, Direction.DOWNLINK, down.status(), down.keyId(), down.generation(), Long.MAX_VALUE - 1, true),
                null, null, 0, 0));
        var exhausted = new SecurityState(base.satelliteId(), base.asOf(), contexts, keys);
        var scenario = input(20, List.of(contact("A", A, 0, 20)), operation("reuse", REUSE, 0, A, null, "A"));
        var result = engine.run(new SimulationScenario(scenario.name(), scenario.start(), scenario.end(), exhausted,
                scenario.constraints(), scenario.costs(), scenario.contacts(), scenario.plan()));
        assertThat(result.finalState().key(OLD_A.uplink()).nextNonceCounter()).isEqualTo(Long.MAX_VALUE);
        assertThat(result.finalState().key(OLD_A.downlink()).nextNonceCounter()).isEqualTo(Long.MAX_VALUE);
        assertThat(result.metrics().usefulSecureTime()).isEqualTo(Duration.ofMillis(500));
        assertThat(result.events()).anyMatch(e -> e.type() == PAYLOAD_REJECTED && "COUNTER_EXHAUSTED".equals(e.failureCode()));
    }

    @Test void candidateExpiryDuringInstallationInterruptsItAtTheDeadline() {
        var scenario = input(20, List.of(contact("A", A, 0, 20)), warmup(), operation("install", INSTALL, 0, A, NEW_A, "A"));
        var shortAge = new SecurityConstraints(CONSTRAINTS.cryptoperiod(), Duration.ofSeconds(3), CONSTRAINTS.maxReuseGap(),
                CONSTRAINTS.authorizedContexts(), Set.of());
        var result = engine.run(new SimulationScenario(scenario.name(), scenario.start(), scenario.end(), scenario.initialState(),
                shortAge, scenario.costs(), scenario.contacts(), scenario.plan()));
        assertThat(result.finalState().key(NEW_A.uplink()).status()).isEqualTo(KeyStatus.DEACTIVATED);
        assertThat(result.finalState().key(NEW_A.downlink()).status()).isEqualTo(KeyStatus.DEACTIVATED);
        assertThat(result.finalState().key(NEW_A.uplink()).installedOnBothPeers()).isFalse();
        assertThat(result.metrics().operationFailures()).containsEntry("KEY_EXPIRED", 1L);
        assertThat(result.events()).anyMatch(e -> e.type() == STEP_INTERRUPTED && e.at().equals(at(3)));
    }

    @Test void overlappingContactInputsAreRejectedByExistingScheduleValidator() {
        assertThatThrownBy(() -> input(20, List.of(contact("A", A, 0, 20), contact("B", B, 10, 20))))
                .hasMessageContaining("Invalid selected schedule");
    }

    @Test void aFrameThatWouldCrossADeadlineIsNotStartedOrCountedAsUseful() {
        var scenario = input(100, List.of(contact("A", A, 60, 100)), operation("reuse", REUSE, 60, A, null, "A"));
        var keys = new TreeMap<>(scenario.initialState().keys());
        var k = keys.get(OLD_A.uplink());
        var deadline = at(69).plusMillis(900);
        keys.put(k.id(), new KeyState(k.id(), k.contextId(), k.direction(), k.createdAt(), deadline, k.activatedAt(),
                k.status(), true, null, null, null, k.nextNonceCounter(), k.lastSuccessfulContactEnd()));
        var state = new SecurityState(SATELLITE, scenario.initialState().asOf(), scenario.initialState().contexts(), keys);
        var result = engine.run(new SimulationScenario(scenario.name(), scenario.start(), scenario.end(), state,
                CONSTRAINTS, scenario.costs(), scenario.contacts(), scenario.plan()));
        assertThat(result.metrics().usefulSecureTime()).isEqualTo(Duration.ofMillis(8750));
        assertThat(result.finalState().key(OLD_A.uplink()).deactivationReason()).isEqualTo(DeactivationReason.EXPIRED);
        assertThat(result.finalState().key(OLD_A.downlink()).deactivationReason()).isEqualTo(DeactivationReason.POLICY_RETIRED);
        assertThat(result.events()).noneMatch(e -> e.type() == PAYLOAD_TRANSFERRED && e.at().isAfter(deadline));
    }
}
