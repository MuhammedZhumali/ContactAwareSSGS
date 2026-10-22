package org.contactaware.security.simulation;

import org.contactaware.security.*;
import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.contactaware.security.simulation.HorizonWitness.*;

class PlanningActionReductionTest {
    @Test void preExecutionWaitCoalescingPreservesSnapshotsForPendingFramesOperationsAndExpiry() {
        var costs = SecurityCostProfile.syntheticWitness();
        var contacts = List.of(contact("C1", A, 0, 20), contact("C2", B, 30, 50));
        var engine = new SimulationEngine();
        var normal = new SimulationScenario("wait-reduction", at(-2), at(50), initialState(), CONSTRAINTS,
                costs, contacts, List.of(operation("opening", SecurityAction.REUSE, 0, A, null, "C1")));
        var started = engine.start(normal);
        assertWaitEquivalence(PlanningObservation.from(engine.advance(started, at(0)), 1));
        assertWaitEquivalence(PlanningObservation.from(engine.advance(started, at(1).plusMillis(125)), 1));

        var preparing = new SimulationScenario("wait-pending-preparation", at(-2), at(50), initialState(), CONSTRAINTS,
                costs, contacts, List.of(operation("preparing", SecurityAction.PREPARE, -2, A, NEW_A, null)));
        assertWaitEquivalence(PlanningObservation.from(engine.advance(engine.start(preparing), at(-1)), 2));

        var expiry = new SimulationScenario("wait-expiry", at(60), at(120), initialState(), CONSTRAINTS, costs,
                List.of(contact("late-A", A, 60, 100), contact("late-B", B, 110, 120)), List.of());
        assertWaitEquivalence(PlanningObservation.from(engine.start(expiry), 1));
    }

    @Test void aGeneratedTerminalOnlyWaitIsKeptAndNoOtherActionIsReordered() {
        var observation = HorizonPlannerMain.observation(Sequence.X, 1);
        var generated = new SecurityActionGenerator().candidates(observation);
        var reversed = new ArrayList<>(generated);
        Collections.reverse(reversed);
        var reduced = PlanningActionReduction.coalesceWaits(observation, reversed);
        assertThat(reduced.stream().filter(a -> a.kind() != PlanningAction.Kind.WAIT).toList())
                .containsExactlyElementsOf(reversed.stream().filter(a -> a.kind() != PlanningAction.Kind.WAIT).toList());
        var wait = reduced.stream().filter(a -> a.kind() == PlanningAction.Kind.WAIT).findFirst().orElseThrow();
        assertThat(PlanningActionReduction.coalesceWaits(observation, List.of(wait))).containsExactly(wait);
        assertThat(PlanningActionReduction.coalesceWaits(observation, List.of())).isEmpty();
    }

    @Test void onlyAnExistingFirstDifferenceProvesTheCanonicalPrefixWorse() {
        var open = new PlanningAction(PlanningAction.Kind.OPEN, at(0), at(1), A, null, "C1");
        var reuse = new PlanningAction(PlanningAction.Kind.REUSE, at(0), at(1), A, null, "C1");
        var wait = new PlanningAction(PlanningAction.Kind.WAIT, at(1), at(20), null, null, null);
        assertThat(PlanningActionReduction.prefixAlreadyWorse(List.of(reuse), List.of(open, wait))).isTrue();
        assertThat(PlanningActionReduction.prefixAlreadyWorse(List.of(open), List.of(reuse, wait))).isFalse();
        assertThat(PlanningActionReduction.prefixAlreadyWorse(List.of(open), List.of(open, wait))).isFalse();
        assertThat(PlanningActionReduction.prefixAlreadyWorse(List.of(open, wait), List.of(open))).isFalse();
        assertThat(PlanningActionReduction.prefixAlreadyWorse(List.of(open, wait), List.of(open, wait))).isFalse();
        assertThat(PlanningActionReduction.prefixAlreadyWorse(List.of(), List.of(open))).isFalse();
        var earlierWait = new PlanningAction(PlanningAction.Kind.WAIT, at(1), at(19), null, null, null);
        assertThat(PlanningActionReduction.prefixAlreadyWorse(List.of(open, wait), List.of(open, earlierWait))).isTrue();
    }

    @Test void offHorizonPreparationCannotBeDroppedBecauseItsCompletionCreatesALegalDelayedStart() {
        var constraints = new SecurityConstraints(Duration.ofSeconds(9), Duration.ofSeconds(100),
                Duration.ofSeconds(100), Set.of(A, B), Set.of());
        var transitions = new LifecycleTransitions(constraints);
        var state = transitions.createContext(SecurityState.empty(SATELLITE, at(-2)), A, "up-A", "down-A", at(-2));
        state = transitions.createContext(state, B, "up-B", "down-B", at(-2));
        state = transitions.prepareKeys(state, A, NEW_A, at(-1));
        state = transitions.confirmInstallation(state, A, NEW_A, window("historical-install", A, -1, 0), at(-1));
        var costs = new EnumMap<SecurityAction, SecurityCostProfile.OperationCost>(SecurityAction.class);
        for (var action : List.of(SecurityAction.PREPARE, SecurityAction.INSTALL, SecurityAction.SWITCH,
                SecurityAction.OPEN, SecurityAction.REUSE)) {
            long seconds = action == SecurityAction.PREPARE ? 4 : action == SecurityAction.INSTALL ? 3 : 1;
            costs.put(action, new SecurityCostProfile.OperationCost(List.of(new SecurityCostProfile.Phase(
                    SecurityCostProfile.Resource.GROUND_CPU, Duration.ofSeconds(seconds), 0))));
        }
        costs.put(SecurityAction.STOP, new SecurityCostProfile.OperationCost(List.of()));
        costs.put(SecurityAction.EXPIRE_CONTEXT, new SecurityCostProfile.OperationCost(List.of()));
        var profile = new SecurityCostProfile("synthetic-extra-decision-time", SecurityCostProfile.Provenance.SYNTHETIC_ASSUMPTION,
                "Finite-start counterexample: irrelevant key generation creates a useful decision time", costs);
        // One UL frame is required; no DL frame is required. C2 fits OPEN + that frame.
        var payload = new SimulationContact.PayloadProfile(100, 100, Long.MAX_VALUE, 0, 100, 0);
        var scenario = new SimulationScenario("off-horizon-preparation-timer", at(0), at(14), state, constraints, profile,
                List.of(new SimulationContact(window("C1", A, 0, 9), payload),
                        new SimulationContact(window("C2", A, 12, 14), payload)), List.of());
        var root = new BranchEngine().create(PlanningObservation.from(new SimulationEngine().start(scenario), 1));
        var full = enumerate(root, false);
        var filtered = enumerate(root, true);
        assertThat(full.evaluation().servedContacts()).isEqualTo(2);
        assertThat(filtered.evaluation().servedContacts()).isEqualTo(1);
        assertThat(new PlanningResultComparator().compare(full, filtered)).isNegative();
        assertThat(full.plan()).anyMatch(a -> a.kind() == PlanningAction.Kind.PREPARE && B.equals(a.contextId()));
        assertThat(full.plan()).anyMatch(a -> a.kind() == PlanningAction.Kind.SWITCH && a.at().equals(at(4)));
    }

    private PlanningResult enumerate(PlanningBranch branch, boolean discardOffHorizonPreparation) {
        if (branch.snapshot().at().equals(branch.origin().evaluationEnd())) return new ContinuationEvaluator().evaluate(branch);
        var observation = branch.observation();
        var actions = PlanningActionReduction.coalesceWaits(observation, new SecurityActionGenerator().candidates(observation));
        PlanningResult best = null;
        var engine = new BranchEngine();
        var comparator = new PlanningResultComparator();
        for (var action : actions) {
            if (discardOffHorizonPreparation && action.kind() == PlanningAction.Kind.PREPARE && B.equals(action.contextId())) continue;
            var candidate = enumerate(engine.execute(branch, action), discardOffHorizonPreparation);
            if (candidate != null && (best == null || comparator.compare(candidate, best) < 0)) best = candidate;
        }
        return best;
    }

    private void assertWaitEquivalence(PlanningObservation observation) {
        var branches = new BranchEngine();
        var branch = branches.create(observation);
        var observed = branch.observation();
        var generated = new SecurityActionGenerator().candidates(observed);
        var waits = generated.stream().filter(a -> a.kind() == PlanningAction.Kind.WAIT).toList();
        assertThat(waits).hasSizeGreaterThan(1);
        var reversed = new ArrayList<>(generated);
        Collections.reverse(reversed);
        var kept = PlanningActionReduction.coalesceWaits(observed, reversed).stream()
                .filter(a -> a.kind() == PlanningAction.Kind.WAIT).toList();
        assertThat(kept).containsExactly(waits.stream().min(new PlanningResultComparator()::compareActions).orElseThrow());
        var expected = branches.execute(branch, kept.getFirst()).snapshot();
        for (var wait : waits) assertThat(branches.execute(branch, wait).snapshot()).isEqualTo(expected);
    }

    private SimulationContact contact(String id, ContextId context, long start, long end) {
        return new SimulationContact(window(id, context, start, end), SimulationContact.PayloadProfile.syntheticWitness());
    }
}
