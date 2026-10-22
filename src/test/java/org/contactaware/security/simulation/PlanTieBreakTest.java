package org.contactaware.security.simulation;

import org.contactaware.security.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import java.time.Duration;
import java.util.*;
import java.util.function.UnaryOperator;
import static org.assertj.core.api.Assertions.*;
import static org.contactaware.security.simulation.HorizonWitness.*;
import static org.contactaware.security.simulation.PlanningAction.Kind.*;

/** Small finite oracles for SE2.2; no ISS inputs, wall-clock thresholds or experiment reruns. */
class PlanTieBreakTest {
    private static final UnaryOperator<List<PlanningAction>> REVERSE = actions -> {
        var copy = new ArrayList<>(actions);
        Collections.reverse(copy);
        return copy;
    };

    @ParameterizedTest @EnumSource(PlanTieBreak.class)
    void eachPrimaryCriterionOverridesAContraryPlanPreference(PlanTieBreak rule) {
        var comparator = new PlanningResultComparator(rule);
        var preferredKind = rule == PlanTieBreak.CANONICAL ? PREPARE : CONTINUE;
        var disfavoredKind = rule == PlanTieBreak.CANONICAL ? CONTINUE : PREPARE;
        var base = new BranchEvaluation(2, Duration.ofSeconds(10), Duration.ofSeconds(1), 100, 2);
        var better = List.of(
                new BranchEvaluation(3, Duration.ZERO, Duration.ofSeconds(20), 1000, 20),
                new BranchEvaluation(2, Duration.ofSeconds(10).plusNanos(1), Duration.ofSeconds(20), 1000, 20),
                new BranchEvaluation(2, Duration.ofSeconds(10), Duration.ofSeconds(1).minusNanos(1), 1000, 20),
                new BranchEvaluation(2, Duration.ofSeconds(10), Duration.ofSeconds(1), 99, 20),
                new BranchEvaluation(2, Duration.ofSeconds(10), Duration.ofSeconds(1), 100, 1));
        var reasons = List.of(PlanningResultComparator.Criterion.SERVED_CONTACTS,
                PlanningResultComparator.Criterion.USEFUL_TIME, PlanningResultComparator.Criterion.CONTROL_CPU,
                PlanningResultComparator.Criterion.MANAGEMENT_BYTES, PlanningResultComparator.Criterion.NEW_KEYS);
        var baseline = new PlanningResult(base, List.of(action(preferredKind, 1)));
        for (int i = 0; i < better.size(); i++) {
            var winner = new PlanningResult(better.get(i), List.of(action(disfavoredKind, 1)));
            assertThat(comparator.compare(winner, baseline)).isNegative();
            assertThat(comparator.compare(baseline, winner)).isPositive();
            assertThat(comparator.decisiveCriterion(winner, baseline)).isEqualTo(reasons.get(i));
        }
    }

    @Test void auditedAlternativeUsesGroupThenUntilThenTheOriginalTuple() {
        var canonical = new PlanningResultComparator(PlanTieBreak.CANONICAL);
        var alternative = new PlanningResultComparator(PlanTieBreak.CONTINUE_WAIT);
        var actions = Arrays.stream(PlanningAction.Kind.values()).map(k -> action(k, 1)).toList();
        assertThat(actions.stream().sorted(canonical::compareActions).map(PlanningAction::kind).toList())
                .containsExactly(PREPARE, INSTALL, SWITCH, OPEN, REUSE, CONTINUE, WAIT);
        assertThat(actions.stream().sorted(alternative::compareActions).map(PlanningAction::kind).toList())
                .containsExactly(CONTINUE, WAIT, PREPARE, INSTALL, SWITCH, OPEN, REUSE);
        // The actual Claude rule differs from simply moving CONTINUE/WAIT ahead of the enum.
        assertThat(canonical.compareActions(action(PREPARE, 3), action(INSTALL, 1))).isNegative();
        assertThat(alternative.compareActions(action(PREPARE, 3), action(INSTALL, 1))).isPositive();
        assertThat(alternative.compareActions(action(CONTINUE, 3), action(WAIT, 1))).isNegative();
        assertThat(alternative.compareActions(action(WAIT, 3), action(INSTALL, 1))).isNegative();
        assertThat(alternative.comparePlans(List.of(action(WAIT, 1)),
                List.of(action(WAIT, 1), action(INSTALL, 2)))).isNegative();
    }

    @ParameterizedTest @EnumSource(PlanTieBreak.class)
    void prefixPruningUsesTheSelectedOrderAndNeverPrunesAProperPrefix(PlanTieBreak rule) {
        var comparator = new PlanningResultComparator(rule);
        var prepare = action(PREPARE, 1);
        var transmit = action(CONTINUE, 1);
        var wait = action(WAIT, 2);
        var better = rule == PlanTieBreak.CANONICAL ? prepare : transmit;
        var worse = rule == PlanTieBreak.CANONICAL ? transmit : prepare;
        assertThat(PlanningActionReduction.prefixAlreadyWorse(List.of(worse), List.of(better, wait), comparator)).isTrue();
        assertThat(PlanningActionReduction.prefixAlreadyWorse(List.of(better), List.of(worse, wait), comparator)).isFalse();
        assertThat(PlanningActionReduction.prefixAlreadyWorse(List.of(better), List.of(better, wait), comparator)).isFalse();
        assertThat(PlanningActionReduction.prefixAlreadyWorse(List.of(better, wait), List.of(better), comparator)).isFalse();
        assertThat(PlanningActionReduction.prefixAlreadyWorse(List.of(), List.of(worse), comparator)).isFalse();
    }

    @ParameterizedTest @EnumSource(PlanTieBreak.class)
    void referenceAndAccelerationsAgreeForBothVisibleHorizonsAndReverseTraversal(PlanTieBreak rule) {
        for (int horizon : List.of(1, 2)) {
            var observation = reusableObservation(3, horizon);
            assertThat(observation.visibleContacts()).hasSize(horizon + 1);
            var reference = planner(rule, PlannerOptions.reference(), UnaryOperator.identity()).plan(observation);
            assertThat(reference.score()).isEqualTo(new BranchEvaluation(horizon + 1,
                    Duration.ofSeconds(2L * (horizon + 1)), Duration.ofSeconds(horizon + 1), 0, 0));
            for (var options : variants()) {
                same(reference, planner(rule, options, UnaryOperator.identity()).plan(observation));
                same(reference, planner(rule, options, REVERSE).plan(observation));
            }
        }
    }

    @ParameterizedTest @EnumSource(PlanTieBreak.class)
    void aCacheHitPreservesTheCurrentPrefixAndTheRulesCompleteSuffix(PlanTieBreak rule) {
        var observation = reusableObservation(1, 1);
        var reference = planner(rule, PlannerOptions.reference(), UnaryOperator.identity()).plan(observation);
        var memoOnly = new PlannerOptions(false, true, PlannerOptions.TraversalOrder.CANONICAL, 0);
        // REUSE is visited first, but OPEN must remain the winner under both documented rules.
        var cached = planner(rule, memoOnly, REVERSE).plan(observation);
        same(reference, cached);
        assertThat(cached.firstAction().kind()).isEqualTo(OPEN);
        assertThat(cached.diagnostics().memoHits()).isPositive();
        assertThat(cached.diagnostics().memoEntries()).isPositive();
        replay(observation, cached);
    }

    @ParameterizedTest @EnumSource(PlanTieBreak.class)
    void genuineInstallContinueTieChangesTheWinnerButNotTheFiveMetrics(PlanTieBreak rule) {
        var observation = installContinueTie();
        var reference = planner(rule, PlannerOptions.reference(), UnaryOperator.identity()).plan(observation);
        assertThat(reference.score()).isEqualTo(new BranchEvaluation(2, Duration.ofSeconds(4), Duration.ofSeconds(3), 0, 0));
        assertThat(reference.firstAction().kind()).isEqualTo(rule == PlanTieBreak.CANONICAL ? INSTALL : CONTINUE);
        for (var options : variants()) {
            same(reference, planner(rule, options, UnaryOperator.identity()).plan(observation));
            same(reference, planner(rule, options, REVERSE).plan(observation));
        }
        replay(observation, reference);
    }

    @ParameterizedTest @EnumSource(PlanTieBreak.class)
    void fixedFirstSearchRecoversTheCompleteOptimalActionSetAtOneUnchangedRoot(PlanTieBreak rule) {
        var observation = installContinueTie();
        var planner = planner(rule, PlannerOptions.accelerated(), REVERSE);
        var unrestricted = planner.plan(observation);
        var comparator = new PlanningResultComparator(rule);
        var tied = new ArrayList<PlanningAction.Kind>();
        var all = new SecurityActionGenerator().candidates(observation);
        assertThat(all.stream().filter(a -> a.kind() == WAIT).count()).isGreaterThan(1);
        for (var first : all) {
            var constrained = planner.planWithFirstAction(observation, first);
            assertThat(constrained.status()).isEqualTo(HorizonPlanResult.Status.COMPLETE);
            assertThat(constrained.firstAction()).isEqualTo(first);
            assertThat(constrained.bestPlan().getFirst()).isEqualTo(first);
            assertThat(comparator.compareMetrics(constrained.score(), unrestricted.score())).isNotNegative();
            if (comparator.compareMetrics(constrained.score(), unrestricted.score()) == 0) tied.add(first.kind());
            replay(observation, constrained);
        }
        assertThat(tied).containsExactlyInAnyOrder(INSTALL, CONTINUE);
        assertThatThrownBy(() -> planner.planWithFirstAction(observation, action(OPEN, 2)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("root candidate");
    }

    @ParameterizedTest @EnumSource(PlanTieBreak.class)
    void fixedFirstKeepsPriorDeliveryAndPartiallyAccruedOperationCosts(PlanTieBreak rule) {
        var branches = new BranchEngine();
        var beginning = branches.create(reusableObservation(1, 1));
        var opening = take(branches, beginning, OPEN);
        var halfOpen = new SimulationEngine().planningAdvance(opening.snapshot(), at(0).plusMillis(500), false);
        var halfObservation = PlanningObservation.from(halfOpen, 1);
        var wait = new SecurityActionGenerator().candidates(halfObservation).stream().filter(a -> a.kind() == WAIT).findFirst().orElseThrow();
        var remaining = planner(rule, PlannerOptions.accelerated(), REVERSE).planWithFirstAction(halfObservation, wait);
        assertThat(remaining.status()).isEqualTo(HorizonPlanResult.Status.COMPLETE);
        assertThat(remaining.score()).isEqualTo(new BranchEvaluation(1, Duration.ofSeconds(2), Duration.ofMillis(500), 0, 0));
        replay(halfObservation, remaining);

        var opened = take(branches, opening, WAIT);
        var oneDelivered = take(branches, opened, CONTINUE);
        var afterFrame = oneDelivered.observation();
        var next = new SecurityActionGenerator().candidates(afterFrame).stream().filter(a -> a.kind() == CONTINUE).findFirst().orElseThrow();
        var threshold = planner(rule, PlannerOptions.accelerated(), REVERSE).planWithFirstAction(afterFrame, next);
        assertThat(threshold.status()).isEqualTo(HorizonPlanResult.Status.COMPLETE);
        assertThat(threshold.score()).isEqualTo(new BranchEvaluation(1, Duration.ofSeconds(1), Duration.ZERO, 0, 0));
        replay(afterFrame, threshold);
    }

    @ParameterizedTest @EnumSource(PlanTieBreak.class)
    void aNodeLimitedFixedFirstResultIsIncompleteAndDoesNotContaminateLaterCalls(PlanTieBreak rule) {
        var observation = installContinueTie();
        var first = new SecurityActionGenerator().candidates(observation).stream().filter(a -> a.kind() == INSTALL).findFirst().orElseThrow();
        var limited = new ExactHorizonPlanner(new SearchLimits(1, Duration.ZERO), PlannerOptions.accelerated(),
                DecisionTimeModel.V2_EVENT_BOUNDARIES, rule).planWithFirstAction(observation, first);
        assertThat(limited.status()).isEqualTo(HorizonPlanResult.Status.INCOMPLETE);
        assertThat(limited.diagnostics().stopReason()).isEqualTo(SearchDiagnostics.StopReason.NODE_LIMIT);
        var reused = planner(rule, PlannerOptions.accelerated(), UnaryOperator.identity());
        var constrained = reused.planWithFirstAction(observation, first);
        assertThat(constrained.status()).isEqualTo(HorizonPlanResult.Status.COMPLETE);
        same(planner(rule, PlannerOptions.reference(), UnaryOperator.identity()).plan(observation), reused.plan(observation));
    }

    private List<PlannerOptions> variants() {
        return List.of(new PlannerOptions(true, false, PlannerOptions.TraversalOrder.CANONICAL, 0),
                new PlannerOptions(false, true, PlannerOptions.TraversalOrder.CANONICAL, 0),
                new PlannerOptions(false, false, PlannerOptions.TraversalOrder.SERVICE_FIRST, 0, true, false, true),
                new PlannerOptions(false, false, PlannerOptions.TraversalOrder.CANONICAL, 0, false, true, false),
                PlannerOptions.accelerated());
    }

    private ExactHorizonPlanner planner(PlanTieBreak rule, PlannerOptions options, UnaryOperator<List<PlanningAction>> traversal) {
        return new ExactHorizonPlanner(SearchLimits.unlimited(), options, DecisionTimeModel.V2_EVENT_BOUNDARIES, rule, traversal);
    }

    private void same(HorizonPlanResult expected, HorizonPlanResult actual) {
        assertThat(expected.status()).isEqualTo(HorizonPlanResult.Status.COMPLETE);
        assertThat(actual.status()).isEqualTo(HorizonPlanResult.Status.COMPLETE);
        assertThat(actual.score()).isEqualTo(expected.score());
        assertThat(actual.bestPlan()).containsExactlyElementsOf(expected.bestPlan());
    }

    private void replay(PlanningObservation observation, HorizonPlanResult result) {
        var engine = new BranchEngine();
        var branch = engine.create(observation);
        for (var action : result.bestPlan()) branch = engine.execute(branch, action);
        assertThat(branch.snapshot().at()).isEqualTo(observation.evaluationEnd());
        assertThat(new ContinuationEvaluator().evaluate(branch).evaluation()).isEqualTo(result.score());
    }

    private PlanningBranch take(BranchEngine engine, PlanningBranch branch, PlanningAction.Kind kind) {
        var next = new SecurityActionGenerator().candidates(branch.observation()).stream().filter(a -> a.kind() == kind).findFirst().orElseThrow();
        return engine.execute(branch, next);
    }

    private PlanningObservation reusableObservation(int contactCount, int horizon) {
        var original = initialState();
        var keys = new TreeMap<String, KeyState>();
        original.keys().values().stream().filter(k -> k.contextId().equals(A)).forEach(k -> keys.put(k.id(), k));
        var state = new SecurityState(SATELLITE, original.asOf(), Map.of(A, original.context(A)), keys);
        var contacts = new ArrayList<SimulationContact>();
        for (int i = 0; i < contactCount; i++) contacts.add(contact("C" + (i + 1), 4L * i, 4L * i + 3));
        var scenario = new SimulationScenario("tie-small-" + contactCount, at(0), contacts.getLast().window().end(),
                state, CONSTRAINTS, profile(), contacts, List.of());
        return PlanningObservation.from(new SimulationEngine().start(scenario), horizon);
    }

    /** Early/later INSTALL preserves four delivered frames and all costs, unlike an OPEN/REUSE-only tie. */
    private PlanningObservation installContinueTie() {
        var original = reusableObservation(1, 1).securityState();
        var state = new LifecycleTransitions(CONSTRAINTS).prepareKeys(original, A, NEW_A, at(0));
        var scenario = new SimulationScenario("tie-install-continue", at(0), at(84), state, CONSTRAINTS, profile(),
                List.of(contact("C1", 0, 5), contact("C2", 80, 84)), List.of());
        var branches = new BranchEngine();
        var root = branches.create(PlanningObservation.from(new SimulationEngine().start(scenario), 1));
        return take(branches, take(branches, root, OPEN), WAIT).observation();
    }

    private SimulationContact contact(String id, long start, long end) {
        return new SimulationContact(window(id, A, start, end), new SimulationContact.PayloadProfile(100, 100, 100, 100, 100, 100));
    }

    private SecurityCostProfile profile() {
        var costs = new EnumMap<SecurityAction, SecurityCostProfile.OperationCost>(SecurityAction.class);
        for (var kind : List.of(SecurityAction.PREPARE, SecurityAction.INSTALL, SecurityAction.SWITCH, SecurityAction.OPEN, SecurityAction.REUSE)) {
            var duration = Duration.ofSeconds(kind == SecurityAction.PREPARE ? 100 : 1);
            costs.put(kind, new SecurityCostProfile.OperationCost(List.of(new SecurityCostProfile.Phase(SecurityCostProfile.Resource.GROUND_CPU, duration, 0))));
        }
        costs.put(SecurityAction.STOP, new SecurityCostProfile.OperationCost(List.of()));
        costs.put(SecurityAction.EXPIRE_CONTEXT, new SecurityCostProfile.OperationCost(List.of()));
        return new SecurityCostProfile("synthetic-tie-oracle", SecurityCostProfile.Provenance.SYNTHETIC_ASSUMPTION,
                "Small exact test fixture; not calibrated hardware", costs);
    }

    private PlanningAction action(PlanningAction.Kind kind, long end) {
        var keys = kind == PREPARE || kind == INSTALL || kind == SWITCH ? NEW_A : null;
        return new PlanningAction(kind, at(0), at(end), kind == WAIT ? null : A, keys,
                kind == WAIT || kind == PREPARE ? null : "C1");
    }
}
