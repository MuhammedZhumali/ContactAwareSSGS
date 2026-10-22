package org.contactaware.security.simulation;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.*;
import static org.assertj.core.api.Assertions.*;
import static org.contactaware.security.simulation.HorizonWitness.*;

class ControllerBudgetAndCheckpointTest {
    @TempDir Path temporary;

    @Test void persistedInFlightOperationAndFrameResumeWithoutDoubleCharging() throws Exception {
        var engine = new SimulationEngine();
        var scenario = scenario(Sequence.X, Plan.MULTI);
        var initial = engine.start(scenario);
        var operation = engine.advance(initial, at(2));
        assertThat(operation.pendingOperations()).isNotEmpty();
        var frame = List.of(at(1).plusMillis(125), at(6).plusMillis(125), at(7).plusMillis(125))
                .stream().map(t -> engine.advance(initial, t)).filter(s -> s.inFlightFrame() != null).findFirst().orElseThrow();
        for (var snapshot : List.of(operation, frame)) {
            var path = temporary.resolve("checkpoint.json");
            ExecutionCheckpointFiles.write(path, snapshot);
            var restored = ExecutionCheckpointFiles.read(path);
            assertThat(restored).isEqualTo(snapshot);
            assertThat(engine.resume(restored)).isEqualTo(engine.run(scenario));
            assertThat(path.resolveSibling("checkpoint.json.tmp")).doesNotExist();
        }
    }

    @Test void cumulativeDeadlineReturnsLastCommittedSnapshotAndResumesExactly() {
        var scenario = PolicyExperimentFixtures.witness(Sequence.X, false).scenario();
        var initial = new SimulationEngine().start(scenario);
        var saved = new AtomicReference<ExecutionSnapshot>();
        var eventCount = new AtomicInteger();
        var clock = new AtomicLong();
        ControlledSimulationResult stopped;
        try (var outer = SearchControl.open(Duration.ofNanos(1), SearchDiagnostics.StopReason.CONTROLLER_TIME_LIMIT,
                clock::get, phase -> { if (phase.equals("simulation.event") && eventCount.incrementAndGet() == 10) clock.set(1); })) {
            stopped = new SecurityPolicyController(new FixedPeriodPolicy(Duration.ofSeconds(60)), Duration.ZERO,
                    (snapshot, count) -> saved.set(snapshot)).run(initial);
        }
        assertThat(stopped.status()).isEqualTo(ControlledSimulationResult.Status.CONTROLLER_TIME_LIMIT);
        assertThat(stopped.simulation()).isNull();
        assertThat(stopped.snapshot()).isEqualTo(saved.get());
        assertThat(stopped.snapshot().journal()).noneMatch(e -> e.type() == SimulationEvent.Type.SIMULATION_ENDED);
        var resumed = new SecurityPolicyController(new FixedPeriodPolicy(Duration.ofSeconds(60))).run(stopped.snapshot());
        var baseline = new SecurityPolicyController(new FixedPeriodPolicy(Duration.ofSeconds(60))).run(initial);
        assertThat(resumed.status()).isEqualTo(ControlledSimulationResult.Status.FINISHED);
        assertThat(resumed.simulation()).isEqualTo(baseline.simulation());
    }

    @Test void interruptedAndExplicitlyCancelledControllersDoNotPublishFinalMetrics() {
        var initial = new SimulationEngine().start(PolicyExperimentFixtures.witness(Sequence.X, false).scenario());
        try (var scope = SearchControl.open(Duration.ZERO, SearchDiagnostics.StopReason.CONTROLLER_TIME_LIMIT)) {
            scope.cancel();
            var stopped = new SecurityPolicyController(new FixedPeriodPolicy(Duration.ofSeconds(60))).run(initial);
            assertThat(stopped.status()).isEqualTo(ControlledSimulationResult.Status.CANCELLED);
            assertThat(stopped.simulation()).isNull();
            assertThat(stopped.snapshot()).isEqualTo(initial);
        }
        try {
            Thread.currentThread().interrupt();
            var stopped = new SecurityPolicyController(new FixedPeriodPolicy(Duration.ofSeconds(60))).run(initial);
            assertThat(stopped.status()).isEqualTo(ControlledSimulationResult.Status.INTERRUPTED);
            assertThat(stopped.simulation()).isNull();
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally { Thread.interrupted(); }
    }

    @Test void replayRestoresOriginalDisclosureAndNotificationHistoryWithoutPastSearch() throws Exception {
        var raw = temporary.resolve("source");
        var scenario = SecurityEvaluationSe2Main.incidentScenario(PolicyExperimentFixtures.witness(Sequence.X, false).scenario(),
                A, at(10), "detected");
        var initial = new SimulationEngine().start(scenario);
        var run = new SecurityPolicyController(new FixedPeriodPolicy(Duration.ofSeconds(60))).run(initial);
        var fixture = new PolicyExperimentFixtures.Fixture("test", "AUTONOMOUS", "unit fixture", Map.of(), scenario, initial);
        FourPolicyExperimentMain.exportCase(raw, "test", fixture, run, 0, false);
        org.contactaware.contacts.JsonFiles.write(raw.resolve("model.json"), Map.of("scenario", scenario, "fixedPeriod", Duration.ofSeconds(60)));
        org.contactaware.contacts.JsonFiles.write(raw.resolve("summary.json"), Map.of("test", Map.of(
                "modelFile", "model.json", "eventsFile", "test.events.json", "decisionsFile", "test.decisions.json",
                "policy", "fixed", "timingModel", "V2_EVENT_BOUNDARIES")));
        int index = 0;
        while (run.decisions().get(index).at().isBefore(at(13))) index++;
        var replayed = RecordedDecisionReplay.before(raw, "test", index);
        assertThat(replayed.replayedDecisions()).isEqualTo(index);
        assertThat(replayed.checkedEvents()).isPositive();
        var path = temporary.resolve("checkpoint.json");
        ExecutionCheckpointFiles.write(path, replayed.snapshot());
        assertThat(ExecutionCheckpointFiles.read(path)).isEqualTo(replayed.snapshot());
        assertThat(replayed.snapshot().journal()).anyMatch(e -> e.type() == SimulationEvent.Type.KEY_DISCLOSED);
    }
}
