package org.contactaware.security.simulation;

import org.contactaware.contacts.JsonFiles;
import org.contactaware.security.*;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.contactaware.security.SecurityAction.*;
import static org.contactaware.security.simulation.HorizonWitness.*;

class PlanningBranchTest {
    private final SimulationEngine simulation = new SimulationEngine();
    private final BranchEngine branches = new BranchEngine();
    private final SecurityActionGenerator generator = new SecurityActionGenerator();
    private final ContinuationEvaluator evaluator = new ContinuationEvaluator();

    @Test void restoringABranchCannotSilentlyEnableAutomaticPayloadOrFinalizeItAsAnExperiment() {
        var branch = reusableBranch();
        var advanced = new SimulationEngine().advance(branch.snapshot(), at(3));
        assertThat(advanced.executionMode()).isEqualTo(ExecutionSnapshot.ExecutionMode.PLANNING_BRANCH);
        assertThat(advanced.inFlightFrame()).isNull();
        assertThat(advanced.usefulSecureTime()).isZero();
        assertThat(advanced.securityState().context(A).status()).isEqualTo(AssociationStatus.OPERATIONAL);
        assertThatThrownBy(() -> new SimulationEngine().resume(advanced)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void realExecutionRejectsReissuingIdsAfterAnInterruptedPreparation() {
        var first = new PlannedOperation("first", new ScheduledSecurityAction(PREPARE, at(-2), A, NEW_A, "First attempt"),
                null, at(-1), Set.of());
        var second = operation("second", PREPARE, 0, A, NEW_A, null);
        var result = simulation.run(input("no-id-reuse", 20, List.of(contact("C1", A, 0, 20)), first, second));
        assertThat(result.metrics().createdKeys()).isZero();
        assertThat(result.metrics().operationFailures()).containsEntry("INTERRUPTED", 1L)
                .containsEntry("KEY_ID_ALREADY_ISSUED", 1L);
        assertThat(result.finalState().keys()).doesNotContainKeys(NEW_A.uplink(), NEW_A.downlink());
    }

    @Test void terminalEvaluationChargesTheInheritedPreparationPrefixWithoutCancellingIt() {
        var costs = SecurityCostProfile.syntheticWitness();
        var profile = new EnumMap<SecurityAction, SecurityCostProfile.OperationCost>(costs.operations());
        profile.put(PREPARE, new SecurityCostProfile.OperationCost(List.of(
                new SecurityCostProfile.Phase(SecurityCostProfile.Resource.GROUND_CPU, Duration.ofSeconds(200), 0))));
        var slow = new SecurityCostProfile("slow-synthetic", costs.provenance(), "Synthetic cutoff test", profile);
        var scenario = new SimulationScenario("long-preparation", at(-2), at(100), initialState(), CONSTRAINTS, slow,
                List.of(contact("C1", A, 0, 20), contact("C2", B, 30, 50)), List.of(warmup()));
        var observation = observed(scenario, at(0), 1);
        var terminal = waitUntil(branches.create(observation), at(50));
        assertThat(terminal.snapshot().pendingOperations()).hasSize(1);
        assertThat(terminal.snapshot().accruedCosts().groundCpu()).isEqualTo(Duration.ofSeconds(52));
        assertThat(evaluator.evaluate(terminal).evaluation().controlCpu()).isEqualTo(Duration.ofSeconds(50));
        assertThat(terminal.snapshot().journal()).noneMatch(e -> e.type() == SimulationEvent.Type.STEP_INTERRUPTED
                || e.type() == SimulationEvent.Type.SIMULATION_ENDED || e.type() == SimulationEvent.Type.ACTION_FAILED);
        assertThat(terminal.snapshot().createdKeys()).isZero();
    }

    @Test void hiddenContactsPlansAndFutureFailuresCannotChangeCandidatesOrTheirScores() throws Exception {
        var contactsX = List.of(contact("C1", A, 0, 20), contact("C2", B, 30, 50), contact("C3", A, 80, 85));
        var contactsY = List.of(contact("C1", A, 0, 20), contact("C2", B, 30, 50), contact("C3", B, 90, 95));
        var install = operation("install", INSTALL, 0, A, NEW_A, "C1");
        var controlled = new PlannedOperation(install.id(), install.intent(), "C1", at(3), Set.of(INSTALL));
        var hidden = new PlannedOperation("hidden", operation("hidden", REUSE, 90, B, null, "C3").intent(),
                "C3", null, Set.of(REUSE));
        var first = observed(input("X", 100, contactsX, warmup(), install), at(2), 1);
        var second = observed(input("Y", 100, contactsY, warmup(), controlled, hidden), at(2), 1);
        assertThat(first).isEqualTo(second);
        var x = branches.create(first);
        var y = branches.create(second);
        assertThat(x).isEqualTo(y);
        assertThat(x.snapshot().scenario().contacts()).extracting(c -> c.window().id()).containsExactly("C1", "C2");
        assertThat(JsonFiles.JSON.writeValueAsString(x)).doesNotContain("hidden", "C3", "secret");

        var actions = generator.candidates(x.observation());
        assertThat(actions).isEqualTo(generator.candidates(y.observation()));
        for (var action : actions) {
            var left = branches.execute(x, action);
            var right = branches.execute(y, action);
            assertThat(left).isEqualTo(right);
            assertThat(evaluator.evaluate(left)).isEqualTo(evaluator.evaluate(right));
        }
        var finished = waitUntil(x, at(4));
        assertThat(finished.snapshot().securityState().key(NEW_A.uplink()).installedOnBothPeers()).isTrue();
        assertThat(finished.snapshot().journal()).noneMatch(e -> e.type() == SimulationEvent.Type.ACTION_FAILED);
    }

    @Test void branchEnumerationOrderDoesNotAllocateDifferentIdsOrMutateSiblings() throws Exception {
        var origin = observed(input("branch-order", 50,
                List.of(contact("C1", A, 0, 20), contact("C2", B, 30, 50)), warmup()), at(0), 1);
        var root = branches.create(origin);
        var encoded = JsonFiles.JSON.writeValueAsBytes(root);
        var actions = generator.candidates(root.observation());
        assertThat(actions).anyMatch(a -> a.kind() == PlanningAction.Kind.PREPARE)
                .anyMatch(a -> a.kind() == PlanningAction.Kind.INSTALL)
                .anyMatch(a -> a.kind() == PlanningAction.Kind.WAIT);
        var forward = new HashMap<PlanningAction, PlanningBranch>();
        for (var action : actions) forward.put(action, branches.execute(root, action));
        var reversed = new ArrayList<>(actions);
        Collections.reverse(reversed);
        for (var action : reversed) {
            var actual = branches.execute(root, action);
            assertThat(actual).isEqualTo(forward.get(action));
            assertThat(evaluator.evaluate(actual)).isEqualTo(evaluator.evaluate(forward.get(action)));
        }
        assertThat(JsonFiles.JSON.writeValueAsBytes(root)).isEqualTo(encoded);
        assertThat(root.snapshot().securityState().keys()).hasSize(6);
    }

    @Test void waitDoesNotSendPayloadAndContinueStopsAfterEveryCompleteFrameIncludingServiceThreshold() {
        var root = reusableBranch();
        var opened = waitUntil(root, at(1));
        assertThat(opened.snapshot().inFlightFrame()).isNull();
        assertThat(opened.snapshot().securityState().context(A).status()).isEqualTo(AssociationStatus.OPERATIONAL);
        assertThat(evaluator.evaluate(opened).evaluation().usefulSecureTime()).isZero();

        var waited = waitUntil(opened, at(20));
        assertThat(evaluator.evaluate(waited).evaluation().servedContacts()).isZero();
        assertThat(evaluator.evaluate(waited).evaluation().usefulSecureTime()).isZero();
        assertThat(waited.snapshot().securityState().key(OLD_A.uplink()).nextNonceCounter()).isEqualTo(1);

        var sent = opened;
        for (int frame = 1; frame <= 8; frame++) {
            sent = branches.execute(sent, candidate(sent, PlanningAction.Kind.CONTINUE));
            assertThat(sent.snapshot().at()).isEqualTo(at(1).plusMillis(250L * frame));
            assertThat(sent.snapshot().inFlightFrame()).isNull();
            assertThat(evaluator.evaluate(sent).evaluation().servedContacts()).isEqualTo(frame == 8 ? 1 : 0);
        }
        assertThat(sent.snapshot().payloadProgress().get("C1"))
                .isEqualTo(new ExecutionSnapshot.PayloadProgress(400, 400, true));
        assertThat(evaluator.evaluate(sent).evaluation().usefulSecureTime()).isEqualTo(Duration.ofSeconds(2));
        assertThat(generator.candidates(sent.observation())).anyMatch(a -> a.kind() == PlanningAction.Kind.WAIT)
                .anyMatch(a -> a.kind() == PlanningAction.Kind.CONTINUE);
        var afterServiceWait = waitUntil(sent, at(20));
        assertThat(afterServiceWait.snapshot().payloadProgress()).isEqualTo(sent.snapshot().payloadProgress());
        assertThat(evaluator.evaluate(afterServiceWait).evaluation()).isEqualTo(evaluator.evaluate(sent).evaluation());
    }

    @Test void waitFinishesAnAlreadyCommittedFrameButDoesNotStartAnotherOne() {
        var input = input("mid-frame", 20, List.of(contact("C1", A, 0, 20)),
                operation("reuse", REUSE, 0, A, null, "C1"));
        var observation = observed(input, at(1).plusMillis(125), 1);
        var root = branches.create(observation);
        assertThat(root.snapshot().inFlightFrame()).isEqualTo(observation.inFlightFrame());
        assertThat(generator.candidates(root.observation())).noneMatch(a -> a.kind() == PlanningAction.Kind.CONTINUE);
        var after = waitUntil(root, at(1).plusMillis(250));
        assertThat(after.snapshot().inFlightFrame()).isNull();
        assertThat(after.snapshot().payloadProgress().get("C1").uplinkBytes()).isEqualTo(100);
        assertThat(after.snapshot().securityState().key(OLD_A.uplink()).nextNonceCounter()).isEqualTo(2);
        assertThat(evaluator.evaluate(after).evaluation().usefulSecureTime()).isEqualTo(Duration.ofMillis(250));
        var later = waitUntil(after, at(20));
        assertThat(later.snapshot().payloadProgress()).isEqualTo(after.snapshot().payloadProgress());
    }

    @Test void futureAcknowledgementIsNominalButAnAlreadyObservedFailureIsNotErased() {
        var normal = operation("reuse", REUSE, 0, A, null, "C1");
        var failed = new PlannedOperation(normal.id(), normal.intent(), normal.contactId(), null, Set.of(REUSE));
        var input = input("missing-ack", 20, List.of(contact("C1", A, 0, 20)), failed);
        var prediction = waitUntil(branches.create(observed(input, at(0), 1)), at(1));
        assertThat(prediction.snapshot().securityState().context(A).status()).isEqualTo(AssociationStatus.OPERATIONAL);
        assertThat(prediction.snapshot().uncertainContexts()).isEmpty();

        var afterFailure = branches.create(observed(input, at(1), 1));
        assertThat(afterFailure.snapshot().uncertainContexts()).containsExactly(A);
        assertThat(generator.candidates(afterFailure.observation())).noneMatch(a ->
                a.kind() == PlanningAction.Kind.CONTINUE || a.kind() == PlanningAction.Kind.OPEN || a.kind() == PlanningAction.Kind.REUSE);
        assertThat(evaluator.evaluate(afterFailure).evaluation().controlCpu()).isZero();
    }

    @Test void failedPreparationRetainsIssuedIdentifiersWithoutLeakingFutureAttempts() {
        var contacts = List.of(contact("C1", A, 0, 20));
        var empty = observed(input("before-attempt", 20, contacts), at(-2), 1);
        var first = generator.candidates(empty).stream().filter(a -> a.kind() == PlanningAction.Kind.PREPARE
                && A.equals(a.contextId())).findFirst().orElseThrow();
        var attempt = new PlannedOperation("failed-preparation",
                new ScheduledSecurityAction(PREPARE, at(-2), A, first.keys(), "Attempted preparation"), null, at(-1), Set.of());
        var futureIds = new KeyPairIds("future-unseen-up", "future-unseen-down");
        var futureAttempt = operation("future-unseen-attempt", PREPARE, 10, B, futureIds, null);
        var history = observed(input("failed-preparation-history", 20, contacts, attempt, futureAttempt), at(-1), 1);
        assertThat(history.securityState().keys()).doesNotContainKeys(first.keys().uplink(), first.keys().downlink());
        assertThat(history.issuedKeyIds()).contains(first.keys().uplink(), first.keys().downlink())
                .doesNotContain(futureIds.uplink(), futureIds.downlink());
        assertThat(history.issuedOperationIds()).contains("failed-preparation").doesNotContain("future-unseen-attempt");
        var next = generator.candidates(history).stream().filter(a -> a.kind() == PlanningAction.Kind.PREPARE
                && A.equals(a.contextId())).findFirst().orElseThrow();
        assertThat(next.keys()).isNotEqualTo(first.keys());
        assertThat(generator.candidates(history)).contains(next);
        var branch = branches.create(history);
        var began = branches.execute(branch, next);
        assertThat(began.snapshot().issuedKeyIds()).contains(first.keys().uplink(), first.keys().downlink(),
                next.keys().uplink(), next.keys().downlink());
        assertThat(branch.snapshot().issuedKeyIds()).doesNotContain(next.keys().uplink(), next.keys().downlink());
    }

    @Test void advancingTheBranchNeverRefillsItsOriginalForecast() {
        var observation = observed(input("hidden-third", 85,
                List.of(contact("C1", A, 0, 20), contact("C2", B, 30, 50), contact("C3", A, 80, 85))), at(0), 1);
        var branch = waitUntil(branches.create(observation), at(30));
        assertThat(branch.observation().visibleContacts()).extracting(c -> c.window().id()).containsExactly("C2");
        assertThat(branch.observation().evaluationEnd()).isEqualTo(at(50));
        assertThat(branch.snapshot().scenario().contacts()).extracting(c -> c.window().id()).doesNotContain("C3");
        var terminal = waitUntil(branch, at(50));
        assertThat(generator.candidates(terminal.observation())).isEmpty();
        assertThat(terminal.snapshot().journal()).noneMatch(e -> e.type() == SimulationEvent.Type.SIMULATION_ENDED);
    }

    @Test void aFrameMayFinishAtTheDeadlineAndTheNextDecisionSeesExpiredKeys() {
        var observation = observed(input("expiry", 100, List.of(contact("C1", A, 60, 100)),
                operation("reuse", REUSE, 60, A, null, "C1")), at(60), 1);
        var branch = waitUntil(branches.create(observation), at(61));
        for (int frame = 0; frame < 36; frame++) branch = branches.execute(branch, candidate(branch, PlanningAction.Kind.CONTINUE));
        assertThat(branch.snapshot().at()).isEqualTo(at(70));
        assertThat(branch.snapshot().inFlightFrame()).isNull();
        assertThat(branch.snapshot().securityState().key(OLD_A.uplink()).status()).isEqualTo(KeyStatus.DEACTIVATED);
        assertThat(evaluator.evaluate(branch).evaluation().usefulSecureTime()).isEqualTo(Duration.ofSeconds(9));
        assertThat(generator.candidates(branch.observation())).noneMatch(a -> a.kind() == PlanningAction.Kind.CONTINUE);
        var atDeadline = branch.snapshot().journal().stream().filter(e -> e.at().equals(at(70))).toList();
        assertThat(atDeadline.getFirst().type()).isEqualTo(SimulationEvent.Type.PAYLOAD_TRANSFERRED);
        assertThat(atDeadline).anyMatch(e -> e.type() == SimulationEvent.Type.KEY_EXPIRED);
    }

    @Test void aCounterExhaustingFrameEndsAtADecisionBoundaryBeforeAnyFurtherPayload() {
        var initial = withUplinkCounter(initialState(), Long.MAX_VALUE - 1);
        var payload = new SimulationContact.PayloadProfile(400, 100, 200, 0, 100, 0);
        var input = new SimulationScenario("counter-limit", at(-2), at(20), initial, CONSTRAINTS,
                SecurityCostProfile.syntheticWitness(), List.of(new SimulationContact(window("C1", A, 0, 20), payload)),
                List.of(operation("reuse", REUSE, 0, A, null, "C1")));
        var branch = waitUntil(branches.create(observed(input, at(0), 1)), at(1));
        branch = branches.execute(branch, candidate(branch, PlanningAction.Kind.CONTINUE));
        assertThat(branch.snapshot().at()).isEqualTo(at(1).plusMillis(250));
        assertThat(branch.snapshot().inFlightFrame()).isNull();
        assertThat(branch.snapshot().securityState().key(OLD_A.uplink()).nextNonceCounter()).isEqualTo(Long.MAX_VALUE);
        assertThat(evaluator.evaluate(branch).evaluation().servedContacts()).isEqualTo(1);
        assertThat(generator.candidates(branch.observation())).noneMatch(a -> a.kind() == PlanningAction.Kind.CONTINUE);
        assertThat(branch.snapshot().journal()).noneMatch(e -> e.type() == SimulationEvent.Type.PAYLOAD_REJECTED);
    }

    private PlanningBranch reusableBranch() {
        return branches.create(observed(input("reuse", 20, List.of(contact("C1", A, 0, 20)),
                operation("reuse", REUSE, 0, A, null, "C1")), at(0), 1));
    }

    private PlanningAction candidate(PlanningBranch branch, PlanningAction.Kind kind) {
        return generator.candidates(branch.observation()).stream().filter(a -> a.kind() == kind).findFirst().orElseThrow();
    }

    private PlanningBranch waitUntil(PlanningBranch branch, Instant until) {
        int steps = 0;
        while (branch.snapshot().at().isBefore(until)) {
            var action = generator.candidates(branch.observation()).stream().filter(a -> a.kind() == PlanningAction.Kind.WAIT
                    && !a.until().isAfter(until)).max(Comparator.comparing(PlanningAction::until)).orElseThrow();
            var before = branch.snapshot().at();
            branch = branches.execute(branch, action);
            assertThat(branch.snapshot().at()).isAfter(before).isBeforeOrEqualTo(until);
            assertThat(++steps).isLessThan(1000);
        }
        return branch;
    }

    private PlanningObservation observed(SimulationScenario input, Instant when, int horizon) {
        return PlanningObservation.from(simulation.advance(simulation.start(input), when), horizon);
    }

    private SimulationContact contact(String id, ContextId context, long start, long end) {
        return new SimulationContact(window(id, context, start, end), SimulationContact.PayloadProfile.syntheticWitness());
    }

    private SimulationScenario input(String name, long end, List<SimulationContact> contacts, PlannedOperation... operations) {
        return new SimulationScenario(name, at(-2), at(end), initialState(), CONSTRAINTS,
                SecurityCostProfile.syntheticWitness(), contacts, List.of(operations));
    }

    private PlannedOperation warmup() { return operation("warmup", PREPARE, -2, A, NEW_A, null); }

    private SecurityState withUplinkCounter(SecurityState state, long counter) {
        var keys = new TreeMap<>(state.keys());
        var k = state.key(OLD_A.uplink());
        keys.put(k.id(), new KeyState(k.id(), k.contextId(), k.direction(), k.createdAt(), k.notAfter(), k.activatedAt(),
                k.status(), k.installedOnSpacecraft(), k.deactivatedAt(), k.deactivationReason(), k.destroyedAt(), counter,
                k.lastSuccessfulContactEnd()));
        var contexts = new TreeMap<>(state.contexts());
        var c = state.context(A);
        var sa = c.uplink();
        var up = new SecurityAssociation(sa.id(), sa.contextId(), sa.direction(), sa.status(), sa.keyId(), sa.generation(),
                counter, sa.peerSynchronized());
        contexts.put(A, new SecurityContext(A, up, c.downlink(), c.activeContact(), c.openedAt(),
                c.uplinkFramesInSession(), c.downlinkFramesInSession()));
        return new SecurityState(state.satelliteId(), state.asOf(), contexts, keys);
    }
}
