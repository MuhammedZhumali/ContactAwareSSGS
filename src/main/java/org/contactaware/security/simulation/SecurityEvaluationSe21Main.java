package org.contactaware.security.simulation;

import org.contactaware.contacts.JsonFiles;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;

/** Separate SE2.1 replay/measurement harness; never writes into SE2. */
public final class SecurityEvaluationSe21Main {
    private SecurityEvaluationSe21Main() {}
    static final Path RAW = Path.of("target/security-evaluation-se2");

    public static void main(String[] args) throws Exception {
        var flags = new HashMap<String, String>();
        for (int i = 0; i < args.length; i += 2) {
            if (!args[i].startsWith("--") || i + 1 >= args.length || flags.put(args[i].substring(2), args[i + 1]) != null)
                throw new IllegalArgumentException("Expected unique --name value flags");
        }
        String mode = Objects.requireNonNull(flags.get("mode"), "mode");
        Path out = Path.of(Objects.requireNonNull(flags.get("out"), "out"));
        // JVM diagnostic files may be created before main; never replace a prior result/input.
        for (String file : List.of("result.json", "checkpoint.json", "observation.json", "replay.json"))
            if (Files.exists(out.resolve(file))) throw new IllegalArgumentException("Refusing to replace existing SE2.1 output: " + file);
        Files.createDirectories(out);
        if (mode.equals("capture")) {
            var replayed = RecordedDecisionReplay.before(RAW, flags.get("case"), Integer.parseInt(flags.get("index")));
            ExecutionCheckpointFiles.write(out.resolve("checkpoint.json"), replayed.snapshot());
            JsonFiles.write(out.resolve("observation.json"), replayed.observation());
            JsonFiles.write(out.resolve("replay.json"), Map.of("sourceCase", replayed.sourceCase(),
                    "decisionIndex", replayed.decisionIndex(), "at", replayed.snapshot().at(),
                    "replayedDecisions", replayed.replayedDecisions(), "checkedEvents", replayed.checkedEvents(),
                    "historicalSearchesPerformed", 0, "status", "MATCHED_FACTUAL_PREFIX"));
            return;
        }
        if (mode.equals("search")) {
            // This path also runs with the original SE2 JAR first on the classpath.
            // It deliberately uses only planner APIs that existed in that executable.
            var observation = ExecutionCheckpointFiles.read(Path.of(flags.get("observation")), PlanningObservation.class);
            var checkpoint = ExecutionCheckpointFiles.read(Path.of(flags.get("checkpoint")));
            ExecutionCheckpointFiles.write(out.resolve("checkpoint.json"), checkpoint);
            var timing = DecisionTimeModel.valueOf(flags.get("timing"));
            var planner = new ExactHorizonPlanner(new SearchLimits(Long.parseLong(flags.getOrDefault("nodes", "100000")),
                    seconds(flags.getOrDefault("seconds", "5"))), PlannerOptions.accelerated(), timing);
            var telemetry = new SearchRuntimeTelemetry.Recorder();
            long started = System.nanoTime();
            var result = planner.plan(observation);
            long elapsed = System.nanoTime() - started;
            JsonFiles.write(out.resolve("result.json"), Map.of("status", result.status(), "search", result,
                    "measurement", telemetry.snapshot(elapsed, null), "physicalTimeAdvancedBySearch", false));
            return;
        }
        if (mode.equals("controller")) {
            String name = Objects.requireNonNull(flags.get("case"));
            var summary = JsonFiles.JSON.readTree(RAW.resolve("summary.json").toFile()).required(name);
            var model = JsonFiles.JSON.readTree(RAW.resolve(summary.required("modelFile").asText()).toFile());
            var scenario = ExecutionCheckpointFiles.convert(model.required("scenario"), SimulationScenario.class);
            var period = ExecutionCheckpointFiles.convert(model.required("fixedPeriod"), Duration.class);
            var policy = SecurityEvaluationSe2Main.policy(summary.required("policy").asText(), period,
                    DecisionTimeModel.valueOf(summary.required("timingModel").asText()));
            var controller = new SecurityPolicyController(policy, seconds(flags.getOrDefault("controller-seconds", "60")),
                    (snapshot, count) -> {
                        try {
                            ExecutionCheckpointFiles.write(out.resolve("checkpoint.json"), snapshot);
                            ExecutionCheckpointFiles.writeAtomic(out.resolve("progress.json"), Map.of("decisions", count, "at", snapshot.at()));
                        } catch (java.io.IOException failure) { throw new java.io.UncheckedIOException(failure); }
                    });
            var initial = new SimulationEngine().start(scenario);
            var telemetry = new SearchRuntimeTelemetry.Recorder();
            long started = System.nanoTime();
            var result = controller.run(initial);
            long elapsed = System.nanoTime() - started;
            ExecutionCheckpointFiles.write(out.resolve("checkpoint.json"), result.snapshot());
            var fixture = new PolicyExperimentFixtures.Fixture(name, "AUTONOMOUS", "SE2.1 identical physical input",
                    Map.of("sourceCase", name), scenario, initial);
            var exported = FourPolicyExperimentMain.exportCase(out, "actual", fixture, result, elapsed, false);
            JsonFiles.write(out.resolve("result.json"), Map.of("status", result.status(), "summary", exported,
                    "measurement", telemetry.snapshot(elapsed, null), "physicalTimeAdvancedBySearch", false));
            return;
        }
        if (mode.equals("watchdog-probe")) {
            ExecutionCheckpointFiles.write(out.resolve("checkpoint.json"), ExecutionCheckpointFiles.read(Path.of(flags.get("checkpoint"))));
            JsonFiles.write(out.resolve("ready.json"), Map.of("injection", "intentional blocking probe; not a natural solver stall"));
            while (true) Thread.sleep(300_000);
        }
        if (mode.equals("check-checkpoint")) {
            var snapshot = ExecutionCheckpointFiles.read(Path.of(flags.get("checkpoint")));
            var restored = new SimulationEngine().controlledSnapshot(snapshot);
            JsonFiles.write(out.resolve("result.json"), Map.of("status", "RESTORED", "at", restored.at(),
                    "pendingOperations", restored.pendingOperations().size(), "frameInFlight", restored.inFlightFrame() != null));
            return;
        }
        throw new IllegalArgumentException("Unknown mode: " + mode);
    }

    private static Duration seconds(String text) {
        return Duration.ofNanos(new java.math.BigDecimal(text).movePointRight(9).longValueExact());
    }
}
