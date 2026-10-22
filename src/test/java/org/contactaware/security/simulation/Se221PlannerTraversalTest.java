package org.contactaware.security.simulation;

import org.contactaware.security.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import java.time.Duration;
import java.util.*;
import java.util.function.UnaryOperator;
import static org.assertj.core.api.Assertions.*;
import static org.contactaware.security.simulation.HorizonWitness.*;
import static org.contactaware.security.simulation.PlanningAction.Kind.*;

/** Finite nontrivial DFS oracles for the opt-in traversal only; no timed performance assertions. */
class Se221PlannerTraversalTest {
    private static final UnaryOperator<List<PlanningAction>> REVERSE = actions -> {
        var result = new ArrayList<>(actions);
        Collections.reverse(result);
        return result;
    };

    @ParameterizedTest @EnumSource(PlanTieBreak.class)
    void newMaterialMustCompleteTheWholeChainBeforeService(PlanTieBreak rule) {
        var transitions = new LifecycleTransitions(CONSTRAINTS);
        var state = transitions.createContext(SecurityState.empty(SATELLITE, at(-1)), A,
                "up-SA", "down-SA", at(-1));
        var scenario = new SimulationScenario("se221-fresh", at(0), at(6), state, CONSTRAINTS,
                profile(1), List.of(contact("C1", 0, 6)), List.of());
        var observation = PlanningObservation.from(new SimulationEngine().start(scenario), 1);
        var reference = compareAll(rule, observation);
        assertThat(reference.score()).isEqualTo(new BranchEvaluation(1, Duration.ofSeconds(2),
                Duration.ofSeconds(4), 0, 2));
        assertThat(reference.bestPlan().stream().filter(PlanningAction::isSecurityOperation)
                .map(PlanningAction::kind).toList()).containsExactly(PREPARE, INSTALL, SWITCH, OPEN);
    }

    @ParameterizedTest @EnumSource(PlanTieBreak.class)
    void aRealInstallContinueTieKeepsTheRulesFullPlanWithEitherTraversalPermutation(PlanTieBreak rule) {
        var state = new LifecycleTransitions(CONSTRAINTS).prepareKeys(aOnlyState(), A, NEW_A, at(0));
        var scenario = new SimulationScenario("se221-install-tie", at(0), at(84), state, CONSTRAINTS,
                profile(100), List.of(contact("C1", 0, 5), contact("C2", 80, 84)), List.of());
        var observation = openFirst(scenario);
        var reference = compareAll(rule, observation);
        assertThat(reference.score()).isEqualTo(new BranchEvaluation(2, Duration.ofSeconds(4),
                Duration.ofSeconds(3), 0, 0));
        assertThat(reference.firstAction().kind()).isEqualTo(rule == PlanTieBreak.CANONICAL ? INSTALL : CONTINUE);
    }

    private HorizonPlanResult compareAll(PlanTieBreak rule, PlanningObservation observation) {
        var reference = planner(rule, PlannerOptions.reference(), UnaryOperator.identity()).plan(observation);
        assertThat(reference.status()).isEqualTo(HorizonPlanResult.Status.COMPLETE);
        for (var options : List.of(PlannerOptions.accelerated(), PlannerOptions.acceleratedServiceFirst())) {
            for (var order : List.of(UnaryOperator.<List<PlanningAction>>identity(), REVERSE)) {
                var actual = planner(rule, options, order).plan(observation);
                assertThat(actual.status()).isEqualTo(HorizonPlanResult.Status.COMPLETE);
                assertThat(actual.score()).isEqualTo(reference.score());
                assertThat(actual.bestPlan()).containsExactlyElementsOf(reference.bestPlan());
                assertThat(actual.firstAction()).isEqualTo(reference.firstAction());
                var branches = new BranchEngine();
                var branch = branches.create(observation);
                for (var action : actual.bestPlan()) branch = branches.execute(branch, action);
                assertThat(branch.snapshot().at()).isEqualTo(observation.evaluationEnd());
                assertThat(new ContinuationEvaluator().evaluate(branch).evaluation()).isEqualTo(actual.score());
            }
        }
        return reference;
    }

    private ExactHorizonPlanner planner(PlanTieBreak rule, PlannerOptions options,
                                        UnaryOperator<List<PlanningAction>> order) {
        return new ExactHorizonPlanner(new SearchLimits(250_000, Duration.ZERO), options,
                DecisionTimeModel.V2_EVENT_BOUNDARIES, rule, order);
    }

    private PlanningObservation openFirst(SimulationScenario scenario) {
        var engine = new BranchEngine();
        var branch = engine.create(PlanningObservation.from(new SimulationEngine().start(scenario), 1));
        for (var kind : List.of(OPEN, WAIT)) {
            var action = new SecurityActionGenerator().candidates(branch.observation()).stream()
                    .filter(a -> a.kind() == kind).findFirst().orElseThrow();
            branch = engine.execute(branch, action);
        }
        return branch.observation();
    }

    private SecurityState aOnlyState() {
        var original = initialState();
        var keys = new TreeMap<String, KeyState>();
        original.keys().values().stream().filter(key -> key.contextId().equals(A))
                .forEach(key -> keys.put(key.id(), key));
        return new SecurityState(SATELLITE, original.asOf(), Map.of(A, original.context(A)), keys);
    }

    private SimulationContact contact(String id, long start, long end) {
        return new SimulationContact(window(id, A, start, end),
                new SimulationContact.PayloadProfile(100, 100, 100, 100, 100, 100));
    }

    private SecurityCostProfile profile(long prepareSeconds) {
        var operations = new EnumMap<SecurityAction, SecurityCostProfile.OperationCost>(SecurityAction.class);
        for (var action : List.of(SecurityAction.PREPARE, SecurityAction.INSTALL, SecurityAction.SWITCH,
                SecurityAction.OPEN, SecurityAction.REUSE))
            operations.put(action, new SecurityCostProfile.OperationCost(List.of(
                    new SecurityCostProfile.Phase(SecurityCostProfile.Resource.GROUND_CPU,
                            Duration.ofSeconds(action == SecurityAction.PREPARE ? prepareSeconds : 1), 0))));
        operations.put(SecurityAction.STOP, new SecurityCostProfile.OperationCost(List.of()));
        operations.put(SecurityAction.EXPIRE_CONTEXT, new SecurityCostProfile.OperationCost(List.of()));
        return new SecurityCostProfile("se221-traversal-oracle", SecurityCostProfile.Provenance.SYNTHETIC_ASSUMPTION,
                "Small exact traversal test, not hardware calibration", operations);
    }
}
