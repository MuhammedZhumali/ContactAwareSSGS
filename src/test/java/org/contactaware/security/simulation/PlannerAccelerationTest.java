package org.contactaware.security.simulation;

import org.contactaware.security.*;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import java.util.function.UnaryOperator;
import static org.assertj.core.api.Assertions.*;
import static org.contactaware.security.simulation.HorizonWitness.*;

/** Regression oracle: optimizations must preserve both the score and the entire canonical plan. */
class PlannerAccelerationTest {
    private static final SearchLimits UNLIMITED = SearchLimits.unlimited();
    private static final UnaryOperator<List<PlanningAction>> REVERSE = actions -> {
        var copy = new ArrayList<>(actions);
        Collections.reverse(copy);
        return copy;
    };

    @Test void everyAccelerationSeparatelyAndTogetherPreservesTheIndependentChainOracle() {
        var observation = freshObservation(true);
        var reference = planner(PlannerOptions.reference()).plan(observation);
        assertThat(reference.score()).isEqualTo(new BranchEvaluation(1, Duration.ofSeconds(2), Duration.ofSeconds(3), 0, 0));
        assertThat(reference.firstAction().kind()).isEqualTo(PlanningAction.Kind.INSTALL);
        for (var options : variants()) {
            var result = planner(options).plan(observation);
            assertSameComplete(reference, result);
            replay(observation, result);
        }
    }

    @Test void prepareRemainsInThePermittedSpaceAndBothSearchesFindTheSameCreatedKeys() {
        var observation = freshObservation(false);
        assertThat(new SecurityActionGenerator().candidates(observation))
                .anyMatch(action -> action.kind() == PlanningAction.Kind.PREPARE);
        var reference = planner(PlannerOptions.reference()).plan(observation);
        assertThat(reference.firstAction().kind()).isEqualTo(PlanningAction.Kind.PREPARE);
        // PREPARE, INSTALL, SWITCH and OPEN each occupy one CPU-second; two full frames remain.
        assertThat(reference.score()).isEqualTo(new BranchEvaluation(1, Duration.ofSeconds(2), Duration.ofSeconds(4), 0, 2));
        for (var options : variants()) assertSameComplete(reference, planner(options).plan(observation));
        var reversed = new ExactHorizonPlanner(UNLIMITED, PlannerOptions.accelerated(), REVERSE).plan(observation);
        assertSameComplete(reference, reversed);
        replay(observation, reversed);
    }

    @Test void identicalWaitSiblingsKeepTheOriginalCanonicalRepresentativeUnderReverseTraversal() {
        var observation = reusableObservation(true);
        var engine = new BranchEngine();
        var root = engine.create(observation);
        var waits = new SecurityActionGenerator().candidates(observation).stream()
                .filter(action -> action.kind() == PlanningAction.Kind.WAIT).toList();
        assertThat(waits).hasSizeGreaterThan(1);
        assertThat(waits.stream().map(action -> engine.execute(root, action).snapshot()).distinct()).hasSize(1);

        var reference = planner(PlannerOptions.reference()).plan(observation);
        var waitOnly = new PlannerOptions(true, false, PlannerOptions.TraversalOrder.CANONICAL, 0);
        var accelerated = new ExactHorizonPlanner(UNLIMITED, waitOnly, REVERSE).plan(observation);
        assertSameComplete(reference, accelerated);
        assertThat(accelerated.diagnostics().collapsedWaitTransitions()).isPositive();
        assertThat(accelerated.diagnostics().repeatedTransitions())
                .isGreaterThanOrEqualTo(accelerated.diagnostics().collapsedWaitTransitions());
        assertThat(accelerated.exploredNodes()).isLessThan(reference.exploredNodes());
        replay(observation, accelerated);
    }

    @Test void memoSuffixFromReusePrefixCannotReplaceTheCanonicalOpenPrefix() {
        var observation = reusableObservation(false);
        var reference = planner(PlannerOptions.reference()).plan(observation);
        var memoOnly = new PlannerOptions(false, true, PlannerOptions.TraversalOrder.CANONICAL, 0);
        // Reversed traversal solves REUSE before OPEN. Their completed states are equivalent,
        // so the winning OPEN prefix must be combined with the cached continuation suffix.
        var reversed = new ExactHorizonPlanner(UNLIMITED, memoOnly, REVERSE).plan(observation);
        assertSameComplete(reference, reversed);
        assertThat(reversed.firstAction().kind()).isEqualTo(PlanningAction.Kind.OPEN);
        assertThat(reversed.diagnostics().memoHits()).isPositive();
        assertThat(reversed.diagnostics().memoEntries()).isPositive();
        replay(observation, reversed);
    }

    @Test void memoCapacityChangesOnlyWorkAndNotTheCompletedAnswer() {
        var observation = reusableObservation(true);
        var reference = planner(PlannerOptions.reference()).plan(observation);
        var tinyCache = new PlannerOptions(true, true, PlannerOptions.TraversalOrder.SERVICE_FIRST, 1);
        var result = planner(tinyCache).plan(observation);
        assertSameComplete(reference, result);
        assertThat(result.diagnostics().memoEntries()).isEqualTo(1);
        assertThat(result.diagnostics().memoStoreSkips()).isPositive();
    }

    @Test void incompleteSearchDoesNotPublishPartialResultsIntoTheNextInvocation() {
        var observation = freshObservation(true);
        var limited = new ExactHorizonPlanner(new SearchLimits(10, Duration.ZERO), PlannerOptions.accelerated());
        var first = limited.plan(observation);
        var second = limited.plan(observation);
        assertThat(first.status()).isEqualTo(HorizonPlanResult.Status.INCOMPLETE);
        assertThat(second.status()).isEqualTo(first.status());
        assertThat(second.bestPlan()).isEqualTo(first.bestPlan());
        assertThat(second.score()).isEqualTo(first.score());
        assertThat(second.exploredNodes()).isEqualTo(first.exploredNodes());
        assertThat(second.diagnostics().memoHits()).isEqualTo(first.diagnostics().memoHits());
        assertThat(second.diagnostics().memoEntries()).isEqualTo(first.diagnostics().memoEntries());
        assertThat(second.diagnostics().terminalPlans()).isEqualTo(first.diagnostics().terminalPlans());
        assertThat(first.diagnostics().stopReason()).isEqualTo(SearchDiagnostics.StopReason.NODE_LIMIT);
        if (first.score() != null) replay(observation, first);
        assertSameComplete(planner(PlannerOptions.reference()).plan(observation), planner(PlannerOptions.accelerated()).plan(observation));
    }

    @Test void diagnosticsAccountForActualGenerationExecutionAndVisitedLeaves() {
        var result = planner(PlannerOptions.reference()).plan(freshObservation(true));
        var diagnostics = result.diagnostics();
        assertThat(diagnostics.terminalPlans()).isPositive().isLessThanOrEqualTo(result.exploredNodes());
        assertThat(diagnostics.maxDepth()).isGreaterThanOrEqualTo(result.bestPlan().size());
        assertThat(diagnostics.expandedNodes()).isPositive();
        assertThat(diagnostics.generatedActions())
                .isEqualTo(diagnostics.generatedByAction().values().stream().mapToLong(Long::longValue).sum());
        assertThat(diagnostics.executedTransitions())
                .isEqualTo(diagnostics.executedByAction().values().stream().mapToLong(Long::longValue).sum());
        assertThat(diagnostics.generationNanos()).isNotNegative();
        assertThat(diagnostics.executionNanos()).isNotNegative();
        assertThat(diagnostics.memoHits()).isZero();
        assertThat(diagnostics.collapsedWaitTransitions()).isZero();
        assertThat(diagnostics.repeatedTransitions()).isPositive();
        assertThat(diagnostics.stopReason()).isEqualTo(SearchDiagnostics.StopReason.NONE);
        assertThat(diagnostics.rootCandidateCount()).isPositive();
        assertThat(diagnostics.rootGeneratedByAction().values().stream().mapToLong(Long::longValue).sum())
                .isEqualTo(diagnostics.rootCandidateCount());
        assertThatThrownBy(() -> diagnostics.generatedByAction().put(PlanningAction.Kind.WAIT, 0L))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    private List<PlannerOptions> variants() {
        return List.of(new PlannerOptions(true, false, PlannerOptions.TraversalOrder.CANONICAL, 0),
                new PlannerOptions(false, true, PlannerOptions.TraversalOrder.CANONICAL, 0),
                new PlannerOptions(false, false, PlannerOptions.TraversalOrder.SERVICE_FIRST, 0),
                new PlannerOptions(true, false, PlannerOptions.TraversalOrder.CANONICAL, 0, false, true, false),
                new PlannerOptions(false, false, PlannerOptions.TraversalOrder.SERVICE_FIRST, 0, true, false, true),
                PlannerOptions.accelerated());
    }

    @Test void earlyWaitReductionPreservesWinnerAndAvoidsExecutingDuplicateTransitions() {
        var observation = reusableObservation(true);
        var legacy = planner(new PlannerOptions(true, true, PlannerOptions.TraversalOrder.SERVICE_FIRST, 50_000, true))
                .plan(observation);
        var reduced = planner(PlannerOptions.accelerated()).plan(observation);
        assertSameComplete(legacy, reduced);
        assertThat(reduced.diagnostics().preExecutionWaitSkips()).isPositive();
        assertThat(reduced.diagnostics().executedTransitions()).isLessThan(legacy.diagnostics().executedTransitions());
        assertSameComplete(reduced, new ExactHorizonPlanner(UNLIMITED, PlannerOptions.accelerated(), REVERSE).plan(observation));
    }

    private ExactHorizonPlanner planner(PlannerOptions options) { return new ExactHorizonPlanner(UNLIMITED, options); }

    private void assertSameComplete(HorizonPlanResult reference, HorizonPlanResult actual) {
        assertThat(reference.status()).isEqualTo(HorizonPlanResult.Status.COMPLETE);
        assertThat(actual.status()).isEqualTo(HorizonPlanResult.Status.COMPLETE);
        assertThat(actual.score()).isEqualTo(reference.score());
        assertThat(actual.bestPlan()).containsExactlyElementsOf(reference.bestPlan());
        assertThat(actual.firstAction()).isEqualTo(reference.firstAction());
    }

    private void replay(PlanningObservation observation, HorizonPlanResult result) {
        var engine = new BranchEngine();
        var branch = engine.create(observation);
        for (var action : result.bestPlan()) branch = engine.execute(branch, action);
        assertThat(branch.snapshot().at()).isEqualTo(observation.evaluationEnd());
        assertThat(new ContinuationEvaluator().evaluate(branch).evaluation()).isEqualTo(result.score());
    }

    private PlanningObservation freshObservation(boolean prepared) {
        var transitions = new LifecycleTransitions(CONSTRAINTS);
        var state = transitions.createContext(SecurityState.empty(SATELLITE, at(-1)), A, "up-SA", "down-SA", at(-1));
        if (prepared) state = transitions.prepareKeys(state, A, NEW_A, at(-1));
        long end = prepared ? 5 : 6;
        var input = new SimulationScenario("acceleration-chain-" + prepared, at(0), at(end), state, CONSTRAINTS,
                profile(prepared ? 100 : 1), List.of(contact("C1", 0, end)), List.of());
        return PlanningObservation.from(new SimulationEngine().start(input), 1);
    }

    private PlanningObservation reusableObservation(boolean twoContacts) {
        var original = initialState();
        var keys = new TreeMap<String, KeyState>();
        original.keys().values().stream().filter(key -> key.contextId().equals(A)).forEach(key -> keys.put(key.id(), key));
        var state = new SecurityState(SATELLITE, original.asOf(), Map.of(A, original.context(A)), keys);
        var contacts = twoContacts ? List.of(contact("C1", 0, 3), contact("C2", 4, 7)) : List.of(contact("C1", 0, 3));
        var input = new SimulationScenario("acceleration-reuse-" + twoContacts, at(0), at(twoContacts ? 7 : 3), state,
                CONSTRAINTS, profile(100), contacts, List.of());
        return PlanningObservation.from(new SimulationEngine().start(input), 1);
    }

    private SimulationContact contact(String id, long start, long end) {
        return new SimulationContact(window(id, A, start, end),
                new SimulationContact.PayloadProfile(100, 100, 100, 100, 100, 100));
    }

    private SecurityCostProfile profile(long prepareSeconds) {
        var costs = new EnumMap<SecurityAction, SecurityCostProfile.OperationCost>(SecurityAction.class);
        for (var action : List.of(SecurityAction.PREPARE, SecurityAction.INSTALL, SecurityAction.SWITCH,
                SecurityAction.OPEN, SecurityAction.REUSE)) {
            var duration = Duration.ofSeconds(action == SecurityAction.PREPARE ? prepareSeconds : 1);
            costs.put(action, new SecurityCostProfile.OperationCost(List.of(
                    new SecurityCostProfile.Phase(SecurityCostProfile.Resource.GROUND_CPU, duration, 0))));
        }
        costs.put(SecurityAction.STOP, new SecurityCostProfile.OperationCost(List.of()));
        costs.put(SecurityAction.EXPIRE_CONTEXT, new SecurityCostProfile.OperationCost(List.of()));
        return new SecurityCostProfile("synthetic-acceleration-oracle", SecurityCostProfile.Provenance.SYNTHETIC_ASSUMPTION,
                "Independent small timing fixtures; no hardware calibration", costs);
    }
}
