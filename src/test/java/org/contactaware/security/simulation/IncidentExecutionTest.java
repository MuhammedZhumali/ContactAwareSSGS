package org.contactaware.security.simulation;

import org.contactaware.security.*;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.contactaware.security.simulation.HorizonWitness.*;
import static org.contactaware.security.simulation.SimulationEvent.Type.*;

class IncidentExecutionTest {
    @Test void measuredProfileCannotSilentlyLabelSyntheticNoticeCostsAsMeasured() {
        var synthetic = SecurityCostProfile.syntheticWitness();
        var measured = new SecurityCostProfile("measured-example", SecurityCostProfile.Provenance.MEASURED,
                "Test fixture only", synthetic.operations());
        assertThatIllegalArgumentException().isThrownBy(() -> measured.cost(SecurityAction.NOTIFY_REVOCATION))
                .withMessageContaining("must explicitly specify NOTIFY_REVOCATION");
    }

    @Test void hiddenDisclosureDoesNotCreateDecisionsOrChangeObservations() {
        var nominal = new SecurityPolicyController(serviceRule()).run(single(List.of()));
        var hidden = new SecurityPolicyController(serviceRule()).run(single(List.of(
                new TrafficKeyDisclosure("hidden", A, at(3).plusMillis(125), null))));
        assertThat(hidden.status()).isEqualTo(ControlledSimulationResult.Status.FINISHED);
        assertThat(hidden.decisions()).isEqualTo(nominal.decisions());
        assertThat(hidden.simulation().metrics()).isEqualTo(nominal.simulation().metrics());
        assertThat(hidden.snapshot().securityState()).isEqualTo(nominal.snapshot().securityState());
        var captured = event(hidden, KEY_DISCLOSED);
        assertThat(captured.keyIds()).isEqualTo(OLD_A);
        assertThat(hidden.decisions()).noneMatch(d -> d.at().equals(captured.at()));
    }

    @Test void hiddenFutureDetectionDoesNotChangeEarlierChoicesAndNoAttackDataEntersBranches() {
        var left = new SecurityPolicyController(serviceRule()).run(single(List.of(
                new TrafficKeyDisclosure("a", A, at(3).plusMillis(125), at(6).plusMillis(125)))));
        var right = new SecurityPolicyController(serviceRule()).run(single(List.of(
                new TrafficKeyDisclosure("different-id", A, at(4).plusMillis(125), at(8).plusMillis(125)))));
        assertThat(left.decisions().stream().filter(d -> d.at().isBefore(at(6).plusMillis(125))).toList())
                .usingRecursiveComparison().ignoringFields("resumedAt").isEqualTo(
                        right.decisions().stream().filter(d -> d.at().isBefore(at(6).plusMillis(125))).toList());
        var before = left.decisions().stream().filter(d -> d.at().equals(at(5))).findFirst().orElseThrow().observation();
        var branch = new BranchEngine().create(before);
        assertThat(branch.snapshot().scenario().disclosures()).isEmpty();
        assertThat(branch.snapshot().queuedEvents()).noneMatch(e -> e.kind() == ExecutionSnapshot.EventKind.DISCLOSURE
                || e.kind() == ExecutionSnapshot.EventKind.REVOCATION_NOTICE);
    }

    @Test void groundRevocationDropsDownlinkButCannotInstantlyStopSpacecraftEmission() {
        var detected = at(5).plusMillis(375);
        var result = new SecurityPolicyController(new FixedPeriodPolicy(Duration.ofSeconds(1000))).run(single(List.of(
                new TrafficKeyDisclosure("incident", A, at(4), detected))));
        assertThat(result.status()).isEqualTo(ControlledSimulationResult.Status.FINISHED);
        var ground = event(result, REVOCATION_RECEIVED_GROUND);
        var remote = event(result, REVOCATION_RECEIVED_SPACECRAFT);
        assertThat(ground.at()).isEqualTo(detected);
        assertThat(remote.at()).isEqualTo(at(5).plusMillis(900));
        var aborted = event(result, PAYLOAD_ABORTED);
        assertThat(aborted.at()).isEqualTo(at(5).plusMillis(500));
        assertThat(aborted.frameEvidence().emittedBytes()).isEqualTo(100);
        assertThat(aborted.frameEvidence().direction()).isEqualTo(Direction.DOWNLINK);
        assertThat(aborted.frameEvidence().delivered()).isFalse();
        assertThat(aborted.uplinkPayloadBytes() + aborted.downlinkPayloadBytes()).isZero();
        assertThat(aborted.usefulTime()).isZero();
        assertThat(result.snapshot().securityState().key(OLD_A.uplink()).deactivationReason()).isEqualTo(DeactivationReason.KNOWN_COMPROMISE);
        assertThat(result.snapshot().journal()).filteredOn(e -> e.type() == PAYLOAD_TRANSFERRED
                && e.at().isAfter(detected)).allSatisfy(e -> assertThat(e.keyIds()).isNotEqualTo(OLD_A));
        assertThat(result.snapshot().journal()).anyMatch(e -> e.type() == STEP_COMPLETED
                && e.action() == SecurityAction.SWITCH && e.at().isBefore(at(20)));
        assertThat(result.snapshot().journal()).filteredOn(e -> e.type() == STEP_COMPLETED
                && e.action() == SecurityAction.NOTIFY_REVOCATION).hasSize(1);
        assertThat(result.snapshot().journal()).filteredOn(e -> e.type() == REVOCATION_RECEIVED_SPACECRAFT).hasSize(1);
        assertThat(new MetricsCollector().collect(result.snapshot().scenario().contacts(), result.snapshot().journal()))
                .isEqualTo(result.simulation().metrics());
        var exposure = new SecurityMetricsCollector().collect(result.snapshot().scenario(), result.snapshot().journal());
        assertThat(exposure.bytesAfterGroundNotice()).isEqualTo(50);
        assertThat(exposure.useAfterGroundNotice()).isEqualTo(Duration.ofMillis(125));
        var blocked = result.decisions().stream().filter(d -> d.at().equals(detected)).findFirst().orElseThrow().observation();
        assertThat(blocked.inFlightFrame().deliveryRejectedAtGround()).isTrue();
        assertThat(new SecurityActionGenerator().candidates(blocked)).allMatch(a -> a.kind() == PlanningAction.Kind.WAIT);
    }

    @Test void groundRevocationImmediatelyStopsItsOwnUplinkTransmitter() {
        var detected = at(5).plusMillis(125);
        var result = new SecurityPolicyController(serviceRule()).run(single(List.of(
                new TrafficKeyDisclosure("uplink-stop", A, at(4), detected))));
        var aborted = event(result, PAYLOAD_ABORTED);
        assertThat(aborted.at()).isEqualTo(detected);
        assertThat(aborted.frameEvidence().direction()).isEqualTo(Direction.UPLINK);
        assertThat(aborted.frameEvidence().emittedBytes()).isEqualTo(50);
        assertThat(event(result, REVOCATION_RECEIVED_SPACECRAFT).at()).isEqualTo(detected.plusMillis(400));
        var exposure = new SecurityMetricsCollector().collect(result.snapshot().scenario(), result.snapshot().journal());
        assertThat(exposure.bytesAfterGroundNotice()).isZero();
        assertThat(exposure.useAfterGroundNotice()).isZero();
    }

    @Test void rejectedDownlinkSurvivesCheckpointAndNominalBranchWithoutUsefulCredit() {
        var contact = new SimulationContact(new org.contactaware.contacts.Model.ContactWindow("short", SATELLITE, "A",
                at(0), at(1).plusMillis(500), null, null, List.of()), SimulationContact.PayloadProfile.syntheticWitness());
        var scenario = new SimulationScenario("rejected-downlink", at(0), contact.window().end(), initialState(), CONSTRAINTS,
                SecurityCostProfile.syntheticWitness(), List.of(contact), List.of(), List.of(
                new TrafficKeyDisclosure("short-incident", A, at(0).plusMillis(750), at(1).plusMillis(375))));
        var result = new SecurityPolicyController(serviceRule()).run(scenario);
        var observation = result.decisions().stream().filter(d -> d.at().equals(at(1).plusMillis(375)))
                .findFirst().orElseThrow().observation();
        assertThat(observation.inFlightFrame().deliveryRejectedAtGround()).isTrue();
        var branches = new BranchEngine();
        var branch = branches.create(observation);
        var bound = new PlanningOptimisticBound(observation).upperBound(branch);
        assertThat(bound.usefulSecureTime()).isZero();
        branch = branches.execute(branch, new SecurityActionGenerator().candidates(observation).getFirst());
        assertThat(new ContinuationEvaluator().evaluate(branch).evaluation().usefulSecureTime()).isZero();
        assertThat(branch.snapshot().payloadProgress()).isEqualTo(observation.payloadProgress());
        assertThat(branch.snapshot().journal()).filteredOn(e -> e.type() == PAYLOAD_ABORTED).hasSize(1);

        var prescribed = new SimulationScenario(scenario.name(), scenario.start(), scenario.end(), scenario.initialState(),
                scenario.constraints(), scenario.costs(), scenario.contacts(),
                List.of(operation("open", SecurityAction.OPEN, 0, A, null, "short")), scenario.disclosures());
        var engine = new SimulationEngine();
        var checkpoint = engine.advance(engine.start(prescribed), at(1).plusMillis(400));
        assertThat(checkpoint.inFlightFrame().deliveryRejectedAtGround()).isTrue();
        assertThat(checkpoint.securityState().context(A).status()).isEqualTo(AssociationStatus.UNKEYED);
        assertThat(engine.resume(checkpoint)).isEqualTo(engine.run(prescribed));
    }

    @Test void disclosureAndNoticeAtFrameBoundaryCommitFrameOnceBeforeBlockingNext() {
        var result = new SecurityPolicyController(serviceRule()).run(single(List.of(
                new TrafficKeyDisclosure("same-time", A, at(3), at(3)))));
        var atBoundary = result.snapshot().journal().stream().filter(e -> e.at().equals(at(3))).toList();
        assertThat(atBoundary.stream().filter(e -> e.type() == PAYLOAD_TRANSFERRED)).hasSize(1);
        assertThat(atBoundary.stream().map(SimulationEvent::type).toList())
                .containsSubsequence(PAYLOAD_TRANSFERRED, KEY_DISCLOSED, REVOCATION_RECEIVED_GROUND);
        assertThat(atBoundary).noneMatch(e -> e.type() == PAYLOAD_ABORTED);
        assertThat(result.snapshot().securityState().key(OLD_A.uplink()).nextNonceCounter()).isEqualTo(5);
        assertThat(result.snapshot().securityState().key(OLD_A.downlink()).nextNonceCounter()).isEqualTo(5);
    }

    @Test void noticeInGapAllowsPaidGroundPreparationButNoSpacecraftDeliveryUntilContact() {
        var contacts = List.of(contact("C1", 0, 10), contact("C2", 30, 50));
        var scenario = new SimulationScenario("gap", at(0), at(50), initialState(), CONSTRAINTS,
                SecurityCostProfile.syntheticWitness(), contacts, List.of(), List.of(
                new TrafficKeyDisclosure("gap-incident", A, at(5), at(15))));
        var result = new SecurityPolicyController(new FixedPeriodPolicy(Duration.ofSeconds(1000))).run(scenario);
        assertThat(result.status()).isEqualTo(ControlledSimulationResult.Status.FINISHED);
        assertThat(event(result, REVOCATION_RECEIVED_GROUND).at()).isEqualTo(at(15));
        assertThat(event(result, REVOCATION_RECEIVED_SPACECRAFT).at()).isEqualTo(at(30).plusMillis(400));
        assertThat(result.snapshot().journal()).anyMatch(e -> e.type() == STEP_COMPLETED
                && e.action() == SecurityAction.PREPARE && e.at().equals(at(17)));
        assertThat(result.snapshot().journal()).noneMatch(e -> e.action() != null && e.action().requiresContact()
                && e.type() == STEP_COMPLETED && e.at().isAfter(at(10)) && e.at().isBefore(at(30)));
    }

    @Test void lateOldKeyNoticeDoesNotRevokeOrRotateIndependentCurrentPair() {
        var result = new SecurityPolicyController(new FixedPeriodPolicy(Duration.ofSeconds(85))).run(single(List.of(
                new TrafficKeyDisclosure("late", A, at(2), at(13)))));
        assertThat(result.status()).isEqualTo(ControlledSimulationResult.Status.FINISHED);
        var state = result.snapshot().securityState();
        assertThat(state.key(OLD_A.uplink()).deactivationReason()).isEqualTo(DeactivationReason.ROTATED);
        assertThat(result.snapshot().journal()).filteredOn(e -> e.type() == STEP_COMPLETED && e.action() == SecurityAction.SWITCH).hasSize(1);
        var key = state.keys().values().stream().filter(k -> k.direction() == Direction.UPLINK && k.contextId().equals(A)
                && !k.id().equals(OLD_A.uplink())).findFirst().orElseThrow();
        assertThat(key.status()).isEqualTo(KeyStatus.ACTIVE);
        assertThat(result.snapshot().journal()).noneMatch(e -> e.type() == PAYLOAD_ABORTED && e.at().equals(at(13)));
    }

    @Test void noTargetDisclosureAndItsDetectionAreNotDecisionOpportunities() {
        var t = new LifecycleTransitions(CONSTRAINTS);
        var empty = t.createContext(SecurityState.empty(SATELLITE, at(0)), A, "up", "down", at(0));
        var base = new SimulationScenario("empty", at(0), at(20), empty, CONSTRAINTS,
                SecurityCostProfile.syntheticWitness(), List.of(contact("C1", 0, 20)), List.of());
        var attacked = new SimulationScenario(base.name(), base.start(), base.end(), empty, base.constraints(), base.costs(),
                base.contacts(), base.plan(), List.of(new TrafficKeyDisclosure("no-target", A, at(2), at(3))));
        var nominal = new SecurityPolicyController(waitOnly()).run(base);
        var actual = new SecurityPolicyController(waitOnly()).run(attacked);
        assertThat(actual.decisions()).isEqualTo(nominal.decisions());
        assertThat(actual.snapshot().journal()).filteredOn(e -> e.type() == DISCLOSURE_NO_TARGET).hasSize(1);
        assertThat(actual.snapshot().journal()).noneMatch(e -> e.type() == REVOCATION_RECEIVED_GROUND);
    }

    @Test void checkpointRestorationAroundIncidentAndPartialWorkReplaysExactly() {
        var scenario = new SimulationScenario("prescribed", at(0), at(20), initialState(), CONSTRAINTS,
                SecurityCostProfile.syntheticWitness(), List.of(contact("C1", 0, 20)),
                List.of(operation("open", SecurityAction.OPEN, 0, A, null, "C1")),
                List.of(new TrafficKeyDisclosure("restore", A, at(4), at(5).plusMillis(125))));
        var engine = new SimulationEngine();
        var continuous = engine.run(scenario);
        var checkpoint = engine.advance(engine.start(scenario), at(4).plusMillis(100));
        assertThat(engine.resume(checkpoint)).isEqualTo(continuous);
        var after = engine.advance(checkpoint, at(5).plusMillis(200));
        assertThat(engine.resume(after)).isEqualTo(continuous);
    }

    @Test void noticeInterruptingOpenDoesNotInventPersistentUncertaintyAndRecoveryStillWorks() {
        var result = new SecurityPolicyController(serviceRule()).run(single(List.of(
                new TrafficKeyDisclosure("open-interruption", A, at(0).plusMillis(250), at(0).plusMillis(500)))));
        assertThat(result.status()).isEqualTo(ControlledSimulationResult.Status.FINISHED);
        assertThat(result.snapshot().uncertainContexts()).isEmpty();
        assertThat(result.snapshot().journal()).anyMatch(e -> e.type() == STEP_INTERRUPTED
                && e.action() == SecurityAction.OPEN && e.failureCode().equals("KNOWN_COMPROMISE"));
        assertThat(result.snapshot().journal()).anyMatch(e -> e.type() == PAYLOAD_TRANSFERRED && !e.keyIds().equals(OLD_A));
    }

    @Test void unrelatedExpiryDoesNotInterruptIndependentCredentialNotification() {
        var base = single(List.of(new TrafficKeyDisclosure("expiry-during-notice", A, at(0).plusMillis(250), at(0).plusMillis(500))));
        var keys = new TreeMap<>(base.initialState().keys());
        var k = keys.get(OLD_B.uplink());
        keys.put(k.id(), new KeyState(k.id(), k.contextId(), k.direction(), k.createdAt(), at(0).plusMillis(700),
                k.activatedAt(), k.status(), k.installedOnSpacecraft(), k.deactivatedAt(), k.deactivationReason(),
                k.destroyedAt(), k.nextNonceCounter(), k.lastSuccessfulContactEnd()));
        var initial = new SecurityState(base.initialState().satelliteId(), base.initialState().asOf(), base.initialState().contexts(), keys);
        base = new SimulationScenario(base.name(), base.start(), base.end(), initial, base.constraints(), base.costs(),
                base.contacts(), base.plan(), base.disclosures());
        var engine = new SimulationEngine();
        var before = engine.controlledSnapshot(engine.start(base));
        var current = engine.controlledAdvance(before, at(0).plusMillis(500), false);
        var o = PlanningObservation.from(current, 1);
        var response = IncidentResponse.next(current, o, new SecurityActionGenerator().candidates(o));
        assertThat(response.notice()).isNotNull();
        current = engine.controlledStart(current, response.notice());
        var after = engine.advance(current, at(1));
        assertThat(after.journal()).anyMatch(e -> e.type() == KEY_EXPIRED && e.at().equals(at(0).plusMillis(700)));
        assertThat(after.journal()).anyMatch(e -> e.type() == STEP_COMPLETED && e.action() == SecurityAction.NOTIFY_REVOCATION);
        assertThat(after.journal()).noneMatch(e -> e.type() == STEP_INTERRUPTED && e.action() == SecurityAction.NOTIFY_REVOCATION);
    }

    private static SimulationEvent event(ControlledSimulationResult result, SimulationEvent.Type type) {
        return result.snapshot().journal().stream().filter(e -> e.type() == type).findFirst().orElseThrow();
    }
    private static SimulationContact contact(String id, long start, long end) {
        return new SimulationContact(window(id, A, start, end), SimulationContact.PayloadProfile.syntheticWitness());
    }
    private static SimulationScenario single(List<TrafficKeyDisclosure> incidents) {
        return new SimulationScenario("single", at(0), at(20), initialState(), CONSTRAINTS,
                SecurityCostProfile.syntheticWitness(), List.of(contact("C1", 0, 20)), List.of(), incidents);
    }
    private static SecurityPolicy serviceRule() {
        return new SecurityPolicy() {
            public PolicyConfiguration configuration() { return HorizonPolicy.next(); }
            public PolicySelection select(PlanningObservation observation, List<PlanningAction> candidates) {
                for (var kind : List.of(PlanningAction.Kind.CONTINUE, PlanningAction.Kind.OPEN, PlanningAction.Kind.REUSE))
                    for (var action : candidates) if (action.kind() == kind) return PolicySelection.rule(action, "Test service");
                return waitOnly().select(observation, candidates);
            }
        };
    }
    private static SecurityPolicy waitOnly() {
        return new SecurityPolicy() {
            public PolicyConfiguration configuration() { return HorizonPolicy.next(); }
            public PolicySelection select(PlanningObservation observation, List<PlanningAction> candidates) {
                return PolicySelection.rule(candidates.stream().filter(a -> a.kind() == PlanningAction.Kind.WAIT)
                        .min(Comparator.comparing(PlanningAction::until)).orElse(null), "Test wait");
            }
        };
    }
}
