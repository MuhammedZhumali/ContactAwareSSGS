package org.contactaware.security.simulation;

import org.contactaware.security.*;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;
import static org.contactaware.security.simulation.HorizonWitness.*;

class RecedingHorizonControllerTest {
    private final SimulationEngine simulation = new SimulationEngine();

    @Test void reducedXYExecutesDifferentHorizonsEndToEndAndNextLearnsC3OnlyAfterC1() {
        var nextX = controller(HorizonPolicy.next()).run(causal(A));
        var nextY = controller(HorizonPolicy.next()).run(causal(B));
        var multiX = controller(HorizonPolicy.multi(2)).run(causal(A));
        var multiY = controller(HorizonPolicy.multi(2)).run(causal(B));
        assertFinishedMetrics(nextX, 2, 2, 2, 0);
        assertFinishedMetrics(nextY, 3, 3, 3, 0);
        assertFinishedMetrics(multiX, 3, 3, 5, 0);
        assertFinishedMetrics(multiY, 3, 3, 3, 0);

        var xC1 = nextX.decisions().stream().filter(d -> d.at().isBefore(at(4))).toList();
        var yC1 = nextY.decisions().stream().filter(d -> d.at().isBefore(at(4))).toList();
        assertThat(xC1).extracting(ControlledSimulationResult.Decision::observation)
                .containsExactlyElementsOf(yC1.stream().map(ControlledSimulationResult.Decision::observation).toList());
        assertThat(xC1).extracting(ControlledSimulationResult.Decision::executedAction)
                .containsExactlyElementsOf(yC1.stream().map(ControlledSimulationResult.Decision::executedAction).toList());
        assertThat(xC1).allMatch(d -> d.observation().visibleContacts().stream().noneMatch(c -> c.window().id().equals("C3")));
        assertThat(nextX.decisions()).anyMatch(d -> !d.at().isBefore(at(4))
                && d.observation().visibleContacts().stream().anyMatch(c -> c.window().id().equals("C3")));
        assertThat(multiX.decisions()).anyMatch(d -> d.at().isBefore(at(4)) && is(d.executedAction(), PlanningAction.Kind.INSTALL));
        assertThat(multiY.decisions()).noneMatch(d -> is(d.executedAction(), PlanningAction.Kind.INSTALL));
        assertThat(nextX.decisions()).allMatch(d -> d.search().status() == HorizonPlanResult.Status.COMPLETE);
    }

    @Test void executesOnlyTheFirstActionAndDiscardsThePreviouslyPredictedSuffix() {
        var solver = exact();
        var calls = new AtomicInteger();
        HorizonPlanner poisonedSuffix = observation -> {
            calls.incrementAndGet();
            var result = solver.plan(observation);
            if (result.firstAction() == null) return result;
            var poison = new PlanningAction(PlanningAction.Kind.PREPARE, at(1000), at(1001), A,
                    new KeyPairIds("must-not-be-issued-up", "must-not-be-issued-down"), null);
            return resultWith(result, List.of(result.firstAction(), poison), result.status());
        };
        var result = new RecedingHorizonController(HorizonPolicy.next(), poisonedSuffix).run(keyed(4, List.of()));
        assertFinishedMetrics(result, 1, 3, 1, 0);
        assertThat(calls.get()).isEqualTo(result.decisions().size()).isGreaterThan(2);
        assertThat(result.snapshot().issuedKeyIds()).doesNotContain("must-not-be-issued-up", "must-not-be-issued-down");
        assertThat(result.decisions()).allMatch(d -> d.executedAction().equals(d.search().firstAction()));
    }

    @Test void replansAfterEveryCompleteFrameAndCanWaitAtTheServiceThresholdBeforeAnotherFrameStarts() {
        var solver = exact();
        var observed = new ArrayList<PlanningObservation>();
        HorizonPlanner stopAfterThreshold = observation -> {
            observed.add(observation);
            var progress = observation.payloadProgress().get("C1");
            if (progress != null && progress.uplinkBytes() >= 100 && progress.downlinkBytes() >= 100) {
                var engine = new BranchEngine();
                var branch = engine.create(observation);
                while (branch.snapshot().at().isBefore(observation.evaluationEnd()))
                    branch = engine.execute(branch, candidate(branch.observation(), PlanningAction.Kind.WAIT));
                var evaluated = new ContinuationEvaluator().evaluate(branch);
                return new HorizonPlanResult(branch.plan(), evaluated.evaluation(), branch.plan().getFirst(),
                        HorizonPlanResult.Status.COMPLETE, 1, 0, new SearchDiagnostics.Counter().snapshot());
            }
            return solver.plan(observation);
        };
        var result = new RecedingHorizonController(HorizonPolicy.next(), stopAfterThreshold).run(keyed(4, List.of()));
        assertFinishedMetrics(result, 1, 2, 1, 0);
        assertThat(observed).anyMatch(o -> o.at().equals(at(2)) && o.payloadProgress().get("C1").uplinkBytes() == 100
                && o.payloadProgress().get("C1").downlinkBytes() == 0);
        assertThat(observed).anyMatch(o -> o.at().equals(at(3)) && o.payloadProgress().get("C1").downlinkBytes() == 100);
        assertThat(result.decisions()).anyMatch(d -> d.at().equals(at(3)) && is(d.executedAction(), PlanningAction.Kind.WAIT));
        assertThat(result.simulation().events()).filteredOn(e -> e.type() == SimulationEvent.Type.PAYLOAD_TRANSFERRED).hasSize(2);
    }

    @Test void incompleteSearchKeepsAResumablePartialWarmupAndDoesNotExecuteItsIncumbent() {
        var source = warmupScenario();
        var checkpoint = simulation.advance(simulation.start(source), at(-1).plusMillis(500));
        var solver = exact();
        HorizonPlanner unfinished = observation -> {
            var complete = solver.plan(observation);
            return resultWith(complete, complete.bestPlan(), HorizonPlanResult.Status.INCOMPLETE);
        };
        var stopped = new RecedingHorizonController(HorizonPolicy.next(), unfinished).run(checkpoint);
        assertThat(stopped.status()).isEqualTo(ControlledSimulationResult.Status.SEARCH_INCOMPLETE);
        assertThat(stopped.simulation()).isNull();
        assertThat(stopped.snapshot().at()).isEqualTo(checkpoint.at());
        assertThat(stopped.snapshot().pendingOperations()).isEqualTo(checkpoint.pendingOperations());
        assertThat(stopped.snapshot().accruedCosts()).isEqualTo(checkpoint.accruedCosts());
        assertThat(stopped.decisions()).hasSize(1);
        assertThat(stopped.decisions().getFirst().search().bestPlan()).isNotEmpty();
        assertThat(stopped.decisions().getFirst().executedAction()).isNull();
        assertThat(stopped.snapshot().journal()).noneMatch(e -> e.type() == SimulationEvent.Type.SIMULATION_ENDED
                || e.type() == SimulationEvent.Type.STEP_INTERRUPTED);
        var resumed = controller(HorizonPolicy.next()).run(stopped.snapshot());
        assertFinishedMetrics(resumed, 1, 2, 4, 2);
    }

    @Test void completedWarmupIsIncludedOnceInActualFinalMetrics() {
        var checkpoint = simulation.advance(simulation.start(warmupScenario()), at(0));
        assertThat(checkpoint.pendingOperations()).isEmpty();
        var result = controller(HorizonPolicy.next()).run(checkpoint);
        assertFinishedMetrics(result, 1, 2, 4, 2);
        assertThat(result.simulation().metrics().completedOperations()).containsEntry(SecurityAction.PREPARE, 1L);
        assertThat(result.decisions().getFirst().observation().createdKeys()).isEqualTo(2);
    }

    @Test void handedOffMissingAcknowledgementRemainsRealAndBlocksFurtherOnlineUse() {
        var open = new PlannedOperation("known-open", new ScheduledSecurityAction(SecurityAction.OPEN, at(0), A, null, "Known pending open"),
                "C1", null, Set.of(SecurityAction.OPEN));
        var result = controller(HorizonPolicy.next()).run(simulation.start(keyed(4, List.of(open))));
        assertFinishedMetrics(result, 0, 0, 1, 0);
        assertThat(result.simulation().metrics().operationFailures()).containsEntry("MISSING_ACKNOWLEDGEMENT", 1L);
        assertThat(result.simulation().uncertainContexts()).contains(A);
        assertThat(result.decisions()).anyMatch(d -> d.at().equals(at(1)) && d.observation().uncertainContexts().contains(A));
        assertThat(result.decisions()).noneMatch(d -> d.at().isAfter(at(0)) && d.executedAction() != null
                && (is(d.executedAction(), PlanningAction.Kind.OPEN) || is(d.executedAction(), PlanningAction.Kind.REUSE)));
    }

    @Test void handedOffInterruptedInstallationTriggersARealEarlyDecisionAndCanBeRetried() {
        var prepared = preparedState();
        var install = new PlannedOperation("known-install", new ScheduledSecurityAction(SecurityAction.INSTALL, at(0), A, NEW_A, "Known pending install"),
                "C1", at(0).plusMillis(500), Set.of());
        var source = new SimulationScenario("controlled-interrupted-install", at(0), at(6), prepared, CONSTRAINTS,
                profile(100), List.of(contact("C1", A, 0, 6, 100, 100)), List.of(install));
        var result = controller(HorizonPolicy.next()).run(simulation.start(source));
        assertThat(result.status()).isEqualTo(ControlledSimulationResult.Status.FINISHED);
        assertThat(result.simulation().metrics().servedContacts()).isEqualTo(1);
        assertThat(result.simulation().metrics().usefulSecureTime()).isEqualTo(Duration.ofSeconds(2));
        assertThat(result.simulation().metrics().controlCosts().totalCpu()).isEqualTo(Duration.ofMillis(3500));
        assertThat(result.simulation().metrics().operationFailures()).containsEntry("INTERRUPTED", 1L);
        assertThat(result.decisions()).anyMatch(d -> d.at().equals(at(0).plusMillis(500))
                && d.observation().ongoingOperations().isEmpty() && is(d.executedAction(), PlanningAction.Kind.INSTALL));
        assertThat(result.decisions()).noneMatch(d -> d.at().equals(at(1)));
    }

    @Test void futureFailureControlsDoNotChangeDecisionsBeforeTheirActualObservation() {
        var source = keyed(4, List.of());
        var nominal = controller(HorizonPolicy.next()).run(simulation.start(source));
        OperationOutcomes missingOpenAck = operation -> new OperationOutcomes.Outcome(null,
                operation.intent().action() == SecurityAction.OPEN ? Set.of(SecurityAction.OPEN) : Set.of());
        var failed = controller(HorizonPolicy.next()).run(simulation.start(source), missingOpenAck);
        assertThat(failed.decisions().getFirst().observation()).isEqualTo(nominal.decisions().getFirst().observation());
        assertThat(failed.decisions().getFirst().executedAction()).isEqualTo(nominal.decisions().getFirst().executedAction());
        assertThat(failed.decisions().getFirst().search().bestPlan()).isEqualTo(nominal.decisions().getFirst().search().bestPlan());
        assertThat(failed.decisions().get(1).observation()).isEqualTo(nominal.decisions().get(1).observation());
        assertFinishedMetrics(failed, 0, 0, 1, 0);
        assertThat(failed.decisions()).anyMatch(d -> d.at().equals(at(1)) && d.observation().uncertainContexts().contains(A));
    }

    @Test void interruptionScheduledAfterOperationCompletionNeverCreatesAnArtificialDecision() {
        var source = keyed(4, List.of());
        var nominal = controller(HorizonPolicy.next()).run(simulation.start(source));
        var calls = new AtomicInteger();
        var selected = new ArrayList<String>();
        OperationOutcomes obsoleteInterruption = operation -> {
            calls.incrementAndGet();
            selected.add(operation.id());
            assertThat(operation.intent().action()).isEqualTo(SecurityAction.OPEN);
            return new OperationOutcomes.Outcome(at(2).plusMillis(500), Set.of());
        };
        // OPEN completes at t=1. The execution-only interruption at t=2.5 belongs to
        // that finished operation and must not interrupt the payload frame [2,3].
        var withStaleEvent = controller(HorizonPolicy.next()).run(simulation.start(source), obsoleteInterruption);
        assertFinishedMetrics(withStaleEvent, 1, 3, 1, 0);
        assertThat(withStaleEvent.simulation()).isEqualTo(nominal.simulation());
        assertThat(withStaleEvent.decisions()).extracting(ControlledSimulationResult.Decision::observation)
                .containsExactlyElementsOf(nominal.decisions().stream().map(ControlledSimulationResult.Decision::observation).toList());
        assertThat(withStaleEvent.decisions()).extracting(ControlledSimulationResult.Decision::executedAction)
                .containsExactlyElementsOf(nominal.decisions().stream().map(ControlledSimulationResult.Decision::executedAction).toList());
        assertThat(withStaleEvent.decisions()).noneMatch(d -> d.at().equals(at(2).plusMillis(500)));
        assertThat(calls.get()).isEqualTo(1);
        assertThat(withStaleEvent.simulation().events()).filteredOn(e -> e.type() == SimulationEvent.Type.ACTION_STARTED)
                .extracting(SimulationEvent::operationId).containsExactlyElementsOf(selected);
    }

    @Test void handedOffRealFrameFinishesOnceAndNewFramesRemainControllerDecisions() {
        var open = operation("known-open", SecurityAction.OPEN, 0, A, null, "C1");
        var source = keyed(4, List.of(open));
        var checkpoint = simulation.advance(simulation.start(source), at(1).plusMillis(500));
        assertThat(checkpoint.inFlightFrame()).isNotNull();
        var originalCounter = checkpoint.securityState().key(OLD_A.uplink()).nextNonceCounter();
        var result = controller(HorizonPolicy.next()).run(checkpoint);
        assertFinishedMetrics(result, 1, 3, 1, 0);
        assertThat(result.simulation().metrics().contacts().getFirst().uplinkFrames()).isEqualTo(2);
        assertThat(result.simulation().metrics().contacts().getFirst().downlinkFrames()).isEqualTo(1);
        assertThat(result.snapshot().securityState().key(OLD_A.uplink()).nextNonceCounter()).isEqualTo(originalCounter + 2);
        assertThat(result.decisions().getFirst().observation().inFlightFrame()).isEqualTo(checkpoint.inFlightFrame());
        assertThat(result.decisions()).anyMatch(d -> d.at().equals(at(2)) && d.observation().inFlightFrame() == null);
    }

    @Test void preparationCompletionDuringAControlledFramePreservesThatCommittedFrame() {
        var newB = new KeyPairIds("B-background-up", "B-background-down");
        var open = operation("known-open", SecurityAction.OPEN, 0, A, null, "C1");
        var prepare = new PlannedOperation("background-prepare", new ScheduledSecurityAction(SecurityAction.PREPARE,
                at(0).plusMillis(500), B, newB, "Preparation at a different ground station"), null);
        var contact = contact("C1", A, 0, 4, 200, 200);
        var source = new SimulationScenario("controlled-frame-event", at(0), at(4), initialState(), CONSTRAINTS,
                profile(2), List.of(contact), List.of(open, prepare));
        var checkpoint = simulation.advance(simulation.start(source), at(1).plusMillis(500));
        assertThat(checkpoint.inFlightFrame()).isEqualTo(
                new ExecutionSnapshot.InFlightFrame(contact, A, OLD_A, at(1), at(2), true));
        assertThat(checkpoint.pendingOperations()).hasSize(1);
        var originalUplinkCounter = checkpoint.securityState().key(OLD_A.uplink()).nextNonceCounter();
        var originalDownlinkCounter = checkpoint.securityState().key(OLD_A.downlink()).nextNonceCounter();

        var result = controller(HorizonPolicy.next()).run(checkpoint);
        assertFinishedMetrics(result, 1, 3, 3, 2);
        var beforeCompletion = result.decisions().stream().filter(d -> d.at().equals(at(2))).findFirst().orElseThrow();
        assertThat(beforeCompletion.executedAction().kind()).isEqualTo(PlanningAction.Kind.CONTINUE);
        assertThat(beforeCompletion.resumedAt()).isEqualTo(at(2).plusMillis(500));
        var duringFrame = result.decisions().stream().filter(d -> d.at().equals(at(2).plusMillis(500))).findFirst().orElseThrow();
        // The completion event returns control halfway through the already committed
        // downlink frame. WAIT must finish that same [2,3] frame exactly once.
        assertThat(duringFrame.observation().inFlightFrame()).isEqualTo(
                new ExecutionSnapshot.InFlightFrame(contact, A, OLD_A, at(2), at(3), false));
        assertThat(duringFrame.observation().ongoingOperations()).isEmpty();
        assertThat(duringFrame.observation().securityState().key(newB.uplink()).status()).isEqualTo(KeyStatus.PRE_ACTIVE);
        assertThat(duringFrame.observation().securityState().key(newB.downlink()).status()).isEqualTo(KeyStatus.PRE_ACTIVE);
        assertThat(duringFrame.observation().createdKeys()).isEqualTo(2);
        assertThat(duringFrame.executedAction().kind()).isEqualTo(PlanningAction.Kind.WAIT);
        assertThat(duringFrame.resumedAt()).isEqualTo(at(3));
        assertThat(result.decisions()).anyMatch(d -> d.at().equals(at(3)) && d.observation().inFlightFrame() == null
                && is(d.executedAction(), PlanningAction.Kind.CONTINUE));
        assertThat(result.simulation().events()).filteredOn(e -> e.type() == SimulationEvent.Type.PAYLOAD_TRANSFERRED)
                .extracting(SimulationEvent::at).containsExactly(at(2), at(3), at(4));
        assertThat(result.simulation().metrics().completedOperations()).containsEntry(SecurityAction.PREPARE, 1L);
        assertThat(result.simulation().metrics().contacts().getFirst().uplinkFrames()).isEqualTo(2);
        assertThat(result.simulation().metrics().contacts().getFirst().downlinkFrames()).isEqualTo(1);
        assertThat(result.snapshot().securityState().key(OLD_A.uplink()).nextNonceCounter()).isEqualTo(originalUplinkCounter + 2);
        assertThat(result.snapshot().securityState().key(OLD_A.downlink()).nextNonceCounter()).isEqualTo(originalDownlinkCounter + 1);
    }

    @Test void emptyForecastIsRecordedAndActualExperimentStillFinishesAtItsEnd() {
        var source = new SimulationScenario("controlled-empty", at(0), at(5), initialState(), CONSTRAINTS,
                profile(100), List.of(), List.of());
        var result = controller(HorizonPolicy.next()).run(source);
        assertFinishedMetrics(result, 0, 0, 0, 0);
        assertThat(result.snapshot().at()).isEqualTo(at(5));
        assertThat(result.decisions()).hasSize(1);
        assertThat(result.decisions().getFirst().observation().visibleContacts()).isEmpty();
        assertThat(result.decisions().getFirst().executedAction()).isNull();
        assertThat(result.simulation().events()).filteredOn(e -> e.type() == SimulationEvent.Type.SIMULATION_ENDED).hasSize(1);
    }

    @Test void infeasibleSearchIsNotReportedAsFinishedAndDoesNotFinalizeTheSnapshot() {
        HorizonPlanner noPlan = ignored -> new HorizonPlanResult(List.of(), null, null,
                HorizonPlanResult.Status.INFEASIBLE, 1, 0, new SearchDiagnostics.Counter().snapshot());
        var result = new RecedingHorizonController(HorizonPolicy.next(), noPlan).run(keyed(4, List.of()));
        assertThat(result.status()).isEqualTo(ControlledSimulationResult.Status.NO_FEASIBLE_PLAN);
        assertThat(result.simulation()).isNull();
        assertThat(result.snapshot().at()).isEqualTo(at(0));
        assertThat(result.snapshot().journal()).noneMatch(e -> e.type() == SimulationEvent.Type.SIMULATION_ENDED);
        assertThat(result.decisions().getFirst().executedAction()).isNull();
    }

    @Test void rejectsFuturePrescribedActionsAndForecastSnapshotsAtTheExecutionBoundary() {
        var future = operation("future-open", SecurityAction.OPEN, 1, A, null, "C1");
        var source = keyed(4, List.of(future));
        var controller = controller(HorizonPolicy.next());
        assertThatThrownBy(() -> controller.run(source)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> controller.run(simulation.start(source))).isInstanceOf(IllegalArgumentException.class);
        var branch = new BranchEngine().create(PlanningObservation.from(simulation.start(keyed(4, List.of())), 1));
        assertThatThrownBy(() -> controller.run(branch.snapshot())).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void immediateExecutionInterruptionStopsInsteadOfSpinningAtTheSameTimestamp() {
        OperationOutcomes immediate = operation -> new OperationOutcomes.Outcome(operation.intent().scheduledAt(), Set.of());
        var result = controller(HorizonPolicy.next()).run(simulation.start(keyed(4, List.of())), immediate);
        assertThat(result.status()).isEqualTo(ControlledSimulationResult.Status.EXECUTION_STALLED);
        assertThat(result.snapshot().at()).isEqualTo(at(0));
        assertThat(result.decisions()).hasSize(1);
        assertThat(result.simulation()).isNull();
        assertThat(result.snapshot().journal()).anyMatch(e -> "INTERRUPTED".equals(e.failureCode()));
        assertThat(result.snapshot().journal()).noneMatch(e -> e.type() == SimulationEvent.Type.SIMULATION_ENDED);
    }

    @Test void nominalRepeatedRunsHaveIdenticalActualEventsMetricsAndActions() {
        var controller = controller(HorizonPolicy.multi(2));
        var first = controller.run(causal(A));
        var second = controller.run(causal(A));
        assertThat(second.simulation()).isEqualTo(first.simulation());
        assertThat(second.snapshot()).isEqualTo(first.snapshot());
        assertThat(second.decisions()).extracting(ControlledSimulationResult.Decision::executedAction)
                .containsExactlyElementsOf(first.decisions().stream().map(ControlledSimulationResult.Decision::executedAction).toList());
        assertThat(second.totalExploredNodes()).isEqualTo(first.totalExploredNodes());
    }

    private ExactHorizonPlanner exact() { return new ExactHorizonPlanner(new SearchLimits(200_000, Duration.ZERO)); }

    private RecedingHorizonController controller(HorizonPolicy policy) { return new RecedingHorizonController(policy, exact()); }

    private HorizonPlanResult resultWith(HorizonPlanResult original, List<PlanningAction> plan, HorizonPlanResult.Status status) {
        return new HorizonPlanResult(plan, original.score(), plan.isEmpty() ? null : plan.getFirst(), status,
                original.exploredNodes(), original.elapsedNanos(), original.diagnostics());
    }

    private PlanningAction candidate(PlanningObservation observation, PlanningAction.Kind kind) {
        return new SecurityActionGenerator().candidates(observation).stream().filter(a -> a.kind() == kind).findFirst().orElseThrow();
    }

    private boolean is(PlanningAction action, PlanningAction.Kind kind) { return action != null && action.kind() == kind; }

    private void assertFinishedMetrics(ControlledSimulationResult result, long served, long usefulSeconds, long cpuSeconds, long keys) {
        assertThat(result.status()).isEqualTo(ControlledSimulationResult.Status.FINISHED);
        assertThat(result.simulation()).isNotNull();
        var metrics = result.simulation().metrics();
        assertThat(metrics.servedContacts()).isEqualTo(served);
        assertThat(metrics.usefulSecureTime()).isEqualTo(Duration.ofSeconds(usefulSeconds));
        assertThat(metrics.controlCosts().totalCpu()).isEqualTo(Duration.ofSeconds(cpuSeconds));
        assertThat(metrics.controlCosts().managementBytes()).isZero();
        assertThat(metrics.createdKeys()).isEqualTo(keys);
        assertThat(new MetricsCollector().collect(result.snapshot().scenario().contacts(), result.snapshot().journal())).isEqualTo(metrics);
    }

    private SimulationScenario keyed(long end, List<PlannedOperation> plan) {
        var initial = initialState();
        var keys = new TreeMap<String, KeyState>();
        initial.keys().values().stream().filter(k -> k.contextId().equals(A)).forEach(k -> keys.put(k.id(), k));
        var state = new SecurityState(SATELLITE, initial.asOf(), Map.of(A, initial.context(A)), keys);
        return new SimulationScenario("controlled-keyed", at(0), at(end), state, CONSTRAINTS, profile(100),
                List.of(contact("C1", A, 0, end, 200, 200)), plan);
    }

    private SecurityState preparedState() {
        var transitions = new LifecycleTransitions(CONSTRAINTS);
        var state = transitions.createContext(SecurityState.empty(SATELLITE, at(-1)), A, "fresh-up", "fresh-down", at(-1));
        return transitions.prepareKeys(state, A, NEW_A, at(-1));
    }

    private SimulationScenario warmupScenario() {
        var state = new LifecycleTransitions(CONSTRAINTS).createContext(SecurityState.empty(SATELLITE, at(-1)),
                A, "fresh-up", "fresh-down", at(-1));
        return new SimulationScenario("controlled-common-warmup", at(-1), at(5), state, CONSTRAINTS, profile(1),
                List.of(contact("C1", A, 0, 5, 100, 100)), List.of(operation("warmup", SecurityAction.PREPARE, -1, A, NEW_A, null)));
    }

    private SimulationScenario causal(ContextId third) {
        var transitions = new LifecycleTransitions(CONSTRAINTS);
        var state = transitions.createContext(SecurityState.empty(SATELLITE, at(-150)), A, "A-up-SA", "A-down-SA", at(-150));
        state = transitions.createContext(state, B, "B-up-SA", "B-down-SA", at(-150));
        state = history(transitions, state, A, OLD_A, -150, -144, -140);
        state = history(transitions, state, B, OLD_B, -40, -30, -20);
        state = transitions.prepareKeys(state, A, NEW_A, at(-1));
        var payload = new SimulationContact.PayloadProfile(100, 100, 100, 0, 100, 0);
        return new SimulationScenario("controlled-reduced-" + third.groundStationId(), at(0), at(8), state, CONSTRAINTS,
                profile(100), List.of(new SimulationContact(window("C1", A, 0, 4), payload),
                new SimulationContact(window("C2", B, 4, 6), payload), new SimulationContact(window("C3", third, 6, 8), payload)), List.of());
    }

    private SecurityState history(LifecycleTransitions transitions, SecurityState state, ContextId context,
                                  KeyPairIds keys, long created, long activated, long end) {
        var contact = window("history-" + context.groundStationId(), context, activated, end);
        state = transitions.prepareKeys(state, context, keys, at(created));
        state = transitions.confirmInstallation(state, context, keys, contact, at(activated));
        state = transitions.activateKeys(state, context, keys, contact, at(activated));
        state = transitions.bindKeys(state, context, keys, contact, at(activated));
        state = transitions.startContext(state, context, contact, at(activated));
        state = transitions.recordProtectedTraffic(state, context, at(activated + 1), at(end), 1, 1);
        return transitions.stopContext(state, context, at(end));
    }

    private SimulationContact contact(String id, ContextId context, long start, long end, long offeredUp, long offeredDown) {
        return new SimulationContact(window(id, context, start, end),
                new SimulationContact.PayloadProfile(100, 100, offeredUp, offeredDown, 100, 100));
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
        return new SecurityCostProfile("synthetic-controller-tests", SecurityCostProfile.Provenance.SYNTHETIC_ASSUMPTION,
                "Reduced deterministic controller fixtures", costs);
    }
}
