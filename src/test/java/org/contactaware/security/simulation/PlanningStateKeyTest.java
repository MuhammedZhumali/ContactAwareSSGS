package org.contactaware.security.simulation;

import org.contactaware.security.*;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import java.util.function.Consumer;
import static org.assertj.core.api.Assertions.*;
import static org.contactaware.security.simulation.HorizonWitness.*;
import static org.contactaware.security.simulation.ExecutionSnapshot.*;

class PlanningStateKeyTest {
    private final BranchEngine engine = new BranchEngine();
    private final SecurityActionGenerator generator = new SecurityActionGenerator();

    @Test void completedOpenAndReuseHaveEquivalentFutureDespiteDifferentJournalAndPrefix() {
        var opened = completed(PlanningAction.Kind.OPEN);
        var reused = completed(PlanningAction.Kind.REUSE);
        assertThat(opened.plan()).isNotEqualTo(reused.plan());
        assertThat(opened.snapshot().journal()).isNotEqualTo(reused.snapshot().journal());
        assertThat(PlanningStateKey.from(opened.snapshot())).isEqualTo(PlanningStateKey.from(reused.snapshot()));
        assertThat(generator.candidates(opened.observation())).isEqualTo(generator.candidates(reused.observation()));

        var action = candidate(opened, PlanningAction.Kind.CONTINUE);
        var left = engine.execute(opened, action);
        var right = engine.execute(reused, action);
        assertThat(PlanningStateKey.from(left.snapshot())).isEqualTo(PlanningStateKey.from(right.snapshot()));
        assertThat(new ContinuationEvaluator().evaluate(left).evaluation())
                .isEqualTo(new ContinuationEvaluator().evaluate(right).evaluation());
    }

    @Test void journalPresentationIsIgnoredButAllConsumedAggregatesAndRejectionAreRetained() {
        var base = completed(PlanningAction.Kind.OPEN).snapshot();
        var key = PlanningStateKey.from(base);
        assertThat(PlanningStateKey.from(append(base, event(SimulationEvent.Type.CONTACT_STARTED,
                ResourceUsage.ZERO, Duration.ZERO, 0, "Extra presentation-only historical event")))).isEqualTo(key);
        var cost = new ResourceUsage(Duration.ofMillis(1), Duration.ofMillis(1), Duration.ZERO,
                Duration.ZERO, 7, 9);
        assertThat(PlanningStateKey.from(append(base, event(SimulationEvent.Type.STEP_COMPLETED,
                cost, Duration.ZERO, 0, "Already recorded work")))).isNotEqualTo(key);
        assertThat(PlanningStateKey.from(append(base, event(SimulationEvent.Type.PAYLOAD_TRANSFERRED,
                ResourceUsage.ZERO, Duration.ofNanos(1), 0, "Already completed payload")))).isNotEqualTo(key);
        assertThat(PlanningStateKey.from(append(base, event(SimulationEvent.Type.STEP_COMPLETED,
                ResourceUsage.ZERO, Duration.ZERO, 2, "Previously created material")))).isNotEqualTo(key);
        for (var failure : List.of(SimulationEvent.Type.ACTION_FAILED, SimulationEvent.Type.PAYLOAD_REJECTED)) {
            var rejected = PlanningStateKey.from(append(base, event(failure, ResourceUsage.ZERO, Duration.ZERO, 0, "Rejected")));
            assertThat(rejected).isNotEqualTo(key);
            assertThat(rejected.rejected()).isTrue();
        }
    }

    @Test void issuedIdentifiersUncertaintyAndDeliveryDirectionRemainDistinct() {
        var base = completed(PlanningAction.Kind.OPEN).snapshot();
        var key = PlanningStateKey.from(base);
        assertThat(PlanningStateKey.from(alter(base, d -> d.keyIds.add("failed-preparation-id")))).isNotEqualTo(key);
        assertThat(PlanningStateKey.from(alter(base, d -> d.operationIds.add("failed-operation-id")))).isNotEqualTo(key);
        assertThat(PlanningStateKey.from(alter(base, d -> d.uncertain.add(A)))).isNotEqualTo(key);
        assertThat(PlanningStateKey.from(alter(base, d -> d.progress.put("C1", new PayloadProgress(0, 0, false)))))
                .isNotEqualTo(key);
    }

    @Test void pendingWorkResourceIntervalsAndQueueOrderRemainDistinct() {
        var root = root();
        var pending = engine.execute(root, candidate(root, PlanningAction.Kind.INSTALL)).snapshot();
        var key = PlanningStateKey.from(pending);
        assertThat(PlanningStateKey.from(alter(pending, d -> d.pending.clear()))).isNotEqualTo(key);
        assertThat(PlanningStateKey.from(alter(pending, d -> {
            var operation = d.pending.getFirst();
            var reservations = new ArrayList<>(operation.reservations());
            reservations.add(new ResourceReservation("ground:other", at(0), at(1)));
            d.pending.set(0, new PendingOperation(operation.planned(), operation.steps(), reservations,
                    operation.stepIndex(), operation.stepStartedAt(), operation.stepKeys()));
        }))).isNotEqualTo(key);
        assertThat(PlanningStateKey.from(alter(pending, d -> d.nextInsertion++))).isNotEqualTo(key);
        assertThat(PlanningStateKey.from(alter(pending, d -> d.expiry.add("previous-key:previous-deadline")))).isNotEqualTo(key);
    }

    @Test void factualCounterProgressAndCommittedFrameArePartOfTheKey() {
        var opened = completed(PlanningAction.Kind.OPEN);
        var afterFrame = engine.execute(opened, candidate(opened, PlanningAction.Kind.CONTINUE)).snapshot();
        assertThat(afterFrame.securityState().key(OLD_A.uplink()).nextNonceCounter())
                .isGreaterThan(opened.snapshot().securityState().key(OLD_A.uplink()).nextNonceCounter());
        assertThat(PlanningStateKey.from(afterFrame)).isNotEqualTo(PlanningStateKey.from(opened.snapshot()));
        var inFlight = new SimulationEngine().planningAdvance(opened.snapshot(), at(1).plusMillis(125), true);
        var waiting = new SimulationEngine().planningAdvance(opened.snapshot(), at(1).plusMillis(125), false);
        assertThat(inFlight.at()).isEqualTo(waiting.at());
        assertThat(inFlight.inFlightFrame()).isNotNull();
        assertThat(waiting.inFlightFrame()).isNull();
        assertThat(PlanningStateKey.from(inFlight)).isNotEqualTo(PlanningStateKey.from(waiting));
    }

    @Test void privilegedExecutionSnapshotsCannotBeUsedAsPlanningEquivalenceKeys() {
        var real = new SimulationEngine().start(HorizonWitness.scenario(Sequence.X, Plan.NEXT));
        assertThatThrownBy(() -> PlanningStateKey.from(real)).isInstanceOf(IllegalArgumentException.class);
    }

    private PlanningBranch root() { return engine.create(HorizonPlannerMain.observation(Sequence.X, 1)); }

    private PlanningBranch completed(PlanningAction.Kind kind) {
        var root = root();
        var started = engine.execute(root, candidate(root, kind));
        return engine.execute(started, candidate(started, PlanningAction.Kind.WAIT));
    }

    private PlanningAction candidate(PlanningBranch branch, PlanningAction.Kind kind) {
        return generator.candidates(branch.observation()).stream().filter(a -> a.kind() == kind).findFirst().orElseThrow();
    }

    private SimulationEvent event(SimulationEvent.Type type, ResourceUsage cost, Duration useful, long created, String text) {
        return new SimulationEvent(999, at(1), type, null, null, null, null, null,
                cost, useful, 0, 0, 0, 0, created, null, text);
    }

    private ExecutionSnapshot append(ExecutionSnapshot source, SimulationEvent event) {
        return alter(source, d -> d.journal.add(event));
    }

    private ExecutionSnapshot alter(ExecutionSnapshot source, Consumer<Draft> change) {
        var draft = new Draft(source);
        change.accept(draft);
        return new ExecutionSnapshot(source.scenario(), source.at(), source.securityState(), draft.pending,
                source.queuedEvents(), draft.nextInsertion, draft.expiry, draft.journal, draft.uncertain, draft.progress,
                draft.frame, source.carriedRecordedCosts(), source.carriedUsefulTime(), source.carriedCreatedKeys(),
                draft.keyIds, draft.operationIds, source.executionMode());
    }

    private static final class Draft {
        final List<PendingOperation> pending;
        final List<SimulationEvent> journal;
        final Set<String> keyIds, operationIds, expiry;
        final Set<ContextId> uncertain;
        final Map<String, PayloadProgress> progress;
        InFlightFrame frame;
        long nextInsertion;
        Draft(ExecutionSnapshot source) {
            pending = new ArrayList<>(source.pendingOperations());
            journal = new ArrayList<>(source.journal());
            keyIds = new HashSet<>(source.issuedKeyIds());
            operationIds = new HashSet<>(source.issuedOperationIds());
            expiry = new HashSet<>(source.registeredExpiryEvents());
            uncertain = new HashSet<>(source.uncertainContexts());
            progress = new HashMap<>(source.payloadProgress());
            frame = source.inFlightFrame();
            nextInsertion = source.nextInsertion();
        }
    }
}
