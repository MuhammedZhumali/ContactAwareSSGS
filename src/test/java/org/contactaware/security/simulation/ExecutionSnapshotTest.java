package org.contactaware.security.simulation;

import org.contactaware.contacts.JsonFiles;
import org.contactaware.security.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.*;
import java.util.*;

import static org.assertj.core.api.Assertions.*;
import static org.contactaware.security.SecurityAction.*;
import static org.contactaware.security.simulation.HorizonWitness.*;
import static org.contactaware.security.simulation.SimulationEvent.Type.*;

class ExecutionSnapshotTest {
    private final SimulationEngine engine = new SimulationEngine();

    @ParameterizedTest
    @CsvSource({"X,NEXT", "X,MULTI", "X,STAGED", "Y,NEXT", "Y,MULTI"})
    void arbitraryPauseBoundariesPreserveTheEntireWitnessResult(Sequence sequence, Plan plan) throws Exception {
        var input = scenario(sequence, plan);
        var uninterrupted = engine.run(input);
        var snapshot = engine.start(input);
        for (var stop : List.of(at(-2), at(-2).plusNanos(1), at(-1), at(0), at(0).plusMillis(103),
                at(0).plusMillis(104), at(1).plusMillis(125), at(2), at(4), at(4).plusMillis(500),
                at(5), at(6).plusMillis(125), at(20), at(30), at(31).plusMillis(125), at(50),
                at(70).minusNanos(1), at(70), at(80), at(81).plusMillis(125), at(85))) {
            snapshot = engine.advance(snapshot, stop);
            assertThat(snapshot.at()).isEqualTo(stop);
            assertThat(snapshot.journal()).noneMatch(e -> e.type() == SIMULATION_ENDED);
        }

        var resumed = new SimulationEngine().resume(snapshot);
        assertThat(resumed).isEqualTo(uninterrupted);
        assertThat(JsonFiles.JSON.writeValueAsBytes(resumed))
                .isEqualTo(JsonFiles.JSON.writeValueAsBytes(uninterrupted));
    }

    @Test void startProcessesItsTimestampAndDoesNotCreatePreparationMaterialEarly() {
        var input = scenario(Sequence.X, Plan.MULTI);
        var snapshot = engine.start(input);

        assertThat(snapshot.scenario()).isEqualTo(input);
        assertThat(snapshot.at()).isEqualTo(at(-2));
        assertThat(snapshot.pendingOperations()).hasSize(1);
        var preparation = snapshot.pendingOperations().getFirst();
        assertThat(preparation.steps()).containsExactly(PREPARE);
        assertThat(preparation.stepIndex()).isZero();
        assertThat(preparation.stepStartedAt()).isEqualTo(at(-2));
        assertThat(preparation.stepKeys()).isEqualTo(NEW_A);
        assertThat(preparation.reservations()).isNotEmpty();
        assertThat(snapshot.securityState().keys()).doesNotContainKeys(NEW_A.uplink(), NEW_A.downlink());
        assertThat(snapshot.journal()).extracting(SimulationEvent::type).containsExactly(ACTION_STARTED, STEP_STARTED);
        assertThat(snapshot.recordedCosts()).isEqualTo(ResourceUsage.ZERO);
        assertThat(snapshot.accruedCosts()).isEqualTo(ResourceUsage.ZERO);
        assertThat(engine.advance(snapshot, snapshot.at())).isEqualTo(snapshot);
    }

    @Test void independentBranchesNeverMutateTheOriginalSnapshotOrEachOther() throws Exception {
        var input = scenario(Sequence.X, Plan.MULTI);
        var original = engine.advance(engine.start(input), at(2));
        var encodedOriginal = JsonFiles.JSON.writeValueAsBytes(original);
        var firstBranch = engine.advance(original, at(4).plusMillis(500));
        var encodedFirstBranch = JsonFiles.JSON.writeValueAsBytes(firstBranch);
        var secondBranch = engine.advance(original, at(6).plusMillis(125));

        assertThat(JsonFiles.JSON.writeValueAsBytes(original)).isEqualTo(encodedOriginal);
        assertThat(JsonFiles.JSON.writeValueAsBytes(firstBranch)).isEqualTo(encodedFirstBranch);
        assertThat(engine.advance(original, at(4).plusMillis(500))).isEqualTo(firstBranch);
        assertThat(engine.resume(firstBranch)).isEqualTo(engine.resume(secondBranch));
        assertThat(engine.resume(original)).isEqualTo(engine.run(input));
        assertThat(JsonFiles.JSON.writeValueAsBytes(original)).isEqualTo(encodedOriginal);
        assertThat(original.securityState().key(NEW_A.uplink()).installedOnBothPeers()).isFalse();
        assertThat(firstBranch.securityState().key(NEW_A.uplink()).installedOnBothPeers()).isTrue();
    }

    @Test void collectionsExposedByTheSnapshotAndPendingOperationAreImmutable() {
        var snapshot = engine.advance(engine.start(scenario(Sequence.X, Plan.MULTI)), at(2));
        var pending = snapshot.pendingOperations().getFirst();

        assertThatThrownBy(() -> snapshot.journal().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> snapshot.pendingOperations().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> snapshot.uncertainContexts().add(A)).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> snapshot.payloadProgress().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> snapshot.securityState().keys().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> pending.steps().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> pending.reservations().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> pending.planned().missingAcknowledgements().add(INSTALL))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test void accruedInstallCostsIncludeWorkInProgressWithoutSyntheticTerminalEvents() {
        var input = input(20, List.of(contact("A", A, 0, 20)), warmup(),
                operation("install", INSTALL, 0, A, NEW_A, "A"));
        var snapshot = engine.advance(engine.start(input), at(2));

        assertThat(snapshot.recordedCosts()).isEqualTo(new ResourceUsage(Duration.ofSeconds(2), Duration.ZERO,
                Duration.ofMillis(200), Duration.ZERO, 0, 0));
        assertThat(snapshot.accruedCosts()).isEqualTo(new ResourceUsage(Duration.ofSeconds(4), Duration.ofMillis(200),
                Duration.ofMillis(300), Duration.ofMillis(1700), 600, 80));
        assertThat(snapshot.pendingOperations()).hasSize(1);
        assertThat(snapshot.pendingOperations().getFirst().stepStartedAt()).isEqualTo(at(0));
        assertThat(snapshot.securityState().key(NEW_A.uplink()).installedOnBothPeers()).isFalse();
        assertThat(snapshot.journal()).noneMatch(e -> e.type() == STEP_INTERRUPTED || e.type() == ACTION_FAILED);

        var completed = engine.advance(snapshot, at(4));
        assertThat(completed.pendingOperations()).isEmpty();
        assertThat(completed.securityState().key(NEW_A.uplink()).installedOnBothPeers()).isTrue();
        assertThat(completed.recordedCosts()).isEqualTo(completed.accruedCosts());
        assertThat(completed.recordedCosts().managementBytes()).isEqualTo(800);
        assertThat(completed.recordedCosts().totalCpu()).isEqualTo(Duration.ofMillis(500));
        assertThat(engine.resume(completed)).isEqualTo(engine.run(input));
    }

    @Test void byteRoundingUsesTheOriginalRadioPhaseStartAcrossRepeatedPauses() {
        var input = input(20, List.of(contact("A", A, 0, 20)), warmup(),
                operation("install", INSTALL, 0, A, NEW_A, "A"));
        var snapshot = engine.advance(engine.start(input), at(0).plusMillis(100));
        // Each millisecond sends 0.4 bytes. Rounding individual pause intervals would lose all bytes.
        for (int millis = 101; millis <= 125; millis++) {
            snapshot = engine.advance(snapshot, at(0).plusMillis(millis));
            assertThat(snapshot.accruedCosts().uplinkBytes()).isEqualTo((millis - 100L) * 600 / 1500);
            assertThat(snapshot.recordedCosts().uplinkBytes()).isZero();
        }
        assertThat(snapshot.accruedCosts().uplinkBytes()).isEqualTo(10);
        assertThat(engine.resume(snapshot)).isEqualTo(engine.run(input));
        assertThat(engine.resume(snapshot).metrics().controlCosts().managementBytes()).isEqualTo(800);
    }

    @Test void restorePreservesMissingAcknowledgementsAndDoesNotConfirmInstallation() {
        var install = controlled(operation("install", INSTALL, 0, A, NEW_A, "A"), null, INSTALL);
        var input = input(20, List.of(contact("A", A, 0, 20)), warmup(), install,
                operation("switch", SWITCH, 4, A, NEW_A, "A"), operation("reuse", REUSE, 5, A, null, "A"));
        var snapshot = engine.advance(engine.start(input), at(2));

        assertThat(snapshot.pendingOperations().getFirst().planned().missingAcknowledgements()).containsExactly(INSTALL);
        var result = new SimulationEngine().resume(snapshot);
        assertThat(result).isEqualTo(engine.run(input));
        assertThat(result.metrics().operationFailures()).containsEntry("MISSING_ACKNOWLEDGEMENT", 1L)
                .containsEntry("KEY_NOT_INSTALLED", 1L);
        assertThat(result.finalState().key(NEW_A.uplink()).installedOnBothPeers()).isFalse();
        assertThat(result.metrics().usefulSecureTime()).isEqualTo(Duration.ofSeconds(14));
        assertThat(result.uncertainContexts()).isEmpty();
    }

    @Test void restoredResourceReservationsRejectAConflictingLaterOperation() {
        var conflict = new PlannedOperation("conflicting-prepare", new ScheduledSecurityAction(PREPARE,
                at(0).plusMillis(50), A, new KeyPairIds("unused-up", "unused-down"), "Same ground CPU"), null);
        var input = input(20, List.of(contact("A", A, 0, 20)), warmup(),
                operation("install", INSTALL, 0, A, NEW_A, "A"), conflict);
        var before = engine.advance(engine.start(input), at(0).plusMillis(25));
        var after = engine.advance(before, at(0).plusMillis(50));

        assertThat(after.pendingOperations()).hasSize(1);
        assertThat(after.pendingOperations().getFirst().planned().id()).isEqualTo("install");
        assertThat(after.journal()).anyMatch(e -> e.type() == ACTION_FAILED
                && "conflicting-prepare".equals(e.operationId()) && "RESOURCE_BUSY".equals(e.failureCode()));
        var result = engine.resume(after);
        assertThat(result).isEqualTo(engine.run(input));
        assertThat(result.finalState().keys()).doesNotContainKeys("unused-up", "unused-down");
        assertThat(result.metrics().createdKeys()).isEqualTo(2);
    }

    @Test void pausingDuringSwitchDoesNotCreateUncertaintyButTheActualInterruptionDoes() {
        var interruption = at(4).plusMillis(500);
        var rotate = controlled(operation("rotate", ROTATE, 0, A, NEW_A, "A"), interruption);
        var input = input(20, List.of(contact("A", A, 0, 20)), warmup(), rotate,
                operation("fallback", REUSE, 6, A, null, "A"));
        var before = engine.advance(engine.start(input), at(4).plusMillis(250));

        assertThat(before.uncertainContexts()).isEmpty();
        assertThat(before.securityState().key(NEW_A.uplink()).installedOnBothPeers()).isTrue();
        assertThat(before.securityState().key(NEW_A.uplink()).activatedAt()).isNull();
        assertThat(before.pendingOperations().getFirst().planned().interruptAt()).isEqualTo(interruption);
        assertThat(before.pendingOperations().getFirst().steps()).containsExactly(INSTALL, SWITCH, OPEN);
        assertThat(before.pendingOperations().getFirst().stepIndex()).isEqualTo(1);
        assertThat(before.pendingOperations().getFirst().stepStartedAt()).isEqualTo(at(4));
        assertThat(before.journal()).noneMatch(e -> e.type() == CONTEXT_UNCERTAIN || e.type() == STEP_INTERRUPTED);

        var after = engine.advance(before, interruption);
        assertThat(after.pendingOperations()).isEmpty();
        assertThat(after.uncertainContexts()).containsExactly(A);
        assertThat(after.journal()).anyMatch(e -> e.type() == STEP_INTERRUPTED && "INTERRUPTED".equals(e.failureCode()));
        var result = engine.resume(after);
        assertThat(result).isEqualTo(engine.run(input));
        assertThat(result.metrics().operationFailures()).containsEntry("INTERRUPTED", 1L).containsEntry("CONTEXT_UNCERTAIN", 1L);
    }

    @Test void aPartialPayloadFrameKeepsItsOriginalIntervalAndCommitsCountersExactlyOnce() {
        var input = input(20, List.of(contact("A", A, 0, 20)), operation("reuse", REUSE, 0, A, null, "A"));
        var snapshot = engine.advance(engine.start(input), at(1).plusMillis(125));

        assertThat(snapshot.inFlightFrame()).isNotNull();
        assertThat(snapshot.inFlightFrame().from()).isEqualTo(at(1));
        assertThat(snapshot.inFlightFrame().until()).isEqualTo(at(1).plusMillis(250));
        assertThat(snapshot.inFlightFrame().keys()).isEqualTo(OLD_A);
        assertThat(snapshot.inFlightFrame().uplink()).isTrue();
        assertThat(snapshot.payloadProgress().get("A").uplinkBytes()).isZero();
        assertThat(snapshot.securityState().key(OLD_A.uplink()).nextNonceCounter()).isEqualTo(1);
        assertThat(snapshot.journal()).noneMatch(e -> e.type() == PAYLOAD_TRANSFERRED);

        var finishedFrame = engine.advance(snapshot, at(1).plusMillis(250));
        assertThat(finishedFrame.payloadProgress().get("A").uplinkBytes()).isEqualTo(100);
        assertThat(finishedFrame.payloadProgress().get("A").downlinkBytes()).isZero();
        assertThat(finishedFrame.payloadProgress().get("A").nextUplink()).isFalse();
        assertThat(finishedFrame.securityState().key(OLD_A.uplink()).nextNonceCounter()).isEqualTo(2);
        assertThat(finishedFrame.securityState().key(OLD_A.downlink()).nextNonceCounter()).isEqualTo(1);
        assertThat(finishedFrame.journal().stream().filter(e -> e.type() == PAYLOAD_TRANSFERRED)).hasSize(1);
        assertThat(finishedFrame.inFlightFrame().from()).isEqualTo(at(1).plusMillis(250));
        assertThat(finishedFrame.inFlightFrame().uplink()).isFalse();
        assertThat(engine.advance(finishedFrame, finishedFrame.at())).isEqualTo(finishedFrame);
        assertThat(engine.resume(finishedFrame)).isEqualTo(engine.run(input));
    }

    @Test void restoreRetainsSimultaneousGroundPreparationAndInFlightPayload() {
        var prepareB = new PlannedOperation("prepare-B", new ScheduledSecurityAction(PREPARE, at(1).plusMillis(100), B,
                new KeyPairIds("B-next-up", "B-next-down"), "Independent ground work"), null);
        var input = input(20, List.of(contact("A", A, 0, 20)), operation("reuse", REUSE, 0, A, null, "A"), prepareB);
        var before = engine.advance(engine.start(input), at(3).plusMillis(50));

        assertThat(before.pendingOperations()).hasSize(1);
        assertThat(before.inFlightFrame().from()).isEqualTo(at(3));
        assertThat(before.inFlightFrame().until()).isEqualTo(at(3).plusMillis(250));
        assertThat(before.securityState().keys()).doesNotContainKey("B-next-up");

        var after = engine.advance(before, at(3).plusMillis(150));
        assertThat(after.pendingOperations()).isEmpty();
        assertThat(after.securityState().key("B-next-up").createdAt()).isEqualTo(at(3).plusMillis(100));
        assertThat(after.inFlightFrame()).isEqualTo(before.inFlightFrame());
        var result = engine.resume(after);
        assertThat(result).isEqualTo(engine.run(input));
        assertThat(result.metrics().usefulSecureTime()).isEqualTo(Duration.ofSeconds(19));
        assertThat(result.metrics().createdKeys()).isEqualTo(2);
        assertThat(result.metrics().controlCosts().totalCpu()).isEqualTo(Duration.ofMillis(260));
    }

    @Test void snapshotOnExpiryCompletesTheAdmissibleFrameBeforeRetiringKeys() {
        var input = input(100, List.of(contact("A", A, 60, 100)), operation("reuse", REUSE, 60, A, null, "A"));
        var before = engine.advance(engine.start(input), at(70).minusNanos(1));
        assertThat(before.securityState().key(OLD_A.uplink()).status()).isEqualTo(KeyStatus.ACTIVE);
        assertThat(before.inFlightFrame().until()).isEqualTo(at(70));

        var expired = engine.advance(before, at(70));
        assertThat(expired.inFlightFrame()).isNull();
        assertThat(expired.securityState().context(A).status()).isEqualTo(AssociationStatus.UNKEYED);
        assertThat(expired.securityState().key(OLD_A.uplink()).nextNonceCounter()).isEqualTo(19);
        assertThat(expired.securityState().key(OLD_A.uplink()).deactivatedAt()).isEqualTo(at(70));
        var frame = expired.journal().stream().filter(e -> e.type() == PAYLOAD_TRANSFERRED).reduce((a, b) -> b).orElseThrow();
        var expiry = expired.journal().stream().filter(e -> e.type() == KEY_EXPIRED).findFirst().orElseThrow();
        assertThat(frame.at()).isEqualTo(at(70));
        assertThat(frame.sequence()).isLessThan(expiry.sequence());
        assertThat(engine.advance(expired, expired.at())).isEqualTo(expired);
        assertThat(engine.resume(expired)).isEqualTo(engine.run(input));
    }

    @Test void restoredCandidateDeadlineInterruptsAnInstallationStillInProgress() {
        var base = input(20, List.of(contact("A", A, 0, 20)), warmup(),
                operation("install", INSTALL, 0, A, NEW_A, "A"));
        var shortAge = new SecurityConstraints(CONSTRAINTS.cryptoperiod(), Duration.ofSeconds(3), CONSTRAINTS.maxReuseGap(),
                CONSTRAINTS.authorizedContexts(), Set.of());
        var input = new SimulationScenario(base.name(), base.start(), base.end(), base.initialState(), shortAge,
                base.costs(), base.contacts(), base.plan());
        var before = engine.advance(engine.start(input), at(2).plusMillis(500));
        assertThat(before.pendingOperations()).hasSize(1);

        var expired = engine.advance(before, at(3));
        assertThat(expired.pendingOperations()).isEmpty();
        assertThat(expired.securityState().key(NEW_A.uplink()).status()).isEqualTo(KeyStatus.DEACTIVATED);
        assertThat(expired.securityState().key(NEW_A.downlink()).status()).isEqualTo(KeyStatus.DEACTIVATED);
        assertThat(expired.securityState().key(NEW_A.uplink()).installedOnBothPeers()).isFalse();
        assertThat(expired.journal()).anyMatch(e -> e.type() == STEP_INTERRUPTED
                && e.at().equals(at(3)) && "KEY_EXPIRED".equals(e.failureCode()));
        assertThat(engine.resume(expired)).isEqualTo(engine.run(input));
    }

    @Test void contactEndStillWinsOverInstallAcknowledgementAtTheSameTimestampAfterRestore() {
        var input = input(5, List.of(contact("A", A, 0, 4)), warmup(),
                operation("install", INSTALL, 0, A, NEW_A, "A"));
        var before = engine.advance(engine.start(input), at(4).minusNanos(1));
        var atEnd = engine.advance(before, at(4));

        assertThat(atEnd.pendingOperations()).isEmpty();
        assertThat(atEnd.securityState().key(NEW_A.uplink()).installedOnBothPeers()).isFalse();
        assertThat(atEnd.recordedCosts().managementBytes()).isEqualTo(800);
        assertThat(atEnd.journal()).anyMatch(e -> e.type() == STEP_INTERRUPTED && "CONTACT_ENDED".equals(e.failureCode()));
        assertThat(atEnd.journal()).noneMatch(e -> e.type() == STEP_COMPLETED && e.action() == INSTALL);
        assertThat(engine.resume(atEnd)).isEqualTo(engine.run(input));
    }

    @Test void explicitInterruptionStillWinsOverAcknowledgementAtTheSameTimestampAfterRestore() {
        var install = controlled(operation("install", INSTALL, 0, A, NEW_A, "A"), at(4));
        var input = input(20, List.of(contact("A", A, 0, 20)), warmup(), install);
        var before = engine.advance(engine.start(input), at(4).minusNanos(1));
        var interrupted = engine.advance(before, at(4));

        assertThat(interrupted.pendingOperations()).isEmpty();
        assertThat(interrupted.securityState().key(NEW_A.uplink()).installedOnBothPeers()).isFalse();
        assertThat(interrupted.recordedCosts().managementBytes()).isEqualTo(800);
        assertThat(interrupted.journal()).anyMatch(e -> e.type() == STEP_INTERRUPTED && "INTERRUPTED".equals(e.failureCode()));
        assertThat(engine.resume(interrupted)).isEqualTo(engine.run(input));
    }

    @Test void pauseAtTheRealHorizonDoesNotFabricateTerminationButResumeFinalizesItOnce() {
        var input = input(-1, List.of(), warmup());
        var paused = engine.advance(engine.start(input), input.end());

        assertThat(paused.pendingOperations()).hasSize(1);
        assertThat(paused.journal()).noneMatch(e -> e.type() == STEP_INTERRUPTED || e.type() == SIMULATION_ENDED);
        assertThat(paused.recordedCosts()).isEqualTo(ResourceUsage.ZERO);
        assertThat(paused.accruedCosts()).isEqualTo(new ResourceUsage(Duration.ofSeconds(1), Duration.ZERO,
                Duration.ofMillis(200), Duration.ZERO, 0, 0));
        assertThat(paused.securityState().keys()).doesNotContainKey(NEW_A.uplink());

        var result = engine.resume(paused);
        assertThat(result).isEqualTo(engine.run(input));
        assertThat(result.metrics().operationFailures()).containsEntry("SIMULATION_ENDED", 1L);
        assertThat(result.events().stream().filter(e -> e.type() == SIMULATION_ENDED)).hasSize(1);
        assertThat(result.metrics().controlCosts()).isEqualTo(paused.accruedCosts());
        assertThat(engine.resume(paused)).isEqualTo(result);
        assertThat(paused.pendingOperations()).hasSize(1);
    }

    @Test void advanceRejectsBackwardsTimeAndTimeOutsideTheScenario() {
        var initial = engine.start(scenario(Sequence.X, Plan.MULTI));
        var paused = engine.advance(initial, at(2));
        assertThatThrownBy(() -> engine.advance(initial, initial.at().minusNanos(1))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> engine.advance(paused, at(1))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> engine.advance(paused, paused.scenario().end().plusNanos(1))).isInstanceOf(IllegalArgumentException.class);
    }

    private SimulationContact contact(String id, ContextId context, long start, long end) {
        return new SimulationContact(window(id, context, start, end), SimulationContact.PayloadProfile.syntheticWitness());
    }

    private SimulationScenario input(long end, List<SimulationContact> contacts, PlannedOperation... operations) {
        return new SimulationScenario("snapshot-boundary-test", at(-2), at(end), initialState(), CONSTRAINTS,
                SecurityCostProfile.syntheticWitness(), contacts, List.of(operations));
    }

    private PlannedOperation warmup() { return operation("warmup", PREPARE, -2, A, NEW_A, null); }

    private PlannedOperation controlled(PlannedOperation operation, Instant interruption, SecurityAction... noAck) {
        return new PlannedOperation(operation.id(), operation.intent(), operation.contactId(), interruption, Set.of(noAck));
    }
}
