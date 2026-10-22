package org.contactaware.security.simulation;

import org.contactaware.security.*;
import org.junit.jupiter.api.Test;

import java.time.*;
import java.util.*;

import static org.assertj.core.api.Assertions.*;
import static org.contactaware.security.simulation.HorizonWitness.*;

/** Independent exhaustive descendant checks for the mandatory preparation relaxation. */
class PlanningPreparationBoundTest {
    private final BranchEngine engine = new BranchEngine();
    private final SecurityActionGenerator generator = new SecurityActionGenerator();
    private final PlanningOptimisticBound bound = new PlanningOptimisticBound();
    private final PlanningResultComparator comparator = new PlanningResultComparator();
    private final ContinuationEvaluator evaluator = new ContinuationEvaluator();

    @Test void autonomousWitnessRequiresPreparationOnlyForTheContextWhoseActiveMaterialCannotReachItsReturn() {
        var sourceX = PolicyExperimentFixtures.witness(Sequence.X, false);
        var sourceY = PolicyExperimentFixtures.witness(Sequence.Y, false);
        var simulation = new SimulationEngine();
        var x = simulation.advance(sourceX.checkpoint(), at(0));
        var y = simulation.advance(sourceY.checkpoint(), at(0));
        assertThat(bound.upperBound(engine.create(PlanningObservation.from(x, 2))))
                .isEqualTo(new BranchEvaluation(3, Duration.ofSeconds(37), Duration.ofMillis(830), 1240, 2));
        assertThat(bound.upperBound(engine.create(PlanningObservation.from(x, 1))))
                .isEqualTo(new BranchEvaluation(2, Duration.ofSeconds(38), Duration.ofMillis(120), 160, 0));
        assertThat(bound.upperBound(engine.create(PlanningObservation.from(y, 2))))
                .isEqualTo(new BranchEvaluation(3, Duration.ofSeconds(42), Duration.ofMillis(180), 240, 0));
    }

    @Test void freshMaterialAddsOnePreparationAndTwoKeysButExistingPreparedMaterialIsNotChargedAgain() {
        for (boolean prepared : List.of(false, true)) {
            var observation = fresh(prepared, false, prepared ? 4 : 5);
            var root = engine.create(observation);
            var expected = new BranchEvaluation(1, Duration.ofSeconds(1), Duration.ofSeconds(prepared ? 3 : 4), 0, prepared ? 0 : 2);
            assertThat(bound.upperBound(root)).isEqualTo(expected);
            validateEveryNode(root);
            compareSearches(observation, expected);
        }
    }

    @Test void pendingAndCompletedPreparationCreditOnlyWorkAlreadyConsumedWithoutDoubleCountingKeys() {
        var observation = fresh(false, false, 5);
        var pending = engine.execute(engine.create(observation), action(engine.create(observation), PlanningAction.Kind.PREPARE, A));
        var halfway = new SimulationEngine().planningAdvance(pending.snapshot(), at(0).plusMillis(500), false);
        var partial = new PlanningBranch(observation, halfway, pending.plan());
        var expected = new BranchEvaluation(1, Duration.ofSeconds(1), Duration.ofSeconds(4), 0, 2);
        assertThat(halfway.accruedCosts().totalCpu()).isEqualTo(Duration.ofMillis(500));
        assertThat(halfway.createdKeys()).isZero();
        assertThat(bound.upperBound(partial)).isEqualTo(expected);
        validateEveryNode(partial);
        var complete = engine.execute(partial, action(partial, PlanningAction.Kind.WAIT, null));
        assertThat(complete.snapshot().createdKeys()).isEqualTo(2);
        assertThat(bound.upperBound(complete)).isEqualTo(expected);
        validateEveryNode(complete);

        // A new search charges only the unconsumed half of the inherited PREPARE.
        var restarted = engine.create(PlanningObservation.from(halfway, 1));
        assertThat(bound.upperBound(restarted)).isEqualTo(
                new BranchEvaluation(1, Duration.ofSeconds(1), Duration.ofMillis(3500), 0, 2));
        validateEveryNode(restarted);
    }

    @Test void inheritedPartialPrimitivesAndOperationalProgressRetainTheTightBoundWithoutChargingSunkCosts() {
        var observation = fresh(false, false, 6);
        var branch = engine.create(observation);
        long started = 0;
        for (var kind : List.of(PlanningAction.Kind.PREPARE, PlanningAction.Kind.INSTALL,
                PlanningAction.Kind.SWITCH, PlanningAction.Kind.OPEN)) {
            branch = engine.execute(branch, action(branch, kind, A));
            var partial = new SimulationEngine().planningAdvance(branch.snapshot(), at(started).plusMillis(500), false);
            var restart = PlanningObservation.from(partial, 1);
            var expected = new BranchEvaluation(1, Duration.ofSeconds(1), Duration.ofMillis(3500 - started * 1000),
                    0, started == 0 ? 2 : 0);
            assertThat(bound.upperBound(engine.create(restart))).isEqualTo(expected);
            validateEveryNode(engine.create(restart));
            compareSearches(restart, expected);
            var completed = new SimulationEngine().planningAdvance(partial, at(++started), false);
            branch = new PlanningBranch(observation, completed, branch.plan());
        }
        var open = PlanningObservation.from(branch.snapshot(), 1);
        assertThat(bound.upperBound(engine.create(open)))
                .isEqualTo(new BranchEvaluation(1, Duration.ofSeconds(1), Duration.ZERO, 0, 0));
        validateEveryNode(engine.create(open));
        branch = engine.execute(branch, action(branch, PlanningAction.Kind.CONTINUE, A));
        var served = PlanningObservation.from(branch.snapshot(), 1);
        assertThat(bound.upperBound(engine.create(served))).isEqualTo(new BranchEvaluation(0, Duration.ZERO, Duration.ZERO, 0, 0));
        validateEveryNode(engine.create(served));
    }

    @Test void autonomousWitnessBoundRemainsTightAfterOpeningAndDuringOverlappingPreparation() {
        var scenario = PolicyExperimentFixtures.witness(Sequence.X, false);
        var source = new SimulationEngine().advance(scenario.checkpoint(), at(0));
        var branch = engine.create(PlanningObservation.from(source, 2));
        branch = engine.execute(branch, action(branch, PlanningAction.Kind.OPEN, A));
        assertRestartedBound(branch, 3, 37, 830, 1240, 2);
        branch = engine.execute(branch, action(branch, PlanningAction.Kind.WAIT, null));
        assertRestartedBound(branch, 3, 37, 770, 1160, 2);
        branch = engine.execute(branch, action(branch, PlanningAction.Kind.PREPARE, A));
        assertRestartedBound(branch, 3, 37, 770, 1160, 2);
        for (int i = 0; i < 4; i++) branch = engine.execute(branch, action(branch, PlanningAction.Kind.CONTINUE, A));
        assertRestartedBound(branch, 3, 36, 570, 1160, 2);
        for (int i = 0; i < 4; i++) branch = engine.execute(branch, action(branch, PlanningAction.Kind.CONTINUE, A));
        assertRestartedBound(branch, 2, 35, 570, 1160, 0);
    }

    @Test void committedFrameRootCreditsItsPreRootElapsedPartExactlyOnce() {
        var branch = engine.create(fresh(false, false, 5));
        for (var kind : List.of(PlanningAction.Kind.PREPARE, PlanningAction.Kind.INSTALL,
                PlanningAction.Kind.SWITCH, PlanningAction.Kind.OPEN)) {
            branch = engine.execute(branch, action(branch, kind, A));
            branch = engine.execute(branch, action(branch, PlanningAction.Kind.WAIT, null));
        }
        var partial = new SimulationEngine().planningAdvance(branch.snapshot(), at(4).plusMillis(500), true);
        assertThat(partial.inFlightFrame()).isNotNull();
        var restarted = PlanningObservation.from(partial, 1);
        var expected = new BranchEvaluation(1, Duration.ofSeconds(1), Duration.ZERO, 0, 0);
        assertThat(bound.upperBound(engine.create(restarted))).isEqualTo(expected);
        validateEveryNode(engine.create(restarted));
        compareSearches(restarted, expected);
    }

    @Test void alreadyCommittedUnnecessaryPreparationStillConsumesCpuAndCreatesKeysAtTheBoundary() {
        var branch = engine.create(fresh(false, true, 1));
        branch = engine.execute(branch, action(branch, PlanningAction.Kind.PREPARE, B));
        assertThat(bound.upperBound(branch)).isEqualTo(
                new BranchEvaluation(0, Duration.ZERO, Duration.ofSeconds(1), 0, 2));
        validateEveryNode(branch);
        var partial = new SimulationEngine().planningAdvance(branch.snapshot(), at(0).plusMillis(500), false);
        var restarted = PlanningObservation.from(partial, 1);
        var expected = new BranchEvaluation(0, Duration.ZERO, Duration.ofMillis(500), 0, 2);
        assertThat(bound.upperBound(engine.create(restarted))).isEqualTo(expected);
        validateEveryNode(engine.create(restarted));
        compareSearches(restarted, expected);
    }

    @Test void preparationThatCrossesTheHorizonCreditsOnlyItsExecutedCpuAndNoUncreatedKeys() {
        var source = fresh(false, true, 1);
        var operations = new EnumMap<>(source.costs().operations());
        operations.put(SecurityAction.PREPARE, new SecurityCostProfile.OperationCost(List.of(
                new SecurityCostProfile.Phase(SecurityCostProfile.Resource.GROUND_CPU, Duration.ofSeconds(2), 0))));
        var profile = new SecurityCostProfile("synthetic-partial-prepare-bound", SecurityCostProfile.Provenance.SYNTHETIC_ASSUMPTION,
                "A committed two-second preparation crosses a one-second horizon", operations);
        // The candidate generator correctly disallows a new PREPARE that cannot finish in
        // its visible horizon. A factual inherited operation can nevertheless outlive it.
        var committed = operation("inherited-B-prepare", SecurityAction.PREPARE, 0, B,
                new KeyPairIds("inherited-B-up", "inherited-B-down"), null);
        var scenario = new SimulationScenario("bound-partial-prepare", at(0), at(1), source.securityState(), source.constraints(),
                profile, source.visibleContacts(), List.of(committed));
        var branch = engine.create(PlanningObservation.from(new SimulationEngine().start(scenario), 1));
        assertThat(branch.snapshot().pendingOperations()).hasSize(1);
        assertThat(bound.upperBound(branch)).isEqualTo(
                new BranchEvaluation(0, Duration.ZERO, Duration.ofSeconds(1), 0, 0));
        validateEveryNode(branch);
        var partial = new SimulationEngine().planningAdvance(branch.snapshot(), at(0).plusMillis(500), false);
        var restarted = PlanningObservation.from(partial, 1);
        var expected = new BranchEvaluation(0, Duration.ZERO, Duration.ofMillis(500), 0, 0);
        assertThat(bound.upperBound(engine.create(restarted))).isEqualTo(expected);
        validateEveryNode(engine.create(restarted));
        compareSearches(restarted, expected);
    }

    private void assertRestartedBound(PlanningBranch branch, long served, long usefulSeconds, long cpuMillis, long bytes, long keys) {
        var observation = PlanningObservation.from(branch.snapshot(), 2);
        var root = engine.create(observation);
        var expected = new BranchEvaluation(served, Duration.ofSeconds(usefulSeconds), Duration.ofMillis(cpuMillis), bytes, keys);
        assertThat(bound.upperBound(root)).isEqualTo(expected);
        assertThat(new PlanningOptimisticBound(observation).upperBound(root)).isEqualTo(expected);
    }

    @Test void preparationForAnUnrelatedStationCannotPayForTheRequiredPair() {
        var observation = fresh(false, true, 6);
        var root = engine.create(observation);
        var branch = engine.execute(root, action(root, PlanningAction.Kind.PREPARE, B));
        branch = engine.execute(branch, action(branch, PlanningAction.Kind.WAIT, null));
        assertThat(branch.snapshot().at()).isEqualTo(at(1));
        assertThat(bound.upperBound(branch)).isEqualTo(
                new BranchEvaluation(1, Duration.ofSeconds(1), Duration.ofSeconds(5), 0, 4));
        validateEveryNode(branch);
    }

    @Test void extraOnlineOperationsAtAnotherStationCannotPayTheMandatoryInstallationCost() {
        var transitions = new LifecycleTransitions(CONSTRAINTS);
        var state = transitions.createContext(SecurityState.empty(SATELLITE, at(-3)), A, "A-up", "A-down", at(-3));
        state = transitions.createContext(state, B, "B-up", "B-down", at(-3));
        state = transitions.prepareKeys(state, B, OLD_B, at(-3));
        var history = window("history", B, -2, -1);
        state = transitions.confirmInstallation(state, B, OLD_B, history, at(-2));
        state = transitions.activateKeys(state, B, OLD_B, history, at(-2));
        state = transitions.bindKeys(state, B, OLD_B, history, at(-2));
        state = transitions.startContext(state, B, history, at(-2));
        state = transitions.recordProtectedTraffic(state, B, at(-2), at(-1), 1, 1);
        state = transitions.stopContext(state, B, at(-1));
        state = transitions.prepareKeys(state, B, new KeyPairIds("B-spare-up", "B-spare-down"), at(-1));
        var scenario = new SimulationScenario("preparation-bound-unrelated-online", at(0), at(9), state, CONSTRAINTS,
                costs(), List.of(contact("B-first", B, 0, 4), contact("A-second", A, 5, 9)), List.of());
        var observation = PlanningObservation.from(new SimulationEngine().start(scenario), 2);
        var branch = engine.create(observation);
        for (var kind : List.of(PlanningAction.Kind.INSTALL, PlanningAction.Kind.SWITCH, PlanningAction.Kind.OPEN)) {
            branch = engine.execute(branch, action(branch, kind, B));
            branch = engine.execute(branch, action(branch, PlanningAction.Kind.WAIT, null));
        }
        branch = engine.execute(branch, action(branch, PlanningAction.Kind.CONTINUE, B));
        assertThat(branch.snapshot().at()).isEqualTo(at(4));
        assertThat(bound.upperBound(branch)).isEqualTo(
                new BranchEvaluation(2, Duration.ofSeconds(2), Duration.ofSeconds(7), 0, 2));
        validateEveryNode(branch);
    }

    private PlanningObservation fresh(boolean prepared, boolean includeB, long end) {
        var transitions = new LifecycleTransitions(CONSTRAINTS);
        var state = transitions.createContext(SecurityState.empty(SATELLITE, at(-1)), A, "A-up", "A-down", at(-1));
        if (includeB) state = transitions.createContext(state, B, "B-up", "B-down", at(-1));
        if (prepared) state = transitions.prepareKeys(state, A, NEW_A, at(-1));
        var scenario = new SimulationScenario("preparation-bound-fresh", at(0), at(end), state, CONSTRAINTS,
                costs(), List.of(contact("A-contact", A, 0, end)), List.of());
        return PlanningObservation.from(new SimulationEngine().start(scenario), 1);
    }

    private SimulationContact contact(String id, ContextId context, long start, long end) {
        return new SimulationContact(window(id, context, start, end),
                new SimulationContact.PayloadProfile(100, 100, 100, 0, 100, 0));
    }

    private SecurityCostProfile costs() {
        var operations = new EnumMap<SecurityAction, SecurityCostProfile.OperationCost>(SecurityAction.class);
        for (var action : List.of(SecurityAction.PREPARE, SecurityAction.INSTALL, SecurityAction.SWITCH, SecurityAction.OPEN, SecurityAction.REUSE))
            operations.put(action, new SecurityCostProfile.OperationCost(List.of(
                    new SecurityCostProfile.Phase(SecurityCostProfile.Resource.GROUND_CPU, Duration.ofSeconds(1), 0))));
        operations.put(SecurityAction.STOP, new SecurityCostProfile.OperationCost(List.of()));
        operations.put(SecurityAction.EXPIRE_CONTEXT, new SecurityCostProfile.OperationCost(List.of()));
        return new SecurityCostProfile("synthetic-preparation-bound", SecurityCostProfile.Provenance.SYNTHETIC_ASSUMPTION,
                "Tiny exhaustive preparation-accounting fixtures", operations);
    }

    private PlanningAction action(PlanningBranch branch, PlanningAction.Kind kind, ContextId context) {
        return generator.candidates(branch.observation()).stream().filter(a -> a.kind() == kind && Objects.equals(context, a.contextId()))
                .findFirst().orElseThrow();
    }

    private void validateEveryNode(PlanningBranch root) {
        var visited = new long[1];
        assertThat(descendants(root, visited)).isNotEmpty();
        assertThat(visited[0]).isPositive();
    }

    /** Enumerate every feasible terminal descendant without pruning or memoization. */
    private List<BranchEvaluation> descendants(PlanningBranch branch, long[] visited) {
        visited[0]++;
        if (branch.snapshot().journal().stream().anyMatch(e -> e.type() == SimulationEvent.Type.ACTION_FAILED
                || e.type() == SimulationEvent.Type.PAYLOAD_REJECTED)) return List.of();
        var optimistic = bound.upperBound(branch);
        assertThat(new PlanningOptimisticBound(branch.origin()).upperBound(branch)).isEqualTo(optimistic);
        var terminal = new ArrayList<BranchEvaluation>();
        if (branch.snapshot().at().equals(branch.origin().evaluationEnd())) terminal.add(evaluator.evaluate(branch).evaluation());
        else for (var candidate : generator.candidates(branch.observation()))
            terminal.addAll(descendants(engine.execute(branch, candidate), visited));
        for (var actual : terminal) assertThat(comparator.compareMetrics(optimistic, actual))
                .as("Bound %s at %s after %s must dominate %s", optimistic, branch.snapshot().at(), branch.plan(), actual)
                .isLessThanOrEqualTo(0);
        return terminal;
    }

    private void compareSearches(PlanningObservation observation, BranchEvaluation expected) {
        var reference = new ExactHorizonPlanner(SearchLimits.unlimited(), PlannerOptions.reference()).plan(observation);
        var optimized = new ExactHorizonPlanner(SearchLimits.unlimited(), PlannerOptions.accelerated()).plan(observation);
        var reversed = new ExactHorizonPlanner(SearchLimits.unlimited(), PlannerOptions.accelerated(), actions -> {
            var copy = new ArrayList<>(actions);
            Collections.reverse(copy);
            return copy;
        }).plan(observation);
        assertThat(reference.status()).isEqualTo(HorizonPlanResult.Status.COMPLETE);
        assertThat(reference.score()).isEqualTo(expected);
        for (var result : List.of(optimized, reversed)) {
            assertThat(result.status()).isEqualTo(HorizonPlanResult.Status.COMPLETE);
            assertThat(result.score()).isEqualTo(reference.score());
            assertThat(result.bestPlan()).isEqualTo(reference.bestPlan());
        }
    }
}
