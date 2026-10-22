package org.contactaware.security.simulation;

import org.contactaware.contacts.Model.ContactWindow;
import org.contactaware.security.*;
import org.junit.jupiter.api.Test;

import java.time.*;
import java.util.*;

import static org.assertj.core.api.Assertions.*;
import static org.contactaware.security.simulation.HorizonWitness.*;

/** Exhaustive oracles for the whole-frame lattice and the fixed geometric service set. */
class PlanningFrameCapacityBoundTest {
    private final BranchEngine engine = new BranchEngine();
    private final SecurityActionGenerator generator = new SecurityActionGenerator();
    private final PlanningOptimisticBound bound = new PlanningOptimisticBound();
    private final PlanningResultComparator comparator = new PlanningResultComparator();
    private final ContinuationEvaluator evaluator = new ContinuationEvaluator();

    @Test void pendingInstallationContinuousCapacityIsRoundedDownToWholeFrameQuantum() {
        var transitions = new LifecycleTransitions(CONSTRAINTS);
        var state = transitions.createContext(SecurityState.empty(SATELLITE, at(-1)), A, "frame-up", "frame-down", at(-1));
        state = transitions.prepareKeys(state, A, NEW_A, at(-1));
        var observation = observation(state, contact("A", A, 0, 6500, payload(200, 400, 0, 200, 0)));
        var branch = engine.create(observation);
        branch = engine.execute(branch, action(branch, PlanningAction.Kind.INSTALL, A));
        // The pending online step grants instantaneous opening in the local relaxation.
        // The common pool leaves 6.5 - I1 - S1 - O1 = 3.5 s, but only 2 s frames count.
        var expected = new BranchEvaluation(1, Duration.ofSeconds(2), Duration.ofSeconds(3), 0, 0);
        assertThat(bound.upperBound(branch)).isEqualTo(expected);
        validateEveryNode(branch);
        var restarted = PlanningObservation.from(branch.snapshot(), 2);
        validateEveryNode(engine.create(restarted));
        compareSearches(restarted, expected);
    }

    @Test void unequalFrameDurationsUseTheirGreatestCommonDivisorRatherThanOneContactPeriod() {
        var observation = unequalFrames();
        var expected = new BranchEvaluation(2, Duration.ofMillis(5500), Duration.ofSeconds(2), 0, 0);
        assertThat(bound.upperBound(engine.create(observation))).isEqualTo(expected);
        validateEveryNode(engine.create(observation));
        compareSearches(observation, expected);
    }

    @Test void partiallyElapsedUnequalFrameRetainsItsWholeDurationAndGeometricEligibility() {
        var branch = engine.create(unequalFrames());
        branch = engine.execute(branch, action(branch, PlanningAction.Kind.OPEN, A));
        branch = engine.execute(branch, action(branch, PlanningAction.Kind.WAIT, null));
        var partial = new SimulationEngine().planningAdvance(branch.snapshot(), at(1).plusMillis(750), true);
        assertThat(partial.inFlightFrame()).isNotNull();
        var restarted = PlanningObservation.from(partial, 2);
        var expected = new BranchEvaluation(2, Duration.ofMillis(5500), Duration.ofSeconds(1), 0, 0);
        assertThat(bound.upperBound(engine.create(restarted))).isEqualTo(expected);
        validateEveryNode(engine.create(restarted));
        compareSearches(restarted, expected);
    }

    @Test void geometricImpossibilityDoesNotDisableMandatoryCostsForOtherContacts() {
        var transitions = new LifecycleTransitions(CONSTRAINTS);
        var state = transitions.createContext(SecurityState.empty(SATELLITE, at(-1)), A, "A-up", "A-down", at(-1));
        state = transitions.createContext(state, B, "B-up", "B-down", at(-1));
        state = transitions.prepareKeys(state, B, new KeyPairIds("B-new-up", "B-new-down"), at(-1));
        var observation = observation(state,
                contact("too-short-A", A, 0, 1500, payload(200, 200, 0, 200, 0)),
                contact("possible-B", B, 2000, 6500, payload(100, 100, 0, 100, 0)));
        var expected = new BranchEvaluation(1, Duration.ofSeconds(1), Duration.ofSeconds(3), 0, 0);
        assertThat(bound.upperBound(engine.create(observation))).isEqualTo(expected);
        validateEveryNode(engine.create(observation));
        compareSearches(observation, expected);
    }

    @Test void geometricallyUnservableContactStillContributesOptionalUsefulPayload() {
        var observation = observation(initialState(),
                contact("unservable-A", A, 0, 2500, payload(100, 300, 0, 300, 0)),
                contact("servable-B", B, 3000, 6500, payload(100, 200, 0, 100, 0)));
        // Only B is mandatory. A's optional OPEN is free in the bound, but its useful frame
        // remains available; a real optimum spends one OPEN at each station.
        assertThat(bound.upperBound(engine.create(observation)))
                .isEqualTo(new BranchEvaluation(1, Duration.ofSeconds(3), Duration.ofSeconds(1), 0, 0));
        validateEveryNode(engine.create(observation));
        compareSearches(observation, new BranchEvaluation(1, Duration.ofSeconds(3), Duration.ofSeconds(2), 0, 0));
    }

    @Test void geometricallyUnservableEarlierWindowCanStageRenewalForAServableReturn() {
        var transitions = new LifecycleTransitions(CONSTRAINTS);
        var state = transitions.prepareKeys(initialState(), A, NEW_A, at(-1));
        var observation = observation(state,
                contact("staging-A", A, 0, 4000, payload(100, 500, 0, 500, 0)),
                contact("return-A", A, 70000, 72000, payload(100, 100, 0, 100, 0)));
        assertThat(bound.upperBound(engine.create(observation)))
                .isEqualTo(new BranchEvaluation(1, Duration.ofSeconds(3), Duration.ofSeconds(3), 0, 0));
        validateEveryNode(engine.create(observation));
        var result = compareSearches(observation, new BranchEvaluation(1, Duration.ofSeconds(2), Duration.ofSeconds(4), 0, 0));
        assertThat(result.bestPlan()).anyMatch(a -> a.kind() == PlanningAction.Kind.INSTALL && "staging-A".equals(a.contactId()));
    }

    @Test void alreadyServedProgressRemainsEligibleButAnImpossibleReturnDoesNotRequireRenewal() {
        var observation = observation(initialState(),
                contact("current-A", A, 0, 4000, payload(100, 200, 0, 100, 0)),
                contact("too-short-return-A", A, 70000, 70500, payload(100, 100, 0, 100, 0)));
        var branch = engine.create(observation);
        branch = engine.execute(branch, action(branch, PlanningAction.Kind.OPEN, A));
        branch = engine.execute(branch, action(branch, PlanningAction.Kind.WAIT, null));
        branch = engine.execute(branch, action(branch, PlanningAction.Kind.CONTINUE, A));
        var restarted = PlanningObservation.from(branch.snapshot(), 2);
        var expected = new BranchEvaluation(0, Duration.ofSeconds(1), Duration.ZERO, 0, 0);
        assertThat(bound.upperBound(engine.create(restarted))).isEqualTo(expected);
        validateEveryNode(engine.create(restarted));
        compareSearches(restarted, expected);
    }

    @Test void insufficientOfferedDirectionIsGeometricallyImpossibleEvenWithAmpleTime() {
        var observation = observation(initialState(),
                contact("no-downlink-A", A, 0, 3000, payload(100, 200, 0, 100, 100)),
                contact("possible-B", B, 4000, 6500, payload(100, 100, 0, 100, 0)));
        assertThat(bound.upperBound(engine.create(observation)))
                .isEqualTo(new BranchEvaluation(1, Duration.ofSeconds(3), Duration.ofSeconds(1), 0, 0));
        validateEveryNode(engine.create(observation));
        compareSearches(observation, new BranchEvaluation(1, Duration.ofSeconds(3), Duration.ofSeconds(2), 0, 0));
    }

    private PlanningObservation unequalFrames() {
        return observation(initialState(),
                contact("A", A, 0, 4250, payload(150, 300, 0, 150, 0)),
                contact("B", B, 5000, 9250, payload(250, 250, 0, 250, 0)));
    }

    private PlanningObservation observation(SecurityState state, SimulationContact... contacts) {
        var end = contacts[contacts.length - 1].window().end();
        var scenario = new SimulationScenario("synthetic-frame-capacity-oracle", at(0), end, state, CONSTRAINTS,
                costs(), List.of(contacts), List.of());
        return PlanningObservation.from(new SimulationEngine().start(scenario), contacts.length);
    }

    private SimulationContact contact(String id, ContextId context, long startMillis, long endMillis,
                                      SimulationContact.PayloadProfile payload) {
        return new SimulationContact(new ContactWindow(id, SATELLITE, context.groundStationId(),
                EPOCH.plusMillis(startMillis), EPOCH.plusMillis(endMillis), null, null, List.of()), payload);
    }

    private SimulationContact.PayloadProfile payload(long frameBytes, long offeredUp, long offeredDown, long minimumUp, long minimumDown) {
        return new SimulationContact.PayloadProfile(100, frameBytes, offeredUp, offeredDown, minimumUp, minimumDown);
    }

    private SecurityCostProfile costs() {
        var operations = new EnumMap<SecurityAction, SecurityCostProfile.OperationCost>(SecurityAction.class);
        for (var action : List.of(SecurityAction.PREPARE, SecurityAction.INSTALL, SecurityAction.SWITCH, SecurityAction.OPEN, SecurityAction.REUSE))
            operations.put(action, new SecurityCostProfile.OperationCost(List.of(new SecurityCostProfile.Phase(
                    SecurityCostProfile.Resource.GROUND_CPU, Duration.ofSeconds(action == SecurityAction.PREPARE ? 100 : 1), 0))));
        operations.put(SecurityAction.STOP, new SecurityCostProfile.OperationCost(List.of()));
        operations.put(SecurityAction.EXPIRE_CONTEXT, new SecurityCostProfile.OperationCost(List.of()));
        return new SecurityCostProfile("synthetic-frame-capacity-cost", SecurityCostProfile.Provenance.SYNTHETIC_ASSUMPTION,
                "Small exhaustive trees with one-second online primitives and no fitting new preparation", operations);
    }

    private PlanningAction action(PlanningBranch branch, PlanningAction.Kind kind, ContextId context) {
        return generator.candidates(branch.observation()).stream().filter(a -> a.kind() == kind && Objects.equals(a.contextId(), context))
                .findFirst().orElseThrow();
    }

    private void validateEveryNode(PlanningBranch root) {
        var visited = new long[1];
        assertThat(descendants(root, visited)).isNotEmpty();
        assertThat(visited[0]).isPositive();
    }

    private List<BranchEvaluation> descendants(PlanningBranch branch, long[] visited) {
        visited[0]++;
        if (branch.snapshot().journal().stream().anyMatch(e -> e.type() == SimulationEvent.Type.ACTION_FAILED
                || e.type() == SimulationEvent.Type.PAYLOAD_REJECTED)) return List.of();
        var optimistic = bound.upperBound(branch);
        assertThat(new PlanningOptimisticBound(branch.origin()).upperBound(branch)).isEqualTo(optimistic);
        var terminals = new ArrayList<BranchEvaluation>();
        if (branch.snapshot().at().equals(branch.origin().evaluationEnd())) terminals.add(evaluator.evaluate(branch).evaluation());
        else for (var candidate : generator.candidates(branch.observation())) terminals.addAll(descendants(engine.execute(branch, candidate), visited));
        for (var terminal : terminals) assertThat(comparator.compareMetrics(optimistic, terminal))
                .as("Bound %s at %s after %s must dominate %s", optimistic, branch.snapshot().at(), branch.plan(), terminal)
                .isLessThanOrEqualTo(0);
        return terminals;
    }

    private HorizonPlanResult compareSearches(PlanningObservation observation, BranchEvaluation expected) {
        var reference = new ExactHorizonPlanner(SearchLimits.unlimited(), PlannerOptions.reference()).plan(observation);
        var accelerated = new ExactHorizonPlanner(SearchLimits.unlimited(), PlannerOptions.accelerated()).plan(observation);
        var reversed = new ExactHorizonPlanner(SearchLimits.unlimited(), PlannerOptions.accelerated(), actions -> {
            var copy = new ArrayList<>(actions);
            Collections.reverse(copy);
            return copy;
        }).plan(observation);
        assertThat(reference.status()).isEqualTo(HorizonPlanResult.Status.COMPLETE);
        assertThat(reference.score()).isEqualTo(expected);
        for (var result : List.of(accelerated, reversed)) {
            assertThat(result.status()).isEqualTo(HorizonPlanResult.Status.COMPLETE);
            assertThat(result.score()).isEqualTo(reference.score());
            assertThat(result.bestPlan()).isEqualTo(reference.bestPlan());
        }
        return accelerated;
    }
}
