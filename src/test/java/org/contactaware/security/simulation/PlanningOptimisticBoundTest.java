package org.contactaware.security.simulation;

import org.contactaware.security.*;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.contactaware.security.simulation.HorizonWitness.*;

/** Exhaustive descendant oracle; no pruning or memoization is used to validate a bound. */
class PlanningOptimisticBoundTest {
    private final BranchEngine engine = new BranchEngine();
    private final SecurityActionGenerator generator = new SecurityActionGenerator();
    private final ContinuationEvaluator evaluator = new ContinuationEvaluator();
    private final PlanningResultComparator comparator = new PlanningResultComparator();
    private final PlanningOptimisticBound bound = new PlanningOptimisticBound();

    @Test void everyFreshPreparationAndBidirectionalContinuationStaysBelowItsAncestorBound() {
        var observation = fresh(false);
        validateEveryNode(observation);
        var result = compareSearches(observation);
        assertThat(result.score()).isEqualTo(new BranchEvaluation(1, Duration.ofSeconds(2), Duration.ofSeconds(4), 0, 2));
        assertThat(result.firstAction().kind()).isEqualTo(PlanningAction.Kind.PREPARE);
    }

    @Test void openReuseAlternativesAndUnequalCostsCannotInvalidateTheBound() {
        var observation = keyed(3, false, true);
        validateEveryNode(observation);
        var result = compareSearches(observation);
        assertThat(result.firstAction().kind()).isEqualTo(PlanningAction.Kind.REUSE);
        assertThat(result.score()).isEqualTo(new BranchEvaluation(1, Duration.ofSeconds(2), Duration.ofMillis(500), 0, 0));
    }

    @Test void partialInFlightFrameContributesItsFullUsefulDurationOnCompletion() {
        var branch = opened(keyed(3, false, false));
        var snapshot = new SimulationEngine().planningAdvance(branch.snapshot(), at(1).plusMillis(500), true);
        assertThat(snapshot.inFlightFrame()).isNotNull();
        var observation = PlanningObservation.from(snapshot, 1);
        validateEveryNode(observation);
        var result = compareSearches(observation);
        // Only 1.5 seconds of wall time remain, but the already-started frame still contributes
        // its full one-second duration at completion, followed by a second complete frame.
        assertThat(result.score()).isEqualTo(new BranchEvaluation(1, Duration.ofSeconds(2), Duration.ZERO, 0, 0));
    }

    @Test void partiallyExecutedOnlineOperationPreservesItsRemainingCostAndBlockingInterval() {
        var origin = fresh(true);
        var branch = engine.create(origin);
        branch = engine.execute(branch, action(branch, PlanningAction.Kind.INSTALL));
        var snapshot = new SimulationEngine().advance(branch.snapshot(), at(0).plusMillis(500));
        assertThat(snapshot.pendingOperations()).hasSize(1);
        var observation = PlanningObservation.from(snapshot, 1);
        validateEveryNode(observation);
        var result = compareSearches(observation);
        assertThat(result.score()).isEqualTo(new BranchEvaluation(1, Duration.ofSeconds(2), Duration.ofMillis(2500), 0, 0));
    }

    @Test void alreadyServedContactProgressIsNotCountedAgainByTheBoundOrSearch() {
        var branch = opened(keyed(4, false, false));
        branch = engine.execute(branch, action(branch, PlanningAction.Kind.CONTINUE));
        branch = engine.execute(branch, action(branch, PlanningAction.Kind.CONTINUE));
        assertThat(evaluator.evaluate(branch).evaluation().servedContacts()).isEqualTo(1);
        var observation = branch.observation();
        validateEveryNode(observation);
        var result = compareSearches(observation);
        assertThat(result.score()).isEqualTo(new BranchEvaluation(0, Duration.ofSeconds(1), Duration.ZERO, 0, 0));
    }

    @Test void expiryInsideTheContactCannotMakeAnyFeasibleDescendantBeatItsBound() {
        var observation = keyed(3, true, false);
        validateEveryNode(observation);
        var result = compareSearches(observation);
        // At t=2 the first full frame completes, then the key expires; downlink cannot follow.
        assertThat(result.score()).isEqualTo(new BranchEvaluation(0, Duration.ofSeconds(1), Duration.ofSeconds(1), 0, 0));
    }

    @Test void zeroMinimumInOneDirectionDoesNotRequireAnUnrequestedFrame() {
        var source = keyed(3, false, false);
        var scenario = new SimulationScenario("bound-uplink-only", at(0), at(2), source.securityState(),
                source.constraints(), source.costs(), List.of(new SimulationContact(window("C1", A, 0, 2),
                new SimulationContact.PayloadProfile(100, 100, 100, 0, 100, 0))), List.of());
        var observation = PlanningObservation.from(new SimulationEngine().start(scenario), 1);
        validateEveryNode(observation);
        assertThat(compareSearches(observation).score())
                .isEqualTo(new BranchEvaluation(1, Duration.ofSeconds(1), Duration.ofSeconds(1), 0, 0));
    }

    @Test void insufficientOfferedPayloadCannotBeCountedAsPotentiallyServed() {
        var source = keyed(3, false, false);
        var scenario = new SimulationScenario("bound-insufficient-demand", at(0), at(3), source.securityState(),
                source.constraints(), source.costs(), List.of(new SimulationContact(window("C1", A, 0, 3),
                new SimulationContact.PayloadProfile(100, 100, 100, 100, 200, 100))), List.of());
        var observation = PlanningObservation.from(new SimulationEngine().start(scenario), 1);
        validateEveryNode(observation);
        assertThat(compareSearches(observation).score())
                .isEqualTo(new BranchEvaluation(0, Duration.ofSeconds(2), Duration.ofSeconds(1), 0, 0));
    }

    @Test void originalWitnessRootBoundsRespectMandatoryOpeningAndEarlyRenewalCosts() {
        var nextX = HorizonPlannerMain.observation(Sequence.X, 1);
        var multiX = HorizonPlannerMain.observation(Sequence.X, 2);
        var multiY = HorizonPlannerMain.observation(Sequence.Y, 2);
        assertThat(bound.upperBound(engine.create(nextX)))
                .isEqualTo(new BranchEvaluation(2, Duration.ofSeconds(38), Duration.ofMillis(120), 160, 0));
        assertThat(bound.upperBound(engine.create(multiX)))
                .isEqualTo(new BranchEvaluation(3, Duration.ofSeconds(37), Duration.ofMillis(630), 1240, 0));
        assertThat(bound.upperBound(engine.create(multiY)))
                .isEqualTo(new BranchEvaluation(3, Duration.ofSeconds(42), Duration.ofMillis(180), 240, 0));
    }

    @Test void earlyInstallationUsesOneSharedTimePoolInsteadOfChargingTheLaterContactAgain() {
        var transitions = new LifecycleTransitions(CONSTRAINTS);
        var state = transitions.createContext(SecurityState.empty(SATELLITE, at(-150)), A, "stage-up", "stage-down", at(-150));
        state = transitions.prepareKeys(state, A, OLD_A, at(-150));
        var past = window("history", A, -144, -142);
        state = transitions.confirmInstallation(state, A, OLD_A, past, at(-144));
        state = transitions.activateKeys(state, A, OLD_A, past, at(-144)); // Old material expires at t=6.
        state = transitions.bindKeys(state, A, OLD_A, past, at(-144));
        state = transitions.startContext(state, A, past, at(-144));
        state = transitions.recordProtectedTraffic(state, A, at(-143), at(-142), 1, 1);
        state = transitions.stopContext(state, A, at(-142));
        state = transitions.prepareKeys(state, A, NEW_A, at(-1));
        var payload = new SimulationContact.PayloadProfile(100, 100, 100, 0, 100, 0);
        var scenario = new SimulationScenario("bound-early-stage-two-contacts", at(0), at(8), state, CONSTRAINTS,
                profile(100, false), List.of(new SimulationContact(window("C1", A, 0, 4), payload),
                new SimulationContact(window("C2", A, 6, 8), payload)), List.of());
        var observation = PlanningObservation.from(new SimulationEngine().start(scenario), 1);
        var expected = new BranchEvaluation(2, Duration.ofSeconds(2), Duration.ofSeconds(4), 0, 0);
        assertThat(bound.upperBound(engine.create(observation))).isEqualTo(expected);
        validateEveryNode(observation);
        var result = compareSearches(observation);
        assertThat(result.score()).isEqualTo(expected);
        assertThat(result.bestPlan()).anyMatch(action -> action.kind() == PlanningAction.Kind.INSTALL
                && action.contactId().equals("C1"));
        assertThat(result.bestPlan()).anyMatch(action -> action.kind() == PlanningAction.Kind.SWITCH
                && action.contactId().equals("C1"));
    }

    @Test void firstRemainingStationWindowCannotBorrowItsInstallationFromAnotherStation() {
        var transitions = new LifecycleTransitions(CONSTRAINTS);
        var state = transitions.prepareKeys(initialState(), A, NEW_A, at(69));
        var payload = new SimulationContact.PayloadProfile(100, 100, 100, 0, 100, 0);
        // A's old pair expires at 70. The only remaining A window is too short for I/S/O,
        // even though B has its own preceding window. An unrelated B OPEN may be in flight.
        var scenario = new SimulationScenario("bound-no-earlier-A-window", at(70), at(74), state, CONSTRAINTS,
                profile(100, false), List.of(new SimulationContact(window("B-now", B, 70, 72), payload),
                new SimulationContact(window("A-next", A, 72, 74), payload)), List.of());
        var observation = PlanningObservation.from(new SimulationEngine().start(scenario), 1);
        assertThat(bound.upperBound(engine.create(observation)).servedContacts()).isEqualTo(1);
        assertThat(bound.upperBound(engine.create(observation)).usefulSecureTime()).isEqualTo(Duration.ofSeconds(1));
        validateEveryNode(observation);
        assertThat(compareSearches(observation).score())
                .isEqualTo(new BranchEvaluation(1, Duration.ofSeconds(1), Duration.ofSeconds(1), 0, 0));
    }

    private void validateEveryNode(PlanningObservation observation) {
        var visited = new long[1];
        var terminal = descendants(engine.create(observation), visited);
        assertThat(visited[0]).isPositive();
        assertThat(terminal).isNotEmpty();
    }

    private List<BranchEvaluation> descendants(PlanningBranch branch, long[] visited) {
        visited[0]++;
        if (branch.snapshot().journal().stream().anyMatch(e -> e.type() == SimulationEvent.Type.ACTION_FAILED
                || e.type() == SimulationEvent.Type.PAYLOAD_REJECTED)) return List.of();
        var optimistic = bound.upperBound(branch);
        var terminal = new ArrayList<BranchEvaluation>();
        if (branch.snapshot().at().equals(branch.origin().evaluationEnd())) terminal.add(evaluator.evaluate(branch).evaluation());
        else for (var action : generator.candidates(branch.observation()))
            terminal.addAll(descendants(engine.execute(branch, action), visited));
        for (var actual : terminal) assertThat(comparator.compareMetrics(optimistic, actual))
                .as("Bound at %s after %s must dominate terminal score %s; bound=%s",
                        branch.snapshot().at(), branch.plan(), actual, optimistic).isLessThanOrEqualTo(0);
        return terminal;
    }

    private HorizonPlanResult compareSearches(PlanningObservation observation) {
        var reference = new ExactHorizonPlanner(SearchLimits.unlimited(), PlannerOptions.reference()).plan(observation);
        var options = new PlannerOptions(true, true, PlannerOptions.TraversalOrder.SERVICE_FIRST, 1000, true);
        var optimized = new ExactHorizonPlanner(SearchLimits.unlimited(), options).plan(observation);
        var reversed = new ExactHorizonPlanner(SearchLimits.unlimited(), options, actions -> {
            var copy = new ArrayList<>(actions);
            Collections.reverse(copy);
            return copy;
        }).plan(observation);
        for (var result : List.of(optimized, reversed)) {
            assertThat(reference.status()).isEqualTo(HorizonPlanResult.Status.COMPLETE);
            assertThat(result.status()).isEqualTo(HorizonPlanResult.Status.COMPLETE);
            assertThat(result.score()).isEqualTo(reference.score());
            assertThat(result.bestPlan()).containsExactlyElementsOf(reference.bestPlan());
            var replay = engine.create(observation);
            for (var action : result.bestPlan()) replay = engine.execute(replay, action);
            assertThat(replay.snapshot().at()).isEqualTo(observation.evaluationEnd());
            assertThat(evaluator.evaluate(replay).evaluation()).isEqualTo(result.score());
        }
        return optimized;
    }

    private PlanningBranch opened(PlanningObservation observation) {
        var branch = engine.create(observation);
        branch = engine.execute(branch, action(branch, PlanningAction.Kind.OPEN));
        return engine.execute(branch, action(branch, PlanningAction.Kind.WAIT));
    }

    private PlanningAction action(PlanningBranch branch, PlanningAction.Kind kind) {
        return generator.candidates(branch.observation()).stream().filter(a -> a.kind() == kind).findFirst().orElseThrow();
    }

    private PlanningObservation fresh(boolean prepared) {
        var transitions = new LifecycleTransitions(CONSTRAINTS);
        var state = transitions.createContext(SecurityState.empty(SATELLITE, at(-1)), A, "bound-up", "bound-down", at(-1));
        if (prepared) state = transitions.prepareKeys(state, A, NEW_A, at(-1));
        long end = prepared ? 5 : 6;
        var scenario = new SimulationScenario("bound-fresh-" + prepared, at(0), at(end), state, CONSTRAINTS,
                profile(prepared ? 100 : 1, false), List.of(contact(end)), List.of());
        return PlanningObservation.from(new SimulationEngine().start(scenario), 1);
    }

    private PlanningObservation keyed(long end, boolean expires, boolean cheapReuse) {
        var transitions = new LifecycleTransitions(CONSTRAINTS);
        long activated = expires ? -148 : -30;
        var state = transitions.createContext(SecurityState.empty(SATELLITE, at(-150)), A, "bound-up", "bound-down", at(-150));
        state = transitions.prepareKeys(state, A, OLD_A, at(-150));
        var past = window("history", A, activated, activated + 2);
        state = transitions.confirmInstallation(state, A, OLD_A, past, at(activated));
        state = transitions.activateKeys(state, A, OLD_A, past, at(activated));
        state = transitions.bindKeys(state, A, OLD_A, past, at(activated));
        state = transitions.startContext(state, A, past, at(activated));
        state = transitions.recordProtectedTraffic(state, A, at(activated + 1), at(activated + 2), 1, 1);
        state = transitions.stopContext(state, A, at(activated + 2));
        var scenario = new SimulationScenario("bound-keyed", at(0), at(end), state, CONSTRAINTS,
                profile(100, cheapReuse), List.of(contact(end)), List.of());
        return PlanningObservation.from(new SimulationEngine().start(scenario), 1);
    }

    private SimulationContact contact(long end) {
        return new SimulationContact(window("C1", A, 0, end), new SimulationContact.PayloadProfile(100, 100, 200, 200, 100, 100));
    }

    private SecurityCostProfile profile(long prepare, boolean cheapReuse) {
        var costs = new EnumMap<SecurityAction, SecurityCostProfile.OperationCost>(SecurityAction.class);
        for (var action : List.of(SecurityAction.PREPARE, SecurityAction.INSTALL, SecurityAction.SWITCH,
                SecurityAction.OPEN, SecurityAction.REUSE)) {
            var duration = action == SecurityAction.PREPARE ? Duration.ofSeconds(prepare)
                    : cheapReuse && action == SecurityAction.REUSE ? Duration.ofMillis(500) : Duration.ofSeconds(1);
            costs.put(action, new SecurityCostProfile.OperationCost(List.of(
                    new SecurityCostProfile.Phase(SecurityCostProfile.Resource.GROUND_CPU, duration, 0))));
        }
        costs.put(SecurityAction.STOP, new SecurityCostProfile.OperationCost(List.of()));
        costs.put(SecurityAction.EXPIRE_CONTEXT, new SecurityCostProfile.OperationCost(List.of()));
        return new SecurityCostProfile("synthetic-bound-oracle", SecurityCostProfile.Provenance.SYNTHETIC_ASSUMPTION,
                "Small exhaustive bound-validation fixture", costs);
    }
}
