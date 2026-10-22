package org.contactaware.security.simulation;

import org.contactaware.security.*;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.contactaware.security.simulation.HorizonWitness.*;
import static org.contactaware.security.simulation.PlanningAction.Kind.*;

class SecurityActionGeneratorTest {
    private final SecurityActionGenerator generator = new SecurityActionGenerator();
    private final SimulationEngine simulator = new SimulationEngine();

    @Test void preparedAndInstalledCandidatesOfferDifferentActionsAndOccupyTheSpareSlot() {
        var scenario = input(List.of(contact("C1", A, 0, 20)),
                operation("prepare", SecurityAction.PREPARE, -2, A, NEW_A, null),
                operation("install", SecurityAction.INSTALL, 1, A, NEW_A, "C1"));
        var prepared = observe(scenario, at(0), 1);
        assertThat(actions(prepared, A)).extracting(PlanningAction::kind).contains(INSTALL, OPEN, REUSE).doesNotContain(PREPARE, SWITCH);
        assertThat(actions(prepared, B)).extracting(PlanningAction::kind).containsExactly(PREPARE);
        var installed = observe(scenario, at(5), 1);
        assertThat(actions(installed, A)).extracting(PlanningAction::kind).contains(SWITCH, OPEN, REUSE).doesNotContain(INSTALL, PREPARE);
    }

    @Test void anOnlineCommitAtContactEndOrKeyDeadlineIsNotACandidate() {
        var shortContact = input(List.of(contact("C1", A, 0, 4)), operation("prepare", SecurityAction.PREPARE, -2, A, NEW_A, null));
        assertThat(actions(observe(shortContact, at(0), 1), A)).extracting(PlanningAction::kind).doesNotContain(INSTALL).contains(OPEN, REUSE);
        var expiry = input(List.of(contact("C1", A, 60, 100)));
        assertThat(actions(observe(expiry, at(69), 1), A)).extracting(PlanningAction::kind).doesNotContain(OPEN, REUSE);
        assertThat(actions(observe(expiry, at(68), 1), A)).extracting(PlanningAction::kind).contains(OPEN, REUSE);
    }

    @Test void groundPreparationHonorsResourcePhasesButCanOverlapAnotherNodesWork() {
        var input = input(List.of(contact("C1", A, 0, 20)), operation("reuse", SecurityAction.REUSE, 0, A, null, "C1"));
        var groundBusy = observe(input, at(0).plusMillis(10), 1);
        assertThat(actions(groundBusy, A)).extracting(PlanningAction::kind).doesNotContain(PREPARE);
        assertThat(actions(groundBusy, B)).extracting(PlanningAction::kind).contains(PREPARE);
        var groundFree = observe(input, at(0).plusMillis(30), 1);
        assertThat(actions(groundFree, A)).extracting(PlanningAction::kind).contains(PREPARE).doesNotContain(OPEN, REUSE);
    }

    @Test void pendingPreparationOccupiesTheSpareSlotEvenBeforeMaterialExists() {
        var input = input(List.of(contact("C1", A, 0, 20)), operation("prepare", SecurityAction.PREPARE, -2, A, NEW_A, null));
        var inProgress = observe(input, at(-1), 1);
        assertThat(inProgress.securityState().keys()).doesNotContainKey(NEW_A.uplink());
        assertThat(actions(inProgress, A)).extracting(PlanningAction::kind).doesNotContain(PREPARE);
        assertThat(actions(inProgress, B)).extracting(PlanningAction::kind).contains(PREPARE);
    }

    @Test void groundPreparationCanStraddleLosWhileAnAdmittedFrameContinues() {
        var input = input(List.of(contact("C1", A, 0, 20), contact("C2", B, 30, 50)),
                operation("reuse", SecurityAction.REUSE, 0, A, null, "C1"));
        var observed = observe(input, at(19), 1);
        assertThat(observed.inFlightFrame()).isNotNull();
        var prepareB = actions(observed, B).stream().filter(a -> a.kind() == PREPARE).findFirst().orElseThrow();
        assertThat(prepareB.until()).isEqualTo(at(21));
        assertThat(actions(observed, A)).extracting(PlanningAction::kind).doesNotContain(INSTALL, SWITCH, OPEN, REUSE, CONTINUE);
    }

    @Test void failedPreparationStillAdvancesTheNextCanonicalGeneration() {
        var contacts = List.of(contact("C1", A, 0, 20));
        var original = observe(input(contacts), at(-2), 1);
        var first = actions(original, A).stream().filter(a -> a.kind() == PREPARE).findFirst().orElseThrow();
        var failed = new PlannedOperation("prepare-failed", new ScheduledSecurityAction(SecurityAction.PREPARE, at(-2), A,
                first.keys(), "A real attempted preparation"), null, at(-1), Set.of());
        var later = observe(input(contacts, failed), at(-1), 1);
        assertThat(later.securityState().keys()).doesNotContainKeys(first.keys().uplink(), first.keys().downlink());
        assertThat(later.issuedKeyIds()).contains(first.keys().uplink(), first.keys().downlink());
        var next = actions(later, A).stream().filter(a -> a.kind() == PREPARE).findFirst().orElseThrow();
        assertThat(next.keys()).isNotEqualTo(first.keys());
        assertThat(next.keys().uplink()).endsWith("g2:up");
    }

    @Test void hiddenScheduleAndUnobservedFutureFailureDoNotChangeCandidates() {
        var first = input(List.of(contact("C1", A, 0, 20), contact("C2", B, 30, 50), contact("C3", A, 80, 85)));
        var future = new PlannedOperation("hidden-failure", new ScheduledSecurityAction(SecurityAction.REUSE, at(90), B, null,
                "Hidden future plan"), "C3", at(90).plusMillis(500), Set.of(SecurityAction.REUSE));
        var second = input(List.of(contact("C1", A, 0, 20), contact("C2", B, 30, 50), contact("C3", B, 90, 95)), future);
        var left = observe(first, at(0), 1);
        var right = observe(second, at(0), 1);
        assertThat(left).isEqualTo(right);
        assertThat(generator.candidates(left)).isEqualTo(generator.candidates(right));
        var originalCandidates = generator.candidates(left);
        generator.candidates(observe(second, at(30), 1));
        assertThat(generator.candidates(left)).isEqualTo(originalCandidates);
    }

    @Test void waitsIncludeJitPreparationAndOnlyPermittedBoundaries() {
        var input = input(List.of(contact("C1", A, 0, 20), contact("C2", B, 30, 50), contact("C3", A, 80, 85)));
        var gap = observe(input, at(20), 1);
        assertThat(generator.candidates(gap).stream().filter(a -> a.kind() == WAIT).map(PlanningAction::until))
                .containsExactly(at(28), at(30), at(50));
        assertThat(generator.candidates(observe(input, at(85), 1))).isEmpty();
        assertThat(generator.candidates(observe(input, at(20), 0))).isEmpty();
    }

    @Test void canonicalAllocationIsUnambiguousMonotoneAndSideEffectFree() {
        var first = KeyGenerationIds.next(A, Set.of(), Set.of());
        assertThat(KeyGenerationIds.next(A, Set.of(), Set.of())).isEqualTo(first);
        var partialIssued = KeyGenerationIds.next(A, Set.of(first.uplink()), Set.of());
        assertThat(partialIssued.uplink()).endsWith("g2:up");
        var blocked = KeyGenerationIds.next(A, Set.of(), Set.of(first.downlink()));
        assertThat(blocked).isEqualTo(partialIssued);
        assertThat(KeyGenerationIds.next(new ContextId("a:b", "c"), Set.of(), Set.of()))
                .isNotEqualTo(KeyGenerationIds.next(new ContextId("a", "b:c"), Set.of(), Set.of()));
    }

    @Test void continueIsOfferedBeforeEachNewFrameIncludingTheLastFrameEndingAtLos() {
        var input = input(List.of(contact("C1", A, 0, 4)), operation("reuse", SecurityAction.REUSE, 0, A, null, "C1"));
        var branches = new BranchEngine();
        var branch = branches.create(observe(input, at(0), 1));
        var waitForOpen = generator.candidates(branch.observation()).stream()
                .filter(a -> a.kind() == WAIT && a.until().equals(at(1))).findFirst().orElseThrow();
        branch = branches.execute(branch, waitForOpen);
        assertThat(branch.snapshot().inFlightFrame()).isNull();
        for (int frame = 0; frame < 12; frame++) {
            var current = branch.observation();
            var keepSending = generator.candidates(current).stream().filter(a -> a.kind() == CONTINUE).findFirst().orElseThrow();
            assertThat(keepSending.at()).isEqualTo(at(1).plusMillis(frame * 250L));
            assertThat(keepSending.until()).isEqualTo(at(1).plusMillis((frame + 1) * 250L));
            assertThat(generator.candidates(current)).anyMatch(a -> a.kind() == WAIT);
            branch = branches.execute(branch, keepSending);
            assertThat(branch.snapshot().inFlightFrame()).isNull();
        }
        assertThat(branch.snapshot().at()).isEqualTo(at(4));
        assertThat(generator.candidates(branch.observation())).isEmpty();
    }

    @Test void actionsRejectAmbiguousTargetsAndCandidatesAreImmutable() {
        assertThatThrownBy(() -> new PlanningAction(WAIT, at(0), at(0), null, null, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PlanningAction(PREPARE, at(0), at(2), A, NEW_A, "C1")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PlanningAction(CONTINUE, at(0), at(1), A, NEW_A, "C1")).isInstanceOf(IllegalArgumentException.class);
        var candidates = generator.candidates(observe(input(List.of(contact("C1", A, 0, 20))), at(0), 1));
        assertThatThrownBy(candidates::clear).isInstanceOf(UnsupportedOperationException.class);
        assertThat(new PlanningAction(PREPARE, at(0), at(2), A, NEW_A, null).securityAction()).isEqualTo(SecurityAction.PREPARE);
    }

    private List<PlanningAction> actions(PlanningObservation observation, ContextId context) {
        return generator.candidates(observation).stream().filter(a -> context.equals(a.contextId())).toList();
    }
    private PlanningObservation observe(SimulationScenario input, Instant at, int horizon) {
        return PlanningObservation.from(simulator.advance(simulator.start(input), at), horizon);
    }
    private SimulationContact contact(String id, ContextId context, long start, long end) {
        return new SimulationContact(window(id, context, start, end), SimulationContact.PayloadProfile.syntheticWitness());
    }
    private SimulationScenario input(List<SimulationContact> contacts, PlannedOperation... operations) {
        return new SimulationScenario("candidate-fixture", at(-2), at(100), initialState(), CONSTRAINTS,
                SecurityCostProfile.syntheticWitness(), contacts, List.of(operations));
    }
}
