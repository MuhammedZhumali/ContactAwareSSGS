package org.contactaware.security.simulation;

import org.contactaware.security.*;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.contactaware.security.simulation.HorizonWitness.*;

class ContinuationEvaluatorTest {
    private final SimulationEngine engine = new SimulationEngine();
    private final BranchEngine branches = new BranchEngine();
    private final ContinuationEvaluator evaluator = new ContinuationEvaluator();

    @Test void creationHasZeroDeltaEvenWithCompletedWarmupAndPartiallyConsumedInstallation() {
        var branch = branch(Sequence.X, Plan.MULTI, at(2));
        assertThat(branch.origin().recordedCosts().totalCpu()).isEqualTo(Duration.ofMillis(200));
        assertThat(branch.origin().accruedCosts().totalCpu()).isEqualTo(Duration.ofMillis(500));
        assertThat(branch.origin().accruedCosts().managementBytes()).isEqualTo(680);
        assertThat(evaluator.evaluate(branch).evaluation())
                .isEqualTo(new BranchEvaluation(0, Duration.ZERO, Duration.ZERO, 0, 0));
        assertThat(branch.snapshot().journal()).isEmpty();
    }

    @Test void publicOverloadRejectsPrivilegedFullRunSnapshotsInsteadOfScoringTheirFuturePlan() {
        var fullRun = engine.advance(engine.start(scenario(Sequence.X, Plan.MULTI)), at(2));
        var observed = PlanningObservation.from(fullRun, 2);
        assertThatThrownBy(() -> evaluator.evaluate(observed, fullRun)).isInstanceOf(IllegalArgumentException.class);
        assertThat(evaluator.evaluate(branches.create(observed)).evaluation())
                .isEqualTo(new BranchEvaluation(0, Duration.ZERO, Duration.ZERO, 0, 0));
    }

    @Test void completionChargesOnlyRemainingAccruedWorkNotTheAlreadyRecordedOrPartialCost() {
        var branch = branch(Sequence.X, Plan.MULTI, at(2));
        var completedInstall = waitUntil(branch, at(4));
        var value = evaluator.evaluate(completedInstall).evaluation();
        assertThat(value).isEqualTo(new BranchEvaluation(0, Duration.ZERO, Duration.ZERO, 120, 0));
        assertThat(completedInstall.snapshot().securityState().key(NEW_A.uplink()).installedOnBothPeers()).isTrue();
        var completedSwitch = waitUntil(completedInstall, at(5));
        assertThat(evaluator.evaluate(completedSwitch).evaluation())
                .isEqualTo(new BranchEvaluation(0, Duration.ZERO, Duration.ofMillis(150), 320, 0));
        assertThat(evaluator.evaluate(branch).evaluation().managementBytes()).isZero();
    }

    @Test void currentContactUsesPastDeliveryToMeetThresholdAndIsCountedOnlyOnce() {
        var branch = branch(Sequence.Y, Plan.NEXT, at(2).plusMillis(750));
        assertThat(branch.origin().payloadProgress().get("C1").uplinkBytes()).isEqualTo(400);
        assertThat(branch.origin().payloadProgress().get("C1").downlinkBytes()).isEqualTo(300);
        var served = waitUntil(branch, at(3)); // Existing eighth frame completes despite WAIT.
        assertThat(evaluator.evaluate(served).evaluation())
                .isEqualTo(new BranchEvaluation(1, Duration.ofMillis(250), Duration.ZERO, 0, 0));
        var nextFrame = new SecurityActionGenerator().candidates(served.observation()).stream()
                .filter(a -> a.kind() == PlanningAction.Kind.CONTINUE).findFirst().orElseThrow();
        var more = branches.execute(served, nextFrame);
        assertThat(evaluator.evaluate(more).evaluation())
                .isEqualTo(new BranchEvaluation(1, Duration.ofMillis(500), Duration.ZERO, 0, 0));
    }

    @Test void aPreviouslyServedContactIsNotAwardedAgain() {
        var branch = branch(Sequence.Y, Plan.NEXT, at(3));
        var afterCommittedFrame = waitUntil(branch, at(3).plusMillis(250));
        assertThat(evaluator.evaluate(afterCommittedFrame).evaluation())
                .isEqualTo(new BranchEvaluation(0, Duration.ofMillis(250), Duration.ZERO, 0, 0));
    }

    @Test void cumulativeRadioRoundingIsSubtractedAtCutoffsInsteadOfRoundingEachDelta() {
        var branch = branch(Sequence.X, Plan.MULTI, at(0).plusMillis(102));
        // INSTALL radio phase has emitted floor(2 ms * 0.4 B/ms) = 0 bytes so far.
        var atThreeMilliseconds = new SimulationEngine().advance(branch.snapshot(), at(0).plusMillis(103));
        var value = evaluator.evaluate(branch.origin(), atThreeMilliseconds);
        assertThat(value.managementBytes()).isEqualTo(1);
        assertThat(value.controlCpu()).isZero();
        var further = new SimulationEngine().advance(atThreeMilliseconds, at(0).plusMillis(104));
        assertThat(evaluator.evaluate(branch.origin(), further).managementBytes()).isEqualTo(1);
    }

    @Test void newCompletedPreparationIsCountedEvenWhenItRemainsUnused() {
        var scenario = new SimulationScenario("prepare-evaluation", at(-2), at(20), initialState(), CONSTRAINTS,
                SecurityCostProfile.syntheticWitness(), List.of(new SimulationContact(window("C1", A, 0, 20),
                SimulationContact.PayloadProfile.syntheticWitness())), List.of());
        var observation = PlanningObservation.from(engine.start(scenario), 1);
        var branch = branches.create(observation);
        var prepare = new SecurityActionGenerator().candidates(branch.observation()).stream()
                .filter(a -> a.kind() == PlanningAction.Kind.PREPARE && a.contextId().equals(A)).findFirst().orElseThrow();
        var after = branches.execute(branch, prepare);
        while (after.snapshot().at().isBefore(at(0))) after = waitUntil(after, at(0));
        var value = evaluator.evaluate(after).evaluation();
        assertThat(value.newKeys()).isEqualTo(2);
        assertThat(value.controlCpu()).isEqualTo(Duration.ofMillis(200));
        assertThat(value.servedContacts()).isZero();
        assertThat(value.usefulSecureTime()).isZero();
    }

    @Test void constraintFailureInInheritedOperationMakesContinuationInadmissible() {
        var constraints = new SecurityConstraints(CONSTRAINTS.cryptoperiod(), Duration.ofSeconds(3),
                CONSTRAINTS.maxReuseGap(), CONSTRAINTS.authorizedContexts(), Set.of());
        var input = new SimulationScenario("known-expiry", at(-2), at(20), initialState(), constraints,
                SecurityCostProfile.syntheticWitness(), List.of(new SimulationContact(window("C1", A, 0, 20),
                SimulationContact.PayloadProfile.syntheticWitness())), List.of(
                operation("warmup", SecurityAction.PREPARE, -2, A, NEW_A, null),
                operation("install", SecurityAction.INSTALL, 0, A, NEW_A, "C1")));
        var observation = PlanningObservation.from(engine.advance(engine.start(input), at(2)), 1);
        var failed = waitUntil(branches.create(observation), at(3));
        assertThat(failed.snapshot().journal()).anyMatch(e -> e.type() == SimulationEvent.Type.ACTION_FAILED);
        assertThatThrownBy(() -> evaluator.evaluate(failed)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Inadmissible");
    }

    private PlanningBranch branch(Sequence sequence, Plan plan, Instant at) {
        var snapshot = engine.advance(engine.start(scenario(sequence, plan)), at);
        return branches.create(PlanningObservation.from(snapshot, 1));
    }

    private PlanningBranch waitUntil(PlanningBranch branch, Instant until) {
        return branches.execute(branch, new PlanningAction(PlanningAction.Kind.WAIT, branch.snapshot().at(), until, null, null, null));
    }
}
