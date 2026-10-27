package org.contactaware.security.simulation;

import org.contactaware.security.*;
import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import static org.assertj.core.api.Assertions.*;
import static org.contactaware.security.simulation.HorizonWitness.*;
import static org.contactaware.security.simulation.PlanningAction.Kind.*;

class FeasibleSeedPlanBuilderTest {
    private final SimulationEngine simulation = new SimulationEngine();
    private final BranchEngine branches = new BranchEngine();
    private final SecurityActionGenerator generator = new SecurityActionGenerator();

    @Test void bothRulesBootstrapAndDeliverFiniteDemandWithoutMutatingActualExecution() {
        var actual = simulation.start(unkeyed(List.of()));
        var observation = PlanningObservation.from(actual, 1);
        for (var rule : PlanTieBreak.values()) {
            var seed = new FeasibleSeedPlanBuilder().build(observation, rule);
            assertThat(seed.completed()).isTrue();
            assertThat(seed.terminal().snapshot().at()).isEqualTo(observation.evaluationEnd());
            assertThat(seed.result().evaluation().servedContacts()).isEqualTo(1);
            assertThat(seed.result().evaluation().newKeys()).isEqualTo(2);
            assertThat(seed.result().evaluation().managementBytes()).isPositive();
            assertThat(seed.result().evaluation().usefulSecureTime()).isEqualTo(Duration.ofSeconds(1));
            assertThat(seed.result().plan()).filteredOn(a -> a.kind() == PREPARE).hasSize(1);
            assertThat(seed.result().plan()).filteredOn(a -> a.kind() == CONTINUE).hasSize(4);
            int lastFrame = -1;
            for (int i = 0; i < seed.result().plan().size(); i++)
                if (seed.result().plan().get(i).kind() == CONTINUE) lastFrame = i;
            assertThat(seed.result().plan().subList(lastFrame + 1, seed.result().plan().size()))
                    .allMatch(a -> a.kind() == WAIT);
            assertThat(seed.terminal().snapshot().payloadProgress().get("finite"))
                    .isEqualTo(new ExecutionSnapshot.PayloadProgress(200, 200, true));
            assertReplay(observation, seed);
            assertThat(new FeasibleSeedPlanBuilder().build(observation, rule)).isEqualTo(seed);
        }
        assertThat(PlanningObservation.from(actual, 1)).isEqualTo(observation);
        assertThat(actual.securityState().keys()).isEmpty();
        assertThat(actual.payloadProgress()).isEmpty();
        assertThat(actual.issuedKeyIds()).isEmpty();
        assertThat(actual.createdKeys()).isZero();
    }

    @Test void completesAnExistingPrefixWithoutChangingItsOriginOrChargingItsTransitionsTwice() {
        var observation = PlanningObservation.from(simulation.start(unkeyed(List.of())), 1);
        var root = branches.create(observation);
        var prepare = generator.candidates(root.observation()).stream().filter(a -> a.kind() == PREPARE).findFirst().orElseThrow();
        var prefix = branches.execute(root, prepare);
        for (var rule : PlanTieBreak.values()) {
            var complete = new FeasibleSeedPlanBuilder().complete(prefix, rule);
            var built = new FeasibleSeedPlanBuilder().build(observation, rule);
            assertThat(complete.completed()).isTrue();
            assertThat(complete.result()).isEqualTo(built.result());
            assertThat(complete.terminal()).isEqualTo(built.terminal());
            assertThat(complete.transitions()).isEqualTo(built.transitions() - 1);
            assertThat(prefix.plan()).containsExactly(prepare);
            assertThat(prefix.snapshot().at()).isEqualTo(observation.at());
        }
    }

    @Test void waitsForInheritedPreparationWithoutIssuingAnotherPair() {
        var source = unkeyed(List.of(operation("warmup", SecurityAction.PREPARE, -2, A, NEW_A, null)));
        var actual = simulation.advance(simulation.start(source), at(-1));
        var observation = PlanningObservation.from(actual, 1);
        assertThat(observation.ongoingOperations()).hasSize(1);
        for (var rule : PlanTieBreak.values()) {
            var seed = new FeasibleSeedPlanBuilder().build(observation, rule);
            assertThat(seed.completed()).isTrue();
            assertThat(seed.result().plan().getFirst().kind()).isEqualTo(WAIT);
            assertThat(seed.result().plan()).noneMatch(a -> a.kind() == PREPARE);
            assertThat(seed.result().evaluation().servedContacts()).isEqualTo(1);
            assertThat(seed.terminal().snapshot().securityState().keys()).containsOnlyKeys(NEW_A.uplink(), NEW_A.downlink());
            assertReplay(observation, seed);
        }
        assertThat(actual.pendingOperations()).hasSize(1);
        assertThat(actual.createdKeys()).isZero();
    }

    @Test void waitsForAnInheritedFrameAndDoesNotRestartItOrEnableAutomaticFrames() {
        var payload = new SimulationContact.PayloadProfile(400, 100, 200, 200, 100, 100);
        var source = new SimulationScenario("seed-frame", at(0), at(6), initialState(), CONSTRAINTS,
                SecurityCostProfile.syntheticWitness(), List.of(new SimulationContact(window("finite", A, 0, 6), payload)),
                List.of(operation("open", SecurityAction.OPEN, 0, A, null, "finite")));
        var actual = simulation.advance(simulation.start(source), at(1).plusMillis(125));
        var observation = PlanningObservation.from(actual, 1);
        assertThat(observation.inFlightFrame()).isNotNull();
        for (var rule : PlanTieBreak.values()) {
            var seed = new FeasibleSeedPlanBuilder().build(observation, rule);
            assertThat(seed.completed()).isTrue();
            assertThat(seed.result().plan().getFirst().kind()).isEqualTo(WAIT);
            assertThat(seed.result().plan()).filteredOn(a -> a.kind() == CONTINUE).hasSize(3);
            assertThat(seed.result().plan()).noneMatch(a -> a.kind() == PREPARE);
            assertThat(seed.terminal().snapshot().journal()).filteredOn(e -> e.type() == SimulationEvent.Type.PAYLOAD_TRANSFERRED)
                    .hasSize(4);
            assertThat(seed.terminal().snapshot().payloadProgress().get("finite"))
                    .isEqualTo(new ExecutionSnapshot.PayloadProgress(200, 200, true));
            assertReplay(observation, seed);
        }
        assertThat(actual.inFlightFrame()).isEqualTo(observation.inFlightFrame());
    }

    @Test void exhaustedFiniteDemandNeverStartsPreparationEvenWhenItsCandidateExists() {
        var source = new RecedingHorizonControllerTest().keyed(8,
                List.of(operation("open", SecurityAction.OPEN, 0, A, null, "C1")));
        var actual = simulation.advance(simulation.start(source), at(5));
        var observation = PlanningObservation.from(actual, 1);
        // Use the ordinary short PREPARE cost so a gratuitous renewal is a genuine choice.
        observation = withCosts(observation, SecurityCostProfile.syntheticWitness());
        assertThat(generator.candidates(observation)).anyMatch(a -> a.kind() == PREPARE);
        for (var rule : PlanTieBreak.values()) {
            var seed = new FeasibleSeedPlanBuilder().build(observation, rule);
            assertThat(seed.completed()).isTrue();
            assertThat(seed.result().plan()).allMatch(a -> a.kind() == WAIT);
            assertThat(seed.result().evaluation()).isEqualTo(new BranchEvaluation(0, Duration.ZERO, Duration.ZERO, 0, 0));
            assertThat(seed.terminal().snapshot().at()).isEqualTo(observation.evaluationEnd());
        }
    }

    @Test void boundedFailureNeverExposesAPartialScoreOrTerminalAndCancellationPropagates() {
        var observation = PlanningObservation.from(simulation.start(unkeyed(List.of())), 1);
        var limited = new FeasibleSeedPlanBuilder(1).build(observation, PlanTieBreak.CANONICAL);
        assertThat(limited.completed()).isFalse();
        assertThat(limited.result()).isNull();
        assertThat(limited.terminal()).isNull();
        assertThat(limited.transitions()).isEqualTo(1);
        try (var scope = SearchControl.open(Duration.ZERO, SearchDiagnostics.StopReason.CONTROLLER_TIME_LIMIT)) {
            scope.cancel();
            assertThatThrownBy(() -> new FeasibleSeedPlanBuilder().build(observation, PlanTieBreak.CANONICAL))
                    .isInstanceOf(SearchControl.Stopped.class).hasMessageContaining("CANCELLED");
        }
    }

    @Test void anEmptyVisibleHorizonIsAlreadyATerminalZeroTransitionSeed() {
        var observation = PlanningObservation.from(simulation.start(unkeyed(List.of())), 0);
        var seed = new FeasibleSeedPlanBuilder(0).build(observation, PlanTieBreak.CONTINUE_WAIT);
        assertThat(seed.completed()).isTrue();
        assertThat(seed.transitions()).isZero();
        assertThat(seed.result().plan()).isEmpty();
        assertThat(seed.result().evaluation()).isEqualTo(new BranchEvaluation(0, Duration.ZERO, Duration.ZERO, 0, 0));
    }

    @Test void interruptedTransitionAttemptIsCountedAndTheNextInvocationResetsTheCount() {
        var observation = PlanningObservation.from(simulation.start(unkeyed(List.of())), 1);
        var builder = new FeasibleSeedPlanBuilder();
        var clock = new AtomicLong();
        try (var scope = SearchControl.open(Duration.ofNanos(1), SearchDiagnostics.StopReason.TIME_LIMIT,
                clock::get, phase -> { if (phase.equals("branch.execute")) clock.incrementAndGet(); })) {
            assertThatThrownBy(() -> builder.build(observation, PlanTieBreak.CANONICAL))
                    .isInstanceOf(SearchControl.Stopped.class).hasMessageContaining("TIME_LIMIT");
        }
        assertThat(builder.transitions()).isEqualTo(1);
        var seed = builder.build(observation, PlanTieBreak.CANONICAL);
        assertThat(builder.transitions()).isEqualTo(seed.transitions());
        try (var scope = SearchControl.open(Duration.ZERO, SearchDiagnostics.StopReason.CONTROLLER_TIME_LIMIT)) {
            scope.cancel();
            assertThatThrownBy(() -> builder.build(observation, PlanTieBreak.CANONICAL))
                    .isInstanceOf(SearchControl.Stopped.class);
        }
        assertThat(builder.transitions()).isZero();
    }

    private SimulationScenario unkeyed(List<PlannedOperation> plan) {
        var state = new LifecycleTransitions(CONSTRAINTS).createContext(SecurityState.empty(SATELLITE, at(-2)),
                A, "seed-up-SA", "seed-down-SA", at(-2));
        var payload = new SimulationContact.PayloadProfile(400, 100, 200, 200, 100, 100);
        return new SimulationScenario("seed-unkeyed", at(-2), at(20), state, CONSTRAINTS,
                SecurityCostProfile.syntheticWitness(), List.of(new SimulationContact(window("finite", A, 0, 20), payload)), plan);
    }

    private void assertReplay(PlanningObservation origin, FeasibleSeedPlanBuilder.Result result) {
        var replay = branches.create(origin);
        for (var action : result.result().plan()) {
            assertThat(generator.candidates(replay.observation())).contains(action);
            replay = branches.execute(replay, action);
        }
        assertThat(replay).isEqualTo(result.terminal());
        assertThat(new ContinuationEvaluator().evaluate(replay)).isEqualTo(result.result());
        assertThat(result.transitions()).isEqualTo(result.result().plan().size());
        assertThat(replay.snapshot().journal()).noneMatch(e -> e.type() == SimulationEvent.Type.ACTION_FAILED
                || e.type() == SimulationEvent.Type.PAYLOAD_REJECTED);
    }

    private PlanningObservation withCosts(PlanningObservation o, SecurityCostProfile costs) {
        return new PlanningObservation(o.at(), o.horizon(), o.currentContact(), o.visibleContacts(), o.evaluationEnd(),
                o.securityState(), o.constraints(), costs, o.ongoingOperations(), o.resourceReservations(),
                o.uncertainContexts(), o.payloadProgress(), o.inFlightFrame(), o.recordedCosts(), o.accruedCosts(),
                o.usefulSecureTime(), o.createdKeys(), o.wakeUps(), o.issuedKeyIds(), o.issuedOperationIds());
    }
}
