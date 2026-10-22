package org.contactaware.security.simulation;

import org.contactaware.security.*;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.contactaware.security.simulation.HorizonWitness.*;
import static org.contactaware.security.simulation.PlanningAction.Kind.*;

/** Small complete trees whose optima follow from independent timing/resource arithmetic. */
class ExactHorizonPlannerTest {
    private final SimulationEngine simulation = new SimulationEngine();

    @Test void independentHandEnumerationRequiresTheWholeInstallSwitchOpenChain() {
        var observation = chainObservation();
        var result = new ExactHorizonPlanner().plan(observation);

        // Only INSTALL -> SWITCH -> OPEN can enable traffic. Each step occupies one second.
        // In [0,5], this leaves exactly two one-second frames, one in each direction.
        // WAIT instead of any prerequisite cannot recover, and no fresh PREPARE fits.
        var expected = List.of(
                new PlanningAction(INSTALL, at(0), at(1), A, NEW_A, "C1"),
                waitAction(0, 1),
                new PlanningAction(SWITCH, at(1), at(2), A, NEW_A, "C1"),
                waitAction(1, 2),
                new PlanningAction(OPEN, at(2), at(3), A, null, "C1"),
                waitAction(2, 3),
                new PlanningAction(CONTINUE, at(3), at(4), A, null, "C1"),
                new PlanningAction(CONTINUE, at(4), at(5), A, null, "C1"));
        assertThat(result.status()).isEqualTo(HorizonPlanResult.Status.COMPLETE);
        assertThat(result.score()).isEqualTo(new BranchEvaluation(1, Duration.ofSeconds(2), Duration.ofSeconds(3), 0, 0));
        assertThat(result.bestPlan()).containsExactlyElementsOf(expected);
        assertThat(result.firstAction()).isEqualTo(expected.getFirst());
        assertThat(result.exploredNodes()).isGreaterThan(expected.size());
        assertThat(result.elapsedNanos()).isNotNegative();
        assertTerminalReplay(observation, result);
    }

    @Test void completeMetricTiesChooseTheCanonicalPlanRegardlessOfCandidateOrder() {
        var observation = keyedObservation(3);
        var normal = new ExactHorizonPlanner().plan(observation);
        var reversed = reversedPlanner().plan(observation);
        var expected = List.of(new PlanningAction(OPEN, at(0), at(1), A, null, "C1"),
                waitAction(0, 1), new PlanningAction(CONTINUE, at(1), at(2), A, null, "C1"),
                new PlanningAction(CONTINUE, at(2), at(3), A, null, "C1"));

        // OPEN and REUSE each cost one CPU-second and allow the same two frames.
        // The agreed canonical order puts OPEN before REUSE, independently of DFS order.
        assertThat(normal.status()).isEqualTo(HorizonPlanResult.Status.COMPLETE);
        assertThat(reversed.status()).isEqualTo(HorizonPlanResult.Status.COMPLETE);
        assertThat(normal.score()).isEqualTo(new BranchEvaluation(1, Duration.ofSeconds(2), Duration.ofSeconds(1), 0, 0));
        assertThat(normal.bestPlan()).containsExactlyElementsOf(expected);
        assertThat(reversed.bestPlan()).isEqualTo(normal.bestPlan());
        assertThat(reversed.score()).isEqualTo(normal.score());
        assertThat(reversed.firstAction()).isEqualTo(normal.firstAction());
    }

    @Test void reversingCandidateTraversalPreservesTheMultiStepWinner() {
        var observation = chainObservation();
        var normal = new ExactHorizonPlanner().plan(observation);
        var reversed = reversedPlanner().plan(observation);
        assertThat(reversed.status()).isEqualTo(HorizonPlanResult.Status.COMPLETE);
        assertThat(reversed.bestPlan()).isEqualTo(normal.bestPlan());
        assertThat(reversed.score()).isEqualTo(normal.score());
        assertTerminalReplay(observation, reversed);
    }

    @Test void continuationRetainsCurrentContactProgressAndSubtractsAccruedRootCosts() {
        var engine = new BranchEngine();
        var branch = engine.create(keyedObservation(3));
        branch = engine.execute(branch, new PlanningAction(OPEN, at(0), at(1), A, null, "C1"));
        branch = engine.execute(branch, waitAction(0, 1));
        branch = engine.execute(branch, new PlanningAction(CONTINUE, at(1), at(2), A, null, "C1"));
        var observation = branch.observation();

        // The uplink minimum is already met. One downlink frame completes this contact;
        // past OPEN CPU and the first frame are not part of the continuation's score.
        var result = new ExactHorizonPlanner().plan(observation);
        assertThat(result.status()).isEqualTo(HorizonPlanResult.Status.COMPLETE);
        assertThat(result.bestPlan()).containsExactly(new PlanningAction(CONTINUE, at(2), at(3), A, null, "C1"));
        assertThat(result.score()).isEqualTo(new BranchEvaluation(1, Duration.ofSeconds(1), Duration.ZERO, 0, 0));
        assertTerminalReplay(observation, result);
    }

    @Test void nextReceivesTheSameRootAndReturnsTheSamePlanForHiddenXAndY() {
        var x = shortRoute(A);
        var y = shortRoute(B);
        assertThat(x).isEqualTo(y);
        var left = new ExactHorizonPlanner().plan(x);
        var right = new ExactHorizonPlanner().plan(y);
        assertThat(left.status()).isEqualTo(HorizonPlanResult.Status.COMPLETE);
        assertThat(right.status()).isEqualTo(HorizonPlanResult.Status.COMPLETE);
        assertThat(left.bestPlan()).isEqualTo(right.bestPlan());
        assertThat(left.score()).isEqualTo(right.score());
        assertThat(left.score()).isEqualTo(new BranchEvaluation(2, Duration.ofSeconds(4), Duration.ofSeconds(2), 0, 0));
        assertThat(left.bestPlan()).noneMatch(action -> "C3".equals(action.contactId()));
        assertThat(left.bestPlan()).allMatch(action -> !action.until().isAfter(at(7)));
        var terminal = assertTerminalReplay(x, left);
        assertThat(terminal.snapshot().scenario().contacts()).extracting(c -> c.window().id()).containsExactly("C1", "C2");
    }

    @Test void reducedSyntheticXYProvesTheThirdContactCanChangeAnEarlierInstallDecision() {
        // Reduced synthetic fixture, not the original witness's cost calibration: one uplink
        // frame per contact, 1s online steps, and no fresh 100s PREPARE fits the 8s horizon.
        // Old A expires at 6 exactly when C3 opens; old B remains valid until 120.
        // A prepared spare is known at the common root. No hidden preselected operations exist.
        var nextX = causalRoute(A, 1);
        var nextY = causalRoute(B, 1);
        assertThat(nextX).isEqualTo(nextY);
        var planner = new ExactHorizonPlanner(new SearchLimits(1_000_000, Duration.ZERO));
        var nextXResult = planner.plan(nextX);
        var nextYResult = planner.plan(nextY);
        var multiX = causalRoute(A, 2);
        var multiY = causalRoute(B, 2);
        var multiXResult = planner.plan(multiX);
        var multiYResult = planner.plan(multiY);

        for (var named : List.of(Map.entry("Next X", nextXResult), Map.entry("Next Y", nextYResult),
                Map.entry("Multi X", multiXResult), Map.entry("Multi Y", multiYResult))) {
            var result = named.getValue();
            System.out.printf("Reduced synthetic %s: %s; nodes=%d; score=%s%n",
                    named.getKey(), result.status(), result.exploredNodes(), result.score());
        }
        assertThat(List.of(nextXResult, nextYResult, multiXResult, multiYResult))
                .extracting(HorizonPlanResult::status).containsOnly(HorizonPlanResult.Status.COMPLETE);
        assertThat(nextXResult.bestPlan()).isEqualTo(nextYResult.bestPlan());
        assertThat(nextXResult.score()).isEqualTo(nextYResult.score());
        assertThat(nextXResult.score()).isEqualTo(new BranchEvaluation(2, Duration.ofSeconds(2), Duration.ofSeconds(2), 0, 0));
        assertThat(nextXResult.bestPlan()).noneMatch(action -> action.kind() == INSTALL);

        // In X, C3 is too short for INSTALL + SWITCH + OPEN + the payload frame.
        // INSTALL and SWITCH must therefore happen during C1 to serve all three contacts.
        assertThat(multiXResult.score()).isEqualTo(new BranchEvaluation(3, Duration.ofSeconds(3), Duration.ofSeconds(5), 0, 0));
        assertThat(multiXResult.bestPlan()).anyMatch(action -> action.kind() == INSTALL
                && action.contextId().equals(A) && "C1".equals(action.contactId()));
        assertThat(multiXResult.bestPlan()).anyMatch(action -> action.kind() == SWITCH
                && action.contextId().equals(A) && "C1".equals(action.contactId()));
        assertThat(multiXResult.firstAction().kind()).isEqualTo(INSTALL);

        // In Y, B's original material remains valid through C3, so extra A management
        // cannot improve the service/time objectives and loses on the CPU criterion.
        assertThat(multiYResult.score()).isEqualTo(new BranchEvaluation(3, Duration.ofSeconds(3), Duration.ofSeconds(3), 0, 0));
        assertThat(multiYResult.bestPlan()).noneMatch(action -> action.kind() == INSTALL);
        assertThat(multiYResult.firstAction().kind()).isEqualTo(OPEN);
        assertTerminalReplay(nextX, nextXResult);
        assertTerminalReplay(multiX, multiXResult);
        assertTerminalReplay(multiY, multiYResult);
    }

    @Test void nodeLimitBeforeAnyCompleteLeafNeverReturnsAPartialScoreAsAnOptimum() {
        var result = new ExactHorizonPlanner(new SearchLimits(1, Duration.ZERO)).plan(chainObservation());
        assertThat(result.status()).isEqualTo(HorizonPlanResult.Status.INCOMPLETE);
        assertThat(result.exploredNodes()).isEqualTo(1);
        assertThat(result.bestPlan()).isEmpty();
        assertThat(result.score()).isNull();
        assertThat(result.firstAction()).isNull();
    }

    @Test void nodeLimitAfterACompleteLeafReportsAnIncumbentButStillIncomplete() {
        var observation = chainObservation();
        var result = new ExactHorizonPlanner(new SearchLimits(10, Duration.ZERO)).plan(observation);
        assertThat(result.status()).isEqualTo(HorizonPlanResult.Status.INCOMPLETE);
        assertThat(result.exploredNodes()).isEqualTo(10);
        assertThat(result.score()).isNotNull();
        assertThat(result.bestPlan()).isNotEmpty();
        assertTerminalReplay(observation, result);
    }

    @Test void elapsedTimeLimitCannotClaimTheUnexploredTreeIsComplete() {
        var clock = new java.util.concurrent.atomic.AtomicLong();
        var result = new ExactHorizonPlanner(new SearchLimits(0, Duration.ofNanos(1)),
                java.util.function.UnaryOperator.identity(), clock::getAndIncrement).plan(chainObservation());
        assertThat(result.status()).isEqualTo(HorizonPlanResult.Status.INCOMPLETE);
        assertThat(result.elapsedNanos()).isEqualTo(2);
        assertThat(result.exploredNodes()).isZero();
        assertThat(result.score()).isNull();
    }

    @Test void emptyHorizonIsACompleteZeroContinuationEvenWithOneAllowedNode() {
        var input = chainScenario();
        var snapshot = simulation.advance(simulation.start(input), input.end());
        var observation = PlanningObservation.from(snapshot, 1);
        var result = new ExactHorizonPlanner(new SearchLimits(1, Duration.ZERO)).plan(observation);
        assertThat(result.status()).isEqualTo(HorizonPlanResult.Status.COMPLETE);
        assertThat(result.score()).isEqualTo(new BranchEvaluation(0, Duration.ZERO, Duration.ZERO, 0, 0));
        assertThat(result.bestPlan()).isEmpty();
        assertThat(result.firstAction()).isNull();
        assertThat(result.exploredNodes()).isEqualTo(1);
    }

    @Test void terminalEvaluationChargesOnlyTheExecutedPartOfInheritedPreparation() {
        var constraints = CONSTRAINTS;
        var transitions = new LifecycleTransitions(constraints);
        var state = transitions.createContext(SecurityState.empty(SATELLITE, at(-1)), A, "up-SA", "down-SA", at(-1));
        var profile = profile(Duration.ofSeconds(10), Duration.ofSeconds(1));
        var input = new SimulationScenario("inherited-long-preparation", at(-1), at(20), state, constraints, profile,
                List.of(contact("C1", A, 0, 2)), List.of(operation("preparation", SecurityAction.PREPARE, -1, A, NEW_A, null)));
        var observation = PlanningObservation.from(simulation.advance(simulation.start(input), at(0)), 1);
        var result = new ExactHorizonPlanner().plan(observation);

        assertThat(result.status()).isEqualTo(HorizonPlanResult.Status.COMPLETE);
        assertThat(result.bestPlan()).containsExactly(waitAction(0, 2));
        assertThat(result.score()).isEqualTo(new BranchEvaluation(0, Duration.ZERO, Duration.ofSeconds(2), 0, 0));
        var terminal = assertTerminalReplay(observation, result);
        assertThat(terminal.snapshot().pendingOperations()).hasSize(1);
        assertThat(terminal.snapshot().accruedCosts().groundCpu()).isEqualTo(Duration.ofSeconds(3));
        assertThat(terminal.snapshot().journal()).noneMatch(e -> e.type() == SimulationEvent.Type.SIMULATION_ENDED
                || e.type() == SimulationEvent.Type.STEP_INTERRUPTED || e.type() == SimulationEvent.Type.ACTION_FAILED);
    }

    @Test void unavoidableConstraintFailureReturnsInfeasibleAfterCompleteExploration() {
        var constraints = new SecurityConstraints(Duration.ofSeconds(150), Duration.ofSeconds(3),
                Duration.ofSeconds(200), Set.of(A), Set.of());
        var transitions = new LifecycleTransitions(constraints);
        var state = transitions.createContext(SecurityState.empty(SATELLITE, at(-1)), A, "up-SA", "down-SA", at(-1));
        state = transitions.prepareKeys(state, A, NEW_A, at(-1)); // Deadline at t=2.
        var input = new SimulationScenario("unavoidable-expiry", at(0), at(5), state, constraints,
                profile(Duration.ofSeconds(100), Duration.ofSeconds(3)), List.of(contact("C1", A, 0, 5)),
                List.of(operation("already-installing", SecurityAction.INSTALL, 0, A, NEW_A, "C1")));
        var observation = PlanningObservation.from(simulation.start(input), 1);
        var result = new ExactHorizonPlanner().plan(observation);
        assertThat(result.status()).isEqualTo(HorizonPlanResult.Status.INFEASIBLE);
        assertThat(result.score()).isNull();
        assertThat(result.bestPlan()).isEmpty();
        assertThat(result.firstAction()).isNull();
    }

    @Test void candidateOrderingHookCannotSilentlyDropAllowedActions() {
        var planner = new ExactHorizonPlanner(SearchLimits.unlimited(), ignored -> List.of());
        assertThatThrownBy(() -> planner.plan(chainObservation())).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void returnedPlanIsImmutable() {
        var result = new ExactHorizonPlanner().plan(chainObservation());
        assertThatThrownBy(() -> result.bestPlan().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    private ExactHorizonPlanner reversedPlanner() {
        return new ExactHorizonPlanner(SearchLimits.unlimited(), actions -> {
            var reversed = new ArrayList<>(actions);
            Collections.reverse(reversed);
            return reversed;
        });
    }

    private PlanningObservation chainObservation() {
        return PlanningObservation.from(simulation.start(chainScenario()), 1);
    }

    private SimulationScenario chainScenario() {
        var transitions = new LifecycleTransitions(CONSTRAINTS);
        var state = transitions.createContext(SecurityState.empty(SATELLITE, at(-1)), A, "up-SA", "down-SA", at(-1));
        state = transitions.prepareKeys(state, A, NEW_A, at(-1));
        return new SimulationScenario("hand-enumerated-chain", at(0), at(5), state, CONSTRAINTS,
                profile(Duration.ofSeconds(100), Duration.ofSeconds(1)), List.of(contact("C1", A, 0, 5)), List.of());
    }

    private PlanningObservation keyedObservation(long end) {
        var initial = initialState();
        var keys = new TreeMap<String, KeyState>();
        initial.keys().values().stream().filter(k -> k.contextId().equals(A)).forEach(k -> keys.put(k.id(), k));
        var oneContext = new SecurityState(SATELLITE, initial.asOf(), Map.of(A, initial.context(A)), keys);
        var input = new SimulationScenario("canonical-open-reuse-tie", at(0), at(end), oneContext, CONSTRAINTS,
                profile(Duration.ofSeconds(100), Duration.ofSeconds(1)), List.of(contact("C1", A, 0, end)), List.of());
        return PlanningObservation.from(simulation.start(input), 1);
    }

    private PlanningObservation shortRoute(ContextId third) {
        var input = new SimulationScenario("private-third-" + third.groundStationId(), at(0), at(11), initialState(), CONSTRAINTS,
                profile(Duration.ofSeconds(100), Duration.ofSeconds(1)),
                List.of(contact("C1", A, 0, 3), contact("C2", B, 4, 7), contact("C3", third, 8, 11)), List.of());
        return PlanningObservation.from(simulation.start(input), 1);
    }

    private PlanningObservation causalRoute(ContextId third, int horizon) {
        var transitions = new LifecycleTransitions(CONSTRAINTS);
        var state = transitions.createContext(SecurityState.empty(SATELLITE, at(-150)), A, "A-up-SA", "A-down-SA", at(-150));
        state = transitions.createContext(state, B, "B-up-SA", "B-down-SA", at(-150));
        state = provisionHistory(transitions, state, A, OLD_A, -150, -144, -140);
        state = provisionHistory(transitions, state, B, OLD_B, -40, -30, -20);
        state = transitions.prepareKeys(state, A, NEW_A, at(-1));
        var payload = new SimulationContact.PayloadProfile(100, 100, 100, 0, 100, 0);
        var input = new SimulationScenario("reduced-synthetic-causal-" + third.groundStationId(), at(0), at(8), state,
                CONSTRAINTS, profile(Duration.ofSeconds(100), Duration.ofSeconds(1)),
                List.of(new SimulationContact(window("C1", A, 0, 4), payload),
                        new SimulationContact(window("C2", B, 4, 6), payload),
                        new SimulationContact(window("C3", third, 6, 8), payload)), List.of());
        return PlanningObservation.from(simulation.start(input), horizon);
    }

    private SecurityState provisionHistory(LifecycleTransitions transitions, SecurityState state, ContextId context,
                                           KeyPairIds keys, long creation, long activation, long end) {
        var past = window("history-" + context.groundStationId(), context, activation, end);
        state = transitions.prepareKeys(state, context, keys, at(creation));
        state = transitions.confirmInstallation(state, context, keys, past, at(activation));
        state = transitions.activateKeys(state, context, keys, past, at(activation));
        state = transitions.bindKeys(state, context, keys, past, at(activation));
        state = transitions.startContext(state, context, past, at(activation));
        state = transitions.recordProtectedTraffic(state, context, at(activation + 1), at(end), 1, 1);
        return transitions.stopContext(state, context, at(end));
    }

    private SimulationContact contact(String id, ContextId context, long start, long end) {
        return new SimulationContact(window(id, context, start, end),
                new SimulationContact.PayloadProfile(100, 100, 100, 100, 100, 100));
    }

    private SecurityCostProfile profile(Duration prepare, Duration online) {
        var costs = new EnumMap<SecurityAction, SecurityCostProfile.OperationCost>(SecurityAction.class);
        costs.put(SecurityAction.PREPARE, cpu(prepare));
        for (var action : List.of(SecurityAction.INSTALL, SecurityAction.SWITCH, SecurityAction.OPEN, SecurityAction.REUSE))
            costs.put(action, cpu(online));
        costs.put(SecurityAction.STOP, new SecurityCostProfile.OperationCost(List.of()));
        costs.put(SecurityAction.EXPIRE_CONTEXT, new SecurityCostProfile.OperationCost(List.of()));
        return new SecurityCostProfile("synthetic-exact-small-tree", SecurityCostProfile.Provenance.SYNTHETIC_ASSUMPTION,
                "Hand-enumerable test only: one-second online steps; preparation longer than the horizon", costs);
    }

    private SecurityCostProfile.OperationCost cpu(Duration duration) {
        return new SecurityCostProfile.OperationCost(List.of(
                new SecurityCostProfile.Phase(SecurityCostProfile.Resource.GROUND_CPU, duration, 0)));
    }

    private PlanningAction waitAction(long from, long until) {
        return new PlanningAction(WAIT, at(from), at(until), null, null, null);
    }

    private PlanningBranch assertTerminalReplay(PlanningObservation observation, HorizonPlanResult result) {
        var engine = new BranchEngine();
        var branch = engine.create(observation);
        for (var action : result.bestPlan()) branch = engine.execute(branch, action);
        assertThat(branch.snapshot().at()).isEqualTo(observation.evaluationEnd());
        assertThat(new ContinuationEvaluator().evaluate(branch).evaluation()).isEqualTo(result.score());
        return branch;
    }
}
