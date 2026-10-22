package org.contactaware.security.simulation;

import org.contactaware.security.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import static org.assertj.core.api.Assertions.*;
import static org.contactaware.security.simulation.HorizonWitness.*;

/** Deterministic budget injection; no sleeps, expensive witnesses or timing assertions. */
class SearchCancellationTest {
    @ParameterizedTest
    @ValueSource(strings = {"initialization.begin", "bound.initialization", "bound.loop", "branch.create",
            "generation.loop", "candidateProcessing.waitReduction", "branch.execute", "simulation.event",
            "stateKey.begin", "memo.publish", "search.complete", "result.publish"})
    void cancellationInsideEachMajorPhaseCannotPublishACompleteAnswer(String checkpoint) {
        var observation = observation();
        var planner = new ExactHorizonPlanner();
        var fakeClock = new AtomicLong();
        HorizonPlanResult stopped;
        try (var scope = SearchControl.open(Duration.ofNanos(1), SearchDiagnostics.StopReason.CONTROLLER_TIME_LIMIT,
                fakeClock::get, phase -> { if (phase.equals(checkpoint)) fakeClock.set(1); })) {
            stopped = planner.plan(observation);
        }
        assertThat(stopped.status()).isEqualTo(HorizonPlanResult.Status.INCOMPLETE);
        assertThat(stopped.diagnostics().stopReason()).isEqualTo(SearchDiagnostics.StopReason.CONTROLLER_TIME_LIMIT);
        assertThat(stopped.diagnostics().runtimeTelemetry().stoppedAtCheckpoint()).isEqualTo(checkpoint);
        if (checkpoint.equals("memo.publish")) assertThat(stopped.diagnostics().memoEntries()).isZero();
        if (checkpoint.equals("initialization.begin") || checkpoint.equals("bound.initialization")) {
            assertThat(stopped.exploredNodes()).isZero();
            assertThat(stopped.firstAction()).isNull();
            assertThat(stopped.score()).isNull();
        }
        // The same planner instance must not retain cancelled scopes or a partial memo suffix.
        var retried = planner.plan(observation);
        var reference = new ExactHorizonPlanner(SearchLimits.unlimited(), PlannerOptions.reference()).plan(observation);
        assertThat(retried.status()).isEqualTo(HorizonPlanResult.Status.COMPLETE);
        assertThat(retried.bestPlan()).isEqualTo(reference.bestPlan());
        assertThat(retried.score()).isEqualTo(reference.score());
    }

    @Test void cancellationDuringAnUpperBoundRetainsAbortedPhaseTime() {
        // Active keys admit both OPEN and REUSE. After solving the OPEN subtree,
        // the nonterminal REUSE sibling must enter an optimistic-bound phase.
        // The prepared-key chain used elsewhere has only terminal WAIT siblings.
        var source = observation();
        var contact = new SimulationContact(window("C1", A, 0, 3),
                new SimulationContact.PayloadProfile(100, 100, 100, 100, 100, 100));
        var scenario = new SimulationScenario("bound-cancellation-open-reuse", at(0), at(3), initialState(),
                CONSTRAINTS, source.costs(), List.of(contact), List.of());
        var observation = PlanningObservation.from(new SimulationEngine().start(scenario), 1);
        var boundsOnly = new PlannerOptions(false, false, PlannerOptions.TraversalOrder.CANONICAL, 0, true);
        var clock = new AtomicLong();
        try (var scope = SearchControl.open(Duration.ofNanos(1), SearchDiagnostics.StopReason.TIME_LIMIT,
                clock::get, phase -> { if (phase.equals("bound.begin")) clock.set(1); })) {
            var result = new ExactHorizonPlanner(SearchLimits.unlimited(), boundsOnly).plan(observation);
            assertThat(result.status()).isEqualTo(HorizonPlanResult.Status.INCOMPLETE);
            assertThat(result.diagnostics().terminalPlans()).isPositive();
            assertThat(result.diagnostics().runtimeTelemetry().stoppedAtCheckpoint()).isEqualTo("bound.begin");
            assertThat(result.diagnostics().runtimeTelemetry().phaseElapsedNanos().get("bound")).isPositive();
            assertThat(result.diagnostics().boundNanos()).isPositive();
        }
    }

    @Test void explicitCancellationAndThreadInterruptHaveSeparateReasonsAndDoNotClearInterrupt() {
        var observation = observation();
        try (var scope = SearchControl.open(Duration.ZERO, SearchDiagnostics.StopReason.CONTROLLER_TIME_LIMIT)) {
            scope.cancel();
            var result = new ExactHorizonPlanner().plan(observation);
            assertThat(result.diagnostics().stopReason()).isEqualTo(SearchDiagnostics.StopReason.CANCELLED);
            assertThat(result.status()).isEqualTo(HorizonPlanResult.Status.INCOMPLETE);
        }
        try {
            Thread.currentThread().interrupt();
            var result = new ExactHorizonPlanner().plan(observation);
            assertThat(result.diagnostics().stopReason()).isEqualTo(SearchDiagnostics.StopReason.INTERRUPTED);
            assertThat(result.status()).isEqualTo(HorizonPlanResult.Status.INCOMPLETE);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally { Thread.interrupted(); }
    }

    @Test void perDecisionClockIncludesInitializationAndKeepsExistingElapsedContract() {
        var clock = new AtomicLong();
        var result = new ExactHorizonPlanner(new SearchLimits(0, Duration.ofNanos(1)),
                java.util.function.UnaryOperator.identity(), clock::getAndIncrement).plan(observation());
        assertThat(result.status()).isEqualTo(HorizonPlanResult.Status.INCOMPLETE);
        assertThat(result.diagnostics().stopReason()).isEqualTo(SearchDiagnostics.StopReason.TIME_LIMIT);
        assertThat(result.diagnostics().runtimeTelemetry().stoppedAtCheckpoint()).isEqualTo("initialization.begin");
        assertThat(result.exploredNodes()).isZero();
        assertThat(result.elapsedNanos()).isEqualTo(2);
    }

    @Test void completedTelemetryAccountsForDisjointPhasesWithoutConfusingModeledCpu() {
        var result = new ExactHorizonPlanner().plan(observation());
        var telemetry = result.diagnostics().runtimeTelemetry();
        assertThat(result.status()).isEqualTo(HorizonPlanResult.Status.COMPLETE);
        assertThat(result.score()).isEqualTo(new BranchEvaluation(1, Duration.ofSeconds(2), Duration.ofSeconds(3), 0, 0));
        assertThat(telemetry.elapsedNanos()).isEqualTo(result.elapsedNanos());
        assertThat(telemetry.phaseElapsedNanos().get("initialization")).isPositive();
        long phases = telemetry.phaseElapsedNanos().values().stream().mapToLong(Long::longValue).sum();
        assertThat(phases + telemetry.unattributedElapsedNanos()).isEqualTo(result.elapsedNanos());
        if (telemetry.threadCpuNanos() != null) assertThat(telemetry.threadCpuNanos()).isNotNegative();
        if (telemetry.gcCollectionCountDelta() != null) assertThat(telemetry.gcCollectionCountDelta()).isNotNegative();
        if (telemetry.gcCollectionTimeMillisDelta() != null) assertThat(telemetry.gcCollectionTimeMillisDelta()).isNotNegative();
        assertThat(telemetry.gcAttribution()).contains("JVM-wide", "cannot be attributed exclusively");
        assertThat(telemetry.stoppedAtCheckpoint()).isNull();
    }

    private PlanningObservation observation() {
        var transitions = new LifecycleTransitions(CONSTRAINTS);
        var state = transitions.createContext(SecurityState.empty(SATELLITE, at(-1)), A, "up-SA", "down-SA", at(-1));
        state = transitions.prepareKeys(state, A, NEW_A, at(-1));
        var costs = new EnumMap<SecurityAction, SecurityCostProfile.OperationCost>(SecurityAction.class);
        costs.put(SecurityAction.PREPARE, cpu(100));
        for (var action : List.of(SecurityAction.INSTALL, SecurityAction.SWITCH, SecurityAction.OPEN, SecurityAction.REUSE))
            costs.put(action, cpu(1));
        costs.put(SecurityAction.STOP, new SecurityCostProfile.OperationCost(List.of()));
        costs.put(SecurityAction.EXPIRE_CONTEXT, new SecurityCostProfile.OperationCost(List.of()));
        var profile = new SecurityCostProfile("cancellation-small-chain", SecurityCostProfile.Provenance.SYNTHETIC_ASSUMPTION,
                "One-second online steps; preparation longer than horizon", costs);
        var contact = new SimulationContact(window("C1", A, 0, 5), new SimulationContact.PayloadProfile(100, 100, 100, 100, 100, 100));
        var scenario = new SimulationScenario("cancellation-chain", at(0), at(5), state, CONSTRAINTS, profile, List.of(contact), List.of());
        return PlanningObservation.from(new SimulationEngine().start(scenario), 1);
    }

    private SecurityCostProfile.OperationCost cpu(long seconds) {
        return new SecurityCostProfile.OperationCost(List.of(new SecurityCostProfile.Phase(
                SecurityCostProfile.Resource.GROUND_CPU, Duration.ofSeconds(seconds), 0)));
    }
}
