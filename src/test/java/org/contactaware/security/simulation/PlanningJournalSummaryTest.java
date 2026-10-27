package org.contactaware.security.simulation;

import org.contactaware.security.*;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.*;
import java.util.function.UnaryOperator;

import static org.assertj.core.api.Assertions.*;
import static org.contactaware.security.simulation.HorizonWitness.*;

class PlanningJournalSummaryTest {
    private final BranchEngine engine = new BranchEngine();
    private final SecurityActionGenerator generator = new SecurityActionGenerator();

    @Test void onePassValuesMatchOriginalSnapshotFoldsAtEverySmallTreeNode() {
        var pending = new ArrayDeque<PlanningBranch>();
        pending.push(engine.create(fresh()));
        long visited = 0;
        boolean sawPending = false, sawPayload = false, sawCreatedKeys = false;
        while (!pending.isEmpty()) {
            var branch = pending.pop();
            assertThat(++visited).isLessThan(20_000);
            assertEquivalent(branch.snapshot());
            sawPending |= !branch.snapshot().pendingOperations().isEmpty();
            sawPayload |= !branch.snapshot().usefulSecureTime().isZero();
            sawCreatedKeys |= branch.snapshot().createdKeys() > 0;
            for (var action : generator.candidates(branch.observation())) pending.push(engine.execute(branch, action));
        }
        assertThat(sawPending).isTrue();
        assertThat(sawPayload).isTrue();
        assertThat(sawCreatedKeys).isTrue();
    }

    @Test void carriedTotalsPartialWorkAndDiagnosticNullContextsKeepTheirOriginalMeaning() {
        var root = engine.create(fresh());
        var prepare = generator.candidates(root.observation()).stream()
                .filter(action -> action.kind() == PlanningAction.Kind.PREPARE).findFirst().orElseThrow();
        var started = engine.execute(root, prepare);
        var partial = new SimulationEngine().planningAdvance(started.snapshot(), at(0).plusMillis(500), false);
        assertEquivalent(partial);
        assertThat(PlanningJournalSummary.from(partial).accruedCosts().totalCpu()).isEqualTo(Duration.ofMillis(500));

        var journal = new ArrayList<>(partial.journal());
        var usage = new ResourceUsage(Duration.ofMillis(3), Duration.ofMillis(1), Duration.ofMillis(2),
                Duration.ZERO, 7, 9);
        journal.add(new SimulationEvent(100, partial.at(), SimulationEvent.Type.STEP_COMPLETED, null,
                SecurityAction.PREPARE, null, null, null, usage, Duration.ZERO, 0, 0, 0, 0, 2, null, "Diagnostic"));
        journal.add(new SimulationEvent(101, partial.at(), SimulationEvent.Type.PAYLOAD_TRANSFERRED, null,
                null, A, null, null, ResourceUsage.ZERO, Duration.ofNanos(17), 0, 0, 0, 0, 0, null, "Diagnostic"));
        for (var rejected : List.of(SimulationEvent.Type.ACTION_FAILED, SimulationEvent.Type.PAYLOAD_REJECTED)) {
            var withFailure = new ArrayList<>(journal);
            withFailure.add(new SimulationEvent(102, partial.at(), rejected, null, null, null, null, null,
                    ResourceUsage.ZERO, Duration.ZERO, 0, 0, 0, 0, 0, null, "Diagnostic rejection"));
            var snapshot = copy(partial, withFailure, usage, Duration.ofSeconds(11), 6);
            assertEquivalent(snapshot);
            var summary = PlanningJournalSummary.from(snapshot);
            assertThat(summary.rejected()).isTrue();
            assertThat(summary.createdKeys()).isEqualTo(8);
            assertThat(summary.createdByContext()).containsEntry(null, 2L);
            assertThatThrownBy(() -> summary.createdByContext().put(A, 7L))
                    .isInstanceOf(UnsupportedOperationException.class);
            assertThatThrownBy(() -> PlanningStateKey.from(partial, summary))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test void fullDfsAndSummaryReuseReturnEveryMetricAndTheSameWholePlanForBothRulesAndTraversalDirections() {
        UnaryOperator<List<PlanningAction>> reverse = actions -> {
            var copy = new ArrayList<>(actions);
            Collections.reverse(copy);
            return copy;
        };
        for (var observation : List.of(fresh(), reusable())) {
            for (var rule : PlanTieBreak.values()) {
                var reference = new ExactHorizonPlanner(SearchLimits.unlimited(), PlannerOptions.reference(),
                        DecisionTimeModel.V2_EVENT_BOUNDARIES, rule).plan(observation);
                assertThat(reference.status()).isEqualTo(HorizonPlanResult.Status.COMPLETE);
                for (var order : List.<UnaryOperator<List<PlanningAction>>>of(UnaryOperator.identity(), reverse)) {
                    var actual = new ExactHorizonPlanner(SearchLimits.unlimited(), PlannerOptions.acceleratedServiceFirst(),
                            DecisionTimeModel.V2_EVENT_BOUNDARIES, rule, order).plan(observation);
                    assertThat(actual.status()).isEqualTo(HorizonPlanResult.Status.COMPLETE);
                    assertThat(actual.score()).isEqualTo(reference.score());
                    assertThat(actual.firstAction()).isEqualTo(reference.firstAction());
                    assertThat(actual.bestPlan()).containsExactlyElementsOf(reference.bestPlan());
                    var replayed = engine.create(observation);
                    for (var action : actual.bestPlan()) replayed = engine.execute(replayed, action);
                    assertThat(replayed.snapshot().at()).isEqualTo(observation.evaluationEnd());
                    assertThat(new ContinuationEvaluator().evaluate(replayed).evaluation()).isEqualTo(actual.score());
                }
            }
        }
    }

    private void assertEquivalent(ExecutionSnapshot snapshot) {
        var summary = PlanningJournalSummary.from(snapshot);
        assertThat(summary.recordedCosts()).isEqualTo(snapshot.recordedCosts());
        assertThat(summary.accruedCosts()).isEqualTo(snapshot.accruedCosts());
        assertThat(summary.usefulSecureTime()).isEqualTo(snapshot.usefulSecureTime());
        assertThat(summary.createdKeys()).isEqualTo(snapshot.createdKeys());
        assertThat(summary.rejected()).isEqualTo(snapshot.journal().stream().anyMatch(event ->
                event.type() == SimulationEvent.Type.ACTION_FAILED || event.type() == SimulationEvent.Type.PAYLOAD_REJECTED));
        var online = new HashMap<ContextId, ResourceUsage>();
        var preparation = new HashMap<ContextId, ResourceUsage>();
        var created = new HashMap<ContextId, Long>();
        for (var event : snapshot.journal()) {
            if (event.action() != null && event.action().requiresContact())
                online.merge(event.contextId(), event.usage(), ResourceUsage::plus);
            if (event.action() == SecurityAction.PREPARE)
                preparation.merge(event.contextId(), event.usage(), ResourceUsage::plus);
            if (event.createdKeys() > 0) created.merge(event.contextId(), event.createdKeys(), Math::addExact);
        }
        assertThat(summary.onlineByContext()).isEqualTo(online);
        assertThat(summary.preparationByContext()).isEqualTo(preparation);
        assertThat(summary.createdByContext()).isEqualTo(created);
        var originalKey = new PlanningStateKey(snapshot.scenario(), snapshot.at(), snapshot.securityState(),
                snapshot.pendingOperations(), snapshot.queuedEvents(), snapshot.nextInsertion(),
                snapshot.registeredExpiryEvents(), snapshot.uncertainContexts(), snapshot.payloadProgress(),
                snapshot.inFlightFrame(), snapshot.carriedRecordedCosts(), snapshot.carriedUsefulTime(),
                snapshot.carriedCreatedKeys(), snapshot.issuedKeyIds(), snapshot.issuedOperationIds(), snapshot.executionMode(),
                snapshot.recordedCosts(), snapshot.usefulSecureTime(), snapshot.createdKeys(), summary.rejected());
        assertThat(PlanningStateKey.from(snapshot, summary)).isEqualTo(originalKey);
    }

    private PlanningObservation fresh() {
        var transitions = new LifecycleTransitions(CONSTRAINTS);
        var state = transitions.createContext(SecurityState.empty(SATELLITE, at(-1)), A, "A-up", "A-down", at(-1));
        return observation(state, List.of(contact("C1", 0, 6)), 6, 1);
    }

    private PlanningObservation reusable() {
        var original = initialState();
        var keys = new TreeMap<String, KeyState>();
        original.keys().values().stream().filter(key -> key.contextId().equals(A)).forEach(key -> keys.put(key.id(), key));
        var state = new SecurityState(SATELLITE, original.asOf(), Map.of(A, original.context(A)), keys);
        return observation(state, List.of(contact("C1", 0, 3), contact("C2", 4, 7)), 7, 100);
    }

    private PlanningObservation observation(SecurityState state, List<SimulationContact> contacts, long end, long preparation) {
        var costs = new EnumMap<SecurityAction, SecurityCostProfile.OperationCost>(SecurityAction.class);
        for (var action : List.of(SecurityAction.PREPARE, SecurityAction.INSTALL, SecurityAction.SWITCH,
                SecurityAction.OPEN, SecurityAction.REUSE))
            costs.put(action, new SecurityCostProfile.OperationCost(List.of(new SecurityCostProfile.Phase(
                    action == SecurityAction.PREPARE ? SecurityCostProfile.Resource.GROUND_CPU : SecurityCostProfile.Resource.SPACECRAFT_CPU,
                    Duration.ofSeconds(action == SecurityAction.PREPARE ? preparation : 1), 0))));
        costs.put(SecurityAction.STOP, new SecurityCostProfile.OperationCost(List.of()));
        costs.put(SecurityAction.EXPIRE_CONTEXT, new SecurityCostProfile.OperationCost(List.of()));
        var profile = new SecurityCostProfile("journal-summary-test", SecurityCostProfile.Provenance.SYNTHETIC_ASSUMPTION,
                "Small exhaustive journal equivalence fixture", costs);
        var scenario = new SimulationScenario("journal-summary", at(0), at(end), state, CONSTRAINTS, profile, contacts, List.of());
        return PlanningObservation.from(new SimulationEngine().start(scenario), 1);
    }

    private SimulationContact contact(String id, long from, long until) {
        return new SimulationContact(window(id, A, from, until),
                new SimulationContact.PayloadProfile(100, 100, 100, 100, 100, 100));
    }

    private ExecutionSnapshot copy(ExecutionSnapshot source, List<SimulationEvent> journal,
                                   ResourceUsage carried, Duration useful, long created) {
        return new ExecutionSnapshot(source.scenario(), source.at(), source.securityState(), source.pendingOperations(),
                source.queuedEvents(), source.nextInsertion(), source.registeredExpiryEvents(), journal,
                source.uncertainContexts(), source.payloadProgress(), source.inFlightFrame(), carried, useful, created,
                source.issuedKeyIds(), source.issuedOperationIds(), source.executionMode());
    }
}
