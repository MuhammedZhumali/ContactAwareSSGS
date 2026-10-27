package org.contactaware.security.simulation;

import org.contactaware.security.SecurityAction;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import java.util.function.Consumer;
import static org.assertj.core.api.Assertions.*;
import static org.contactaware.security.simulation.HorizonWitness.*;

/** Deterministic cooperative deadline injection; no sleeps or wall-clock performance claims. */
class DecisionDeadlineControllerTest {
    @Test void observationGenerationAndSelectionDeadlinesOnlyWaitWithoutStartingService() {
        for (var phase : List.of("controller.observation", "generation.loop", "controller.selection")) {
            var clock = new AtomicLong();
            var selected = new AtomicInteger();
            var controller = controller(waitPolicy(List.of(), () -> selected.incrementAndGet()), clock,
                    p -> { if (p.equals(phase)) clock.addAndGet(10); });
            var result = controller.run(scenario());
            assertThat(result.status()).isEqualTo(ControlledSimulationResult.Status.FINISHED);
            assertThat(selected).hasValue(0);
            assertThat(result.decisions()).hasSize(1).allSatisfy(d -> {
                assertThat(d.executedAction().kind()).isEqualTo(PlanningAction.Kind.WAIT);
                assertThat(d.search()).isNull();
                assertThat(d.source()).isEqualTo(ControlledSimulationResult.DecisionSource.RULE);
                assertThat(d.reason()).contains("DECISION_TIME_LIMIT");
            });
            assertThat(result.simulation().events()).noneMatch(e -> e.type() == SimulationEvent.Type.STEP_STARTED
                    || e.type() == SimulationEvent.Type.PAYLOAD_TRANSFERRED);
            assertThat(controller.decisionTimings()).singleElement().satisfies(t -> {
                assertThat(t.deadlineMiss()).isTrue();
                assertThat(t.elapsedNanos()).isEqualTo(10);
                assertThat(t.budgetNanos()).isEqualTo(10);
                assertThat(t.stopCheckpoint()).isEqualTo(phase);
                assertThat(t.disposition()).isEqualTo("SAFE_WAIT");
            });
        }
    }

    @Test void nonCooperativePolicyReturnIsCheckedBeforeItsSelectedServiceExecutes() {
        var clock = new AtomicLong();
        var controller = controller(servicePolicy(() -> clock.addAndGet(10)), clock, ignored -> {});
        var result = controller.run(scenario());
        assertThat(result.decisions()).singleElement().satisfies(d -> {
            assertThat(d.executedAction().kind()).isEqualTo(PlanningAction.Kind.WAIT);
            assertThat(d.search()).isNull();
        });
        assertThat(result.snapshot().issuedOperationIds()).isEmpty();
        assertThat(controller.decisionTimings().getFirst().stopCheckpoint()).isEqualTo("controller.selection.complete");
    }

    @Test void exactSearchSwallowingDeadlineCannotReturnCompleteOrStopTheExperimentAsLocalIncomplete() {
        var clock = new AtomicLong();
        var policy = new BudgetedHorizonPolicy(HorizonPolicy.next(), new ExactHorizonPlanner(), PlanTieBreak.CANONICAL);
        var controller = controller(policy, clock,
                phase -> { if (phase.equals("initialization.begin")) clock.addAndGet(10); });
        var result = controller.run(scenario());
        assertThat(result.status()).isEqualTo(ControlledSimulationResult.Status.FINISHED);
        assertThat(policy.attempts()).singleElement().satisfies(attempt -> {
            assertThat(attempt.search().status()).isEqualTo(HorizonPlanResult.Status.INCOMPLETE);
            assertThat(attempt.search().diagnostics().stopReason()).isEqualTo(SearchDiagnostics.StopReason.DECISION_TIME_LIMIT);
            assertThat(attempt.disposition()).isEqualTo("STOP");
        });
        assertThat(result.decisions()).allSatisfy(d -> {
            assertThat(d.search()).isNull();
            assertThat(d.executedAction().kind()).isEqualTo(PlanningAction.Kind.WAIT);
        });
        assertThat(controller.decisionTimings().getFirst().deadlineMiss()).isTrue();
    }

    @Test void everyDecisionGetsFreshBudgetWhileGlobalBudgetRemainsCumulative() {
        var timers = List.of(at(1), at(2), at(3));
        var clock = new AtomicLong();
        var controller = controller(waitPolicy(timers, () -> clock.addAndGet(6)), clock, ignored -> {});
        var result = controller.run(scenario());
        assertThat(result.status()).isEqualTo(ControlledSimulationResult.Status.FINISHED);
        assertThat(controller.decisionTimings()).hasSize(4).allSatisfy(t -> {
            assertThat(t.elapsedNanos()).isEqualTo(6);
            assertThat(t.deadlineMiss()).isFalse();
        });
        assertThat(clock).hasValue(24);

        clock.set(0);
        var saved = new AtomicReference<ExecutionSnapshot>();
        var globallyLimited = new SecurityPolicyController(waitPolicy(timers, () -> clock.addAndGet(6)),
                Duration.ofNanos(15), (s, n) -> saved.set(s), false, Duration.ofNanos(10), clock::get, ignored -> {});
        var stopped = globallyLimited.run(scenario());
        assertThat(stopped.status()).isEqualTo(ControlledSimulationResult.Status.CONTROLLER_TIME_LIMIT);
        assertThat(stopped.simulation()).isNull();
        assertThat(stopped.snapshot()).isEqualTo(saved.get());
        assertThat(stopped.snapshot().at()).isEqualTo(at(2));
        assertThat(stopped.decisions()).hasSize(2);
        assertThat(globallyLimited.decisionTimings()).noneMatch(SecurityPolicyController.DecisionTiming::deadlineMiss);
    }

    @Test void zeroAndNonExpiredDecisionBudgetsPreserveNominalExecutionAndExcludeExecutorTime() {
        var baseline = new SecurityPolicyController(servicePolicy(() -> {})).run(scenario());
        for (var budget : List.of(Duration.ZERO, Duration.ofNanos(10))) {
            var clock = new AtomicLong();
            var controller = new SecurityPolicyController(servicePolicy(() -> clock.addAndGet(6)), Duration.ZERO,
                    (s, n) -> {}, false, budget, clock::get, ignored -> {});
            var initial = new SimulationEngine().start(scenario());
            var result = controller.run(initial, operation -> {
                clock.addAndGet(100); // Actual operation/outcome work is outside the optimization deadline.
                return OperationOutcomes.nominal().forOperation(operation);
            });
            assertThat(result.simulation()).isEqualTo(baseline.simulation());
            assertThat(result.snapshot()).isEqualTo(baseline.snapshot());
            assertThat(result.decisions()).isEqualTo(baseline.decisions());
            assertThat(controller.decisionTimings()).allSatisfy(t -> {
                assertThat(t.elapsedNanos()).isEqualTo(6);
                assertThat(t.deadlineMiss()).isFalse();
            });
        }
    }

    @Test void expiredDecisionPreservesPendingOperationAndFrameAcrossEarliestWakeup() {
        var engine = new SimulationEngine();
        var opened = execute(engine.controlledSnapshot(engine.start(scenario())), PlanningAction.Kind.OPEN, List.of());
        var pending = engine.controlledAdvance(opened, at(0).plusMillis(500), false);
        assertThat(pending.pendingOperations()).hasSize(1);
        var ready = engine.controlledAdvance(opened, at(1), false);
        var inFrame = execute(ready, PlanningAction.Kind.CONTINUE, List.of(at(1).plusMillis(125)));
        assertThat(inFrame.inFlightFrame()).isNotNull();
        for (var initial : List.of(pending, inFrame)) {
            var clock = new AtomicLong();
            var timer = initial.at().plusMillis(50);
            var snapshots = new ArrayList<ExecutionSnapshot>();
            var controller = new SecurityPolicyController(waitPolicy(List.of(timer), () -> {}), Duration.ZERO,
                    (s, n) -> snapshots.add(s), false, Duration.ofNanos(10), clock::get,
                    p -> { if (p.equals("controller.selection")) clock.addAndGet(10); });
            var result = controller.run(initial);
            var baseline = new SecurityPolicyController(waitPolicy(List.of(timer), () -> {})).run(initial);
            assertThat(result.simulation()).isEqualTo(baseline.simulation());
            assertThat(snapshots.getFirst()).isEqualTo(initial);
            assertThat(snapshots.get(1).at()).isEqualTo(timer);
            assertThat(snapshots.get(1).pendingOperations()).isEqualTo(initial.pendingOperations());
            assertThat(snapshots.get(1).inFlightFrame()).isEqualTo(initial.inFlightFrame());
            assertThat(result.decisions()).allMatch(d -> d.executedAction().kind() == PlanningAction.Kind.WAIT);
            assertThat(result.snapshot().issuedOperationIds()).isEqualTo(initial.issuedOperationIds());
        }
    }

    @Test void cancellationDuringDeadlineRecoveryStopsAtTheSavedSnapshotWithoutFallbackExecution() {
        var clock = new AtomicLong();
        var outerRef = new AtomicReference<SearchControl.Scope>();
        var saved = new AtomicReference<ExecutionSnapshot>();
        var controller = new SecurityPolicyController(waitPolicy(List.of(), () -> {}), Duration.ZERO,
                (s, n) -> saved.set(s), false, Duration.ofNanos(10), clock::get,
                p -> { if (p.equals("generation.loop")) clock.addAndGet(10); });
        try (var outer = SearchControl.open(Duration.ZERO, SearchDiagnostics.StopReason.CONTROLLER_TIME_LIMIT,
                clock::get, p -> { if (p.equals("controller.deadline.recovery")) outerRef.get().cancel(); })) {
            outerRef.set(outer);
            var result = controller.run(scenario());
            assertThat(result.status()).isEqualTo(ControlledSimulationResult.Status.CANCELLED);
            assertThat(result.snapshot()).isEqualTo(saved.get());
            assertThat(result.snapshot().at()).isEqualTo(at(0));
            assertThat(result.decisions()).isEmpty();
            assertThat(result.simulation()).isNull();
        }
        assertThat(controller.decisionTimings().getFirst().disposition()).isEqualTo("STOP_CANCELLED");
    }

    @Test void interruptHasPrecedenceOverExpiredDecisionAndIsNotCleared() {
        var clock = new AtomicLong();
        var controller = controller(waitPolicy(List.of(), () -> {
            clock.set(10);
            Thread.currentThread().interrupt();
        }), clock, ignored -> {});
        try {
            var result = controller.run(scenario());
            assertThat(result.status()).isEqualTo(ControlledSimulationResult.Status.INTERRUPTED);
            assertThat(result.decisions()).isEmpty();
            assertThat(result.snapshot().at()).isEqualTo(at(0));
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally { Thread.interrupted(); }
    }

    @Test void mandatoryIncidentResponseStillExecutesWhenOptimizationExpiresBeforeObservation() {
        var source = scenario();
        var incident = new SimulationScenario(source.name(), source.start(), source.end(), source.initialState(),
                source.constraints(), source.costs(), source.contacts(), source.plan(),
                List.of(new TrafficKeyDisclosure("deadline-incident", A, at(0), at(0))));
        var clock = new AtomicLong();
        var controller = controller(waitPolicy(List.of(), () -> {}), clock,
                p -> { if (p.equals("controller.observation")) clock.addAndGet(10); });
        var result = controller.run(incident);
        var baseline = new SecurityPolicyController(waitPolicy(List.of(), () -> {})).run(incident);
        assertThat(result.status()).isEqualTo(ControlledSimulationResult.Status.FINISHED);
        assertThat(result.simulation()).isEqualTo(baseline.simulation());
        assertThat(result.snapshot().journal()).anyMatch(e -> e.type() == SimulationEvent.Type.STEP_COMPLETED
                && e.action() == SecurityAction.NOTIFY_REVOCATION);
        assertThat(controller.decisionTimings()).anyMatch(t -> t.disposition().equals("MANDATORY_RESPONSE"));
    }

    @Test void deadlineDuringProofPreparationDiscardsTheCertificateAndNeverExecutesItsAction() {
        var planner = new ExactHorizonPlanner();
        var policy = new SecurityPolicy() {
            public PolicyConfiguration configuration() { return HorizonPolicy.next(); }
            public ContinuationReuseGuard.Rules continuationRules() { return planner.continuationRules(); }
            public PolicySelection select(PlanningObservation o, List<PlanningAction> candidates) {
                return planner.selectUnrestricted(o, "test exact");
            }
        };
        var clock = new AtomicLong();
        var controller = new SecurityPolicyController(policy, Duration.ZERO, (s, n) -> {}, true,
                Duration.ofNanos(10), clock::get,
                p -> { if (p.equals("controller.reusePreparation")) clock.addAndGet(10); });
        var result = controller.run(scenario());
        assertThat(result.status()).isEqualTo(ControlledSimulationResult.Status.FINISHED);
        assertThat(result.decisions()).singleElement().satisfies(d -> {
            assertThat(d.executedAction().kind()).isEqualTo(PlanningAction.Kind.WAIT);
            assertThat(d.search()).isNull();
            assertThat(d.continuation()).isNull();
        });
        assertThat(controller.decisionTimings().getFirst().stopCheckpoint()).isEqualTo("controller.reusePreparation");
    }

    private SecurityPolicyController controller(SecurityPolicy policy, AtomicLong clock, Consumer<String> hook) {
        return new SecurityPolicyController(policy, Duration.ZERO, (s, n) -> {}, false,
                Duration.ofNanos(10), clock::get, hook);
    }

    private SecurityPolicy waitPolicy(List<Instant> timers, Runnable selection) {
        return new SecurityPolicy() {
            public PolicyConfiguration configuration() { return HorizonPolicy.next(); }
            public List<Instant> additionalWakeUps(PlanningObservation o) { return timers; }
            public PolicySelection select(PlanningObservation o, List<PlanningAction> candidates) {
                selection.run();
                return PolicySelection.rule(candidates.stream().filter(a -> a.kind() == PlanningAction.Kind.WAIT)
                        .min(Comparator.comparing(PlanningAction::until)).orElse(null), "Test wait");
            }
        };
    }

    private SecurityPolicy servicePolicy(Runnable selection) {
        return new SecurityPolicy() {
            public PolicyConfiguration configuration() { return HorizonPolicy.next(); }
            public PolicySelection select(PlanningObservation o, List<PlanningAction> candidates) {
                selection.run();
                var action = candidates.stream().filter(a -> a.kind() == PlanningAction.Kind.OPEN
                        || a.kind() == PlanningAction.Kind.CONTINUE).findFirst()
                        .orElseGet(() -> candidates.stream().filter(a -> a.kind() == PlanningAction.Kind.WAIT)
                                .min(Comparator.comparing(PlanningAction::until)).orElse(null));
                return PolicySelection.rule(action, "Test service");
            }
        };
    }

    private ExecutionSnapshot execute(ExecutionSnapshot initial, PlanningAction.Kind kind, List<Instant> timers) {
        var observation = PlanningObservation.from(initial, 1).withAdditionalWakeUps(timers);
        var action = new SecurityActionGenerator().candidates(observation).stream().filter(a -> a.kind() == kind)
                .min(Comparator.comparing(PlanningAction::until)).orElseThrow();
        return SecurityPolicyController.executeAction(initial, observation, action, OperationOutcomes.nominal());
    }

    private SimulationScenario scenario() {
        return new SimulationScenario("decision-deadline", at(0), at(4), initialState(), CONSTRAINTS,
                SecurityCostProfile.syntheticWitness(), List.of(new SimulationContact(window("C1", A, 0, 4),
                SimulationContact.PayloadProfile.syntheticWitness())), List.of());
    }
}
