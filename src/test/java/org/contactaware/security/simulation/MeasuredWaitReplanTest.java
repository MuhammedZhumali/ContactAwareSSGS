package org.contactaware.security.simulation;

import org.contactaware.security.*;
import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import static org.assertj.core.api.Assertions.*;
import static org.contactaware.security.simulation.HorizonWitness.*;
import static org.contactaware.security.simulation.PlanningAction.Kind.*;

class MeasuredWaitReplanTest {
    private static final long PENDING_COMPUTE_NANOS = 1_100_000_000L;
    private static final long READY_COMPUTE_NANOS = 100_000_000L;

    @Test void completedEffectiveWaitWakeTriggersChargedReplanningInsteadOfWaitingToLos() {
        for (var configuration : List.of(HorizonPolicy.next(), HorizonPolicy.multi(2))) {
            var initial = committedSwitch();
            var clock = new AtomicLong();
            var result = controller(configuration, clock, 1).run(initial);
            var execution = result.execution();
            assertThat(execution.status()).isEqualTo(ControlledSimulationResult.Status.FINISHED);
            assertThat(result.latencyDecisions()).hasSize(6).noneMatch(PlanningLatencyController.Decision::deadlineMiss);

            var first = result.latencyDecisions().getFirst();
            assertThat(first.origin().horizon()).isEqualTo(configuration.horizon());
            assertThat(first.selection().action().kind()).isEqualTo(WAIT);
            assertThat(first.selection().action().until()).isEqualTo(at(10));
            assertThat(first.origin().wakeUps()).contains(at(1));
            assertThat(first.availableAt()).isEqualTo(at(1).plusMillis(100));
            assertThat(first.disposition()).isEqualTo("WAIT_EVENT_REACHED_REPLAN");
            assertThat(first.hostNanos()).isEqualTo(PENDING_COMPUTE_NANOS);

            var reopened = result.latencyDecisions().get(1);
            assertThat(reopened.origin().at()).isEqualTo(first.availableAt());
            assertThat(reopened.origin().ongoingOperations()).isEmpty();
            assertThat(reopened.origin().securityState().context(A).status()).isEqualTo(AssociationStatus.KEYED);
            assertThat(reopened.selection().action().kind()).isEqualTo(OPEN);
            assertThat(reopened.hostNanos()).isEqualTo(READY_COMPUTE_NANOS);
            assertThat(reopened.availableAt()).isEqualTo(at(1).plusMillis(200));

            // OPEN is also committed work. Its completion during another long requested
            // WAIT must permit a new, separately measured CONTINUE selection.
            var openWait = result.latencyDecisions().get(2);
            assertThat(openWait.selection().action().until()).isEqualTo(at(10));
            assertThat(openWait.origin().wakeUps()).contains(at(2).plusMillis(200));
            assertThat(openWait.availableAt()).isEqualTo(at(2).plusMillis(300));
            assertThat(openWait.disposition()).isEqualTo("WAIT_EVENT_REACHED_REPLAN");
            var firstFrame = result.latencyDecisions().get(3);
            assertThat(firstFrame.origin().at()).isEqualTo(openWait.availableAt());
            assertThat(firstFrame.selection().action().kind()).isEqualTo(CONTINUE);
            assertThat(firstFrame.hostNanos()).isEqualTo(READY_COMPUTE_NANOS);

            assertThat(execution.decisions()).extracting(d -> d.executedAction().kind())
                    .containsExactly(OPEN, CONTINUE, CONTINUE, WAIT);
            assertThat(execution.decisions().getFirst().at()).isEqualTo(at(1).plusMillis(200));
            assertThat(execution.decisions().getLast().at()).isEqualTo(at(3).plusMillis(100));
            assertThat(execution.decisions()).noneMatch(d -> d.executedAction().kind() == WAIT
                    && d.at().isBefore(at(3)));
            assertThat(execution.computation().selectionNanos()).isEqualTo(2 * PENDING_COMPUTE_NANOS + 4 * READY_COMPUTE_NANOS)
                    .isEqualTo(result.latencyDecisions().stream().mapToLong(PlanningLatencyController.Decision::hostNanos).sum())
                    .isEqualTo(clock.get());
            assertSuccessfulExecution(initial, execution);
        }
    }

    @Test void zeroModeledLatencyExecutesOriginalEffectiveWakeWithoutReplanLoop() {
        var initial = committedSwitch();
        var clock = new AtomicLong();
        var result = controller(HorizonPolicy.next(), clock, 0).run(initial);
        assertThat(result.execution().status()).isEqualTo(ControlledSimulationResult.Status.FINISHED);
        assertThat(result.latencyDecisions()).hasSize(6)
                .noneMatch(d -> d.disposition().equals("WAIT_EVENT_REACHED_REPLAN")
                        || d.disposition().equals("WAIT_EVENT_CHANGED_REPLAN") || d.disposition().equals("STALE_INTENT_REPLAN"));
        assertThat(result.execution().decisions()).extracting(d -> d.executedAction().kind())
                .containsExactly(WAIT, OPEN, WAIT, CONTINUE, CONTINUE, WAIT);
        assertThat(result.execution().decisions().getFirst().resumedAt()).isEqualTo(at(1));
        assertThat(result.execution().computation().selectionNanos())
                .isEqualTo(result.latencyDecisions().stream().mapToLong(PlanningLatencyController.Decision::hostNanos).sum());
        assertSuccessfulExecution(initial, result.execution());
    }

    @Test void interruptedInstallRemovesItsFutureWakeAndChargesASeparateRetryDecision() {
        var initial = interruptedInstallation();
        var clock = new AtomicLong();
        var result = controller(HorizonPolicy.next(), clock, 1).run(initial);
        var execution = result.execution();
        assertThat(execution.status()).isEqualTo(ControlledSimulationResult.Status.FINISHED);
        assertThat(result.latencyDecisions()).hasSize(10).noneMatch(PlanningLatencyController.Decision::deadlineMiss);

        var obsoleteWait = result.latencyDecisions().getFirst();
        assertThat(obsoleteWait.selection().action().kind()).isEqualTo(WAIT);
        assertThat(obsoleteWait.selection().action().until()).isEqualTo(at(20));
        assertThat(obsoleteWait.origin().wakeUps().getFirst()).isEqualTo(at(4));
        assertThat(obsoleteWait.availableAt()).isEqualTo(at(1).plusMillis(100)).isBefore(at(4));
        assertThat(obsoleteWait.disposition()).isEqualTo("WAIT_EVENT_CHANGED_REPLAN");
        assertThat(obsoleteWait.hostNanos()).isEqualTo(PENDING_COMPUTE_NANOS);

        var retry = result.latencyDecisions().get(1);
        assertThat(retry.origin().at()).isEqualTo(obsoleteWait.availableAt());
        assertThat(retry.origin().ongoingOperations()).isEmpty();
        assertThat(retry.origin().wakeUps()).doesNotContain(at(4)).contains(at(20));
        assertThat(retry.selection().action().kind()).isEqualTo(INSTALL);
        assertThat(retry.hostNanos()).isEqualTo(READY_COMPUTE_NANOS);
        assertThat(retry.availableAt()).isEqualTo(at(1).plusMillis(200));
        assertThat(execution.decisions().getFirst().executedAction().kind()).isEqualTo(INSTALL);
        assertThat(execution.decisions().getFirst().at()).isEqualTo(retry.availableAt());
        assertThat(execution.decisions()).extracting(d -> d.executedAction().kind())
                .containsExactly(INSTALL, WAIT, SWITCH, OPEN, CONTINUE, CONTINUE, WAIT);
        assertThat(execution.computation().selectionNanos()).isEqualTo(4 * PENDING_COMPUTE_NANOS + 6 * READY_COMPUTE_NANOS)
                .isEqualTo(result.latencyDecisions().stream().mapToLong(PlanningLatencyController.Decision::hostNanos).sum())
                .isEqualTo(clock.get());

        var snapshot = execution.snapshot();
        assertThat(execution.simulation().metrics().servedContacts()).isEqualTo(1);
        assertThat(snapshot.payloadProgress().get("C1")).isEqualTo(new ExecutionSnapshot.PayloadProgress(100, 100, true));
        assertThat(snapshot.uncertainContexts()).isEmpty();
        assertThat(snapshot.issuedKeyIds()).isEqualTo(initial.issuedKeyIds());
        assertThat(snapshot.issuedOperationIds()).containsExactlyInAnyOrder("interrupted-install",
                "branch-operation:1", "branch-operation:2", "branch-operation:3");
        assertThat(snapshot.securityState().key(NEW_A.uplink()).nextNonceCounter()).isEqualTo(1);
        assertThat(snapshot.securityState().key(NEW_A.downlink()).nextNonceCounter()).isEqualTo(1);
        assertThat(snapshot.createdKeys()).isZero();
        var costs = initial.scenario().costs();
        assertThat(snapshot.accruedCosts()).isEqualTo(costs.cost(SecurityAction.INSTALL).consumedUntil(Duration.ofMillis(500))
                .plus(costs.cost(SecurityAction.INSTALL).total()).plus(costs.cost(SecurityAction.SWITCH).total())
                .plus(costs.cost(SecurityAction.OPEN).total()));
        assertThat(snapshot.journal()).filteredOn(e -> e.type() == SimulationEvent.Type.ACTION_FAILED).singleElement()
                .satisfies(e -> {
                    assertThat(e.operationId()).isEqualTo("interrupted-install");
                    assertThat(e.at()).isEqualTo(at(0).plusMillis(500));
                    assertThat(e.failureCode()).isEqualTo("INTERRUPTED");
                });
        assertThat(snapshot.journal()).filteredOn(e -> e.type() == SimulationEvent.Type.STEP_COMPLETED)
                .extracting(SimulationEvent::action).containsExactly(SecurityAction.INSTALL, SecurityAction.SWITCH, SecurityAction.OPEN, SecurityAction.STOP);
        assertThat(snapshot.journal()).filteredOn(e -> e.type() == SimulationEvent.Type.PAYLOAD_TRANSFERRED).hasSize(2);
        assertThat(snapshot.journal()).noneMatch(e -> e.type() == SimulationEvent.Type.PAYLOAD_REJECTED
                || e.type() == SimulationEvent.Type.PAYLOAD_ABORTED);
        assertThat(initial.pendingOperations()).hasSize(1);
        assertThat(initial.securityState().key(NEW_A.uplink()).installedOnBothPeers()).isFalse();
        assertThat(initial.securityState().key(NEW_A.uplink()).nextNonceCounter()).isZero();
    }

    private PlanningLatencyController controller(HorizonPolicy configuration, AtomicLong clock, double scale) {
        SecurityPolicy policy = new SecurityPolicy() {
            @Override public PolicyConfiguration configuration() { return configuration; }

            @Override public PolicySelection select(PlanningObservation observation, List<PlanningAction> candidates) {
                boolean pending = !observation.ongoingOperations().isEmpty();
                clock.addAndGet(pending ? PENDING_COMPUTE_NANOS : READY_COMPUTE_NANOS);
                if (!pending) {
                    for (var kind : List.of(CONTINUE, OPEN, SWITCH, INSTALL)) {
                        var service = candidates.stream().filter(a -> a.kind() == kind).findFirst();
                        if (service.isPresent()) return PolicySelection.rule(service.get(), "Measured test service");
                    }
                }
                // Deliberately request LOS, not the earlier known completion. The common
                // execution contract clips this WAIT to the first observed wakeup.
                return PolicySelection.rule(candidates.stream().filter(a -> a.kind() == WAIT)
                        .max(Comparator.comparing(PlanningAction::until)).orElseThrow(), "Measured test long WAIT");
            }
        };
        return new PlanningLatencyController(policy,
                new PlanningLatencyController.Model(Duration.ZERO, scale, Duration.ofSeconds(2)),
                Duration.ofSeconds(5), (snapshot, count) -> { }, clock::get);
    }

    private ExecutionSnapshot committedSwitch() {
        var transitions = new LifecycleTransitions(CONSTRAINTS);
        var state = transitions.createContext(SecurityState.empty(SATELLITE, at(-3)), A, "wait-up-SA", "wait-down-SA", at(-3));
        state = transitions.prepareKeys(state, A, NEW_A, at(-2));
        state = transitions.confirmInstallation(state, A, NEW_A, window("provision", A, -2, 0), at(-1));
        var contact = new SimulationContact(window("C1", A, 0, 10),
                new SimulationContact.PayloadProfile(400, 100, 100, 100, 100, 100));
        var scenario = new SimulationScenario("measured-wait-replan", at(0), at(10), state, CONSTRAINTS,
                SecurityCostProfile.syntheticWitness(), List.of(contact), List.of());
        var engine = new SimulationEngine();
        var initial = engine.controlledSnapshot(engine.start(scenario));
        return engine.controlledStart(initial, operation("committed-switch", SecurityAction.SWITCH, 0, A, NEW_A, "C1"));
    }

    private ExecutionSnapshot interruptedInstallation() {
        var transitions = new LifecycleTransitions(CONSTRAINTS);
        var state = transitions.createContext(SecurityState.empty(SATELLITE, at(-3)), A, "retry-up-SA", "retry-down-SA", at(-3));
        state = transitions.prepareKeys(state, A, NEW_A, at(-2));
        var contact = new SimulationContact(window("C1", A, 0, 20),
                new SimulationContact.PayloadProfile(400, 100, 100, 100, 100, 100));
        var scenario = new SimulationScenario("measured-interrupted-install-wake", at(0), at(20), state, CONSTRAINTS,
                SecurityCostProfile.syntheticWitness(), List.of(contact), List.of());
        var engine = new SimulationEngine();
        var initial = engine.controlledSnapshot(engine.start(scenario));
        var interrupted = new PlannedOperation("interrupted-install",
                new ScheduledSecurityAction(SecurityAction.INSTALL, at(0), A, NEW_A, "Test interrupted committed installation"),
                "C1", at(0).plusMillis(500), Set.of());
        return engine.controlledStart(initial, interrupted);
    }

    private void assertSuccessfulExecution(ExecutionSnapshot initial, ControlledSimulationResult execution) {
        var finalSnapshot = execution.snapshot();
        assertThat(execution.simulation().metrics().servedContacts()).isEqualTo(1);
        assertThat(finalSnapshot.payloadProgress().get("C1")).isEqualTo(new ExecutionSnapshot.PayloadProgress(100, 100, true));
        assertThat(finalSnapshot.securityState().key(NEW_A.uplink()).nextNonceCounter()).isEqualTo(1);
        assertThat(finalSnapshot.securityState().key(NEW_A.downlink()).nextNonceCounter()).isEqualTo(1);
        assertThat(finalSnapshot.issuedKeyIds()).isEqualTo(initial.issuedKeyIds());
        assertThat(finalSnapshot.issuedOperationIds()).containsExactlyInAnyOrder("committed-switch", "branch-operation:1");
        assertThat(finalSnapshot.createdKeys()).isZero();
        assertThat(finalSnapshot.accruedCosts()).isEqualTo(initial.scenario().costs().cost(SecurityAction.SWITCH).total()
                .plus(initial.scenario().costs().cost(SecurityAction.OPEN).total()));
        assertThat(finalSnapshot.journal()).filteredOn(e -> e.type() == SimulationEvent.Type.STEP_COMPLETED)
                .extracting(SimulationEvent::action).containsExactly(SecurityAction.SWITCH, SecurityAction.OPEN, SecurityAction.STOP);
        assertThat(finalSnapshot.journal()).filteredOn(e -> e.type() == SimulationEvent.Type.PAYLOAD_TRANSFERRED).hasSize(2);
        assertThat(finalSnapshot.journal()).noneMatch(e -> e.type() == SimulationEvent.Type.ACTION_FAILED
                || e.type() == SimulationEvent.Type.PAYLOAD_REJECTED || e.type() == SimulationEvent.Type.PAYLOAD_ABORTED);
        assertThat(initial.at()).isEqualTo(at(0));
        assertThat(initial.pendingOperations()).hasSize(1);
        assertThat(initial.securityState().context(A).status()).isEqualTo(AssociationStatus.UNKEYED);
        assertThat(initial.securityState().key(NEW_A.uplink()).nextNonceCounter()).isZero();
        assertThat(initial.securityState().key(NEW_A.downlink()).nextNonceCounter()).isZero();
    }
}
