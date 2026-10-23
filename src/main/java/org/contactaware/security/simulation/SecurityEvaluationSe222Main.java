package org.contactaware.security.simulation;

import org.contactaware.contacts.JsonFiles;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.io.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;

/** Five pre-registered X repetitions only; same SE2 inputs, opt-in proven continuation reuse. */
public final class SecurityEvaluationSe222Main {
    private SecurityEvaluationSe222Main() { }
    public static void main(String[] args) throws Exception {
        var flags = new HashMap<String, String>();
        for (int i = 0; i < args.length; i += 2)
            if (i + 1 == args.length || !args[i].startsWith("--") || flags.put(args[i].substring(2), args[i+1]) != null)
                throw new IllegalArgumentException("Unique --flag value pairs required");
        if (!flags.keySet().equals(Set.of("case", "out"))) throw new IllegalArgumentException("Only --case and --out");
        var out = Path.of(flags.get("out"));
        if (Files.exists(out.resolve("checkpoint.json")) || Files.exists(out.resolve("result.json")))
            throw new IllegalArgumentException("Refusing to replace an earlier attempt");
        Files.createDirectories(out);
        Path source = Path.of("target/security-evaluation-se2");
        String name = flags.get("case");
        var spec = JsonFiles.JSON.readTree(source.resolve("summary.json").toFile()).required(name);
        if (!"multi".equals(spec.required("policy").asText()) || !name.startsWith("X-")
                || !"V2_EVENT_BOUNDARIES".equals(spec.required("timingModel").asText()))
            throw new IllegalArgumentException("This registered series contains only X/Multi with frozen timing");
        var model = JsonFiles.JSON.readTree(source.resolve(spec.required("modelFile").asText()).toFile());
        var scenario = ExecutionCheckpointFiles.convert(model.required("scenario"), SimulationScenario.class);
        var options = PlannerOptions.acceleratedServiceFirst();
        var planner = new ExactHorizonPlanner(new SearchLimits(2_000_000, Duration.ofSeconds(30)), options,
                DecisionTimeModel.V2_EVENT_BOUNDARIES, PlanTieBreak.CONTINUE_WAIT);
        SecurityPolicy policy = new SecurityPolicy() {
            public PolicyConfiguration configuration() { return HorizonPolicy.multi(2); }
            public ContinuationReuseGuard.Rules continuationRules() { return planner.continuationRules(); }
            public PolicySelection select(PlanningObservation o, List<PlanningAction> candidates) {
                return planner.selectUnrestricted(o, "SE2.2.2 CONTINUE_WAIT SERVICE_FIRST reuse=ON");
            }
        };
        var initial = new SimulationEngine().start(scenario);
        var controller = new SecurityPolicyController(policy, Duration.ofSeconds(60), (s, n) -> save(out, s, n), true);
        var measurement = new SearchRuntimeTelemetry.Recorder();
        long started = System.nanoTime();
        var result = controller.run(initial);
        long elapsed = System.nanoTime() - started;
        var runtime = measurement.snapshot(elapsed, null);
        save(out, result.snapshot(), result.decisions().size());
        var fixture = new PolicyExperimentFixtures.Fixture(name, "AUTONOMOUS", "Unchanged SE2 X input",
                Map.of("sourceCase", name, "tieBreak", "CONTINUE_WAIT", "traversal", "SERVICE_FIRST", "reuse", "true"), scenario, initial);
        var exported = FourPolicyExperimentMain.exportCase(out, "actual", fixture, result, elapsed, true);
        var data = new LinkedHashMap<String, Object>();
        data.put("status", result.status()); data.put("sourceCase", name); data.put("tieBreak", PlanTieBreak.CONTINUE_WAIT);
        data.put("options", options); data.put("reuseEnabled", true); data.put("summary", exported);
        data.put("measurement", runtime); data.put("computation", result.computation());
        data.put("simulationMetrics", result.simulation() == null ? null : result.simulation().metrics());
        data.put("securityMetrics", result.simulation() == null ? null : new SecurityMetricsCollector().collect(scenario, result.snapshot().journal()));
        data.put("physicalTimeAdvancedBySearch", false);
        JsonFiles.write(out.resolve("result.json"), data);
        System.out.println(result.status() + " SEARCH=" + result.computation().searches() + " SUFFIX_REUSE=" + result.computation().suffixReuses());
    }

    private static void save(Path out, ExecutionSnapshot snapshot, int count) {
        try {
            var diagnostic = ExecutionCheckpointFiles.writeWithDiagnostics(out.resolve("checkpoint.json"), snapshot);
            var line = JsonFiles.JSON.writer().without(SerializationFeature.INDENT_OUTPUT).writeValueAsString(
                    Map.of("at", snapshot.at(), "completedDecisions", count, "diagnostics", diagnostic));
            Files.writeString(out.resolve("checkpoint-writes.jsonl"), line + System.lineSeparator(), StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            try { JsonFiles.write(out.resolve("failure.json"), Map.of("status", "CHECKPOINT_WRITE_FAILED", "exception", e.toString())); }
            catch (IOException secondary) { e.addSuppressed(secondary); }
            throw new UncheckedIOException(e);
        }
    }
}
