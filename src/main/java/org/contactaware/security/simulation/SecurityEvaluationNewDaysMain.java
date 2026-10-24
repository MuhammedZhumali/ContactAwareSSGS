package org.contactaware.security.simulation;

import org.contactaware.contacts.JsonFiles;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.io.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;

/** Offline new-day ISS input and execution adapter. No changes to solver, policy, execution or metrics. */
public final class SecurityEvaluationNewDaysMain {
    private SecurityEvaluationNewDaysMain() { }
    public static void main(String[] args) throws Exception {
        var flags = new HashMap<String, String>();
        for (int i = 0; i < args.length; i += 2)
            if (i + 1 == args.length || !args[i].startsWith("--") || flags.put(args[i].substring(2), args[i+1]) != null)
                throw new IllegalArgumentException("Unique --flag value pairs required");
        if ("configure".equals(flags.get("mode"))) {
            configure(flags);
            return;
        }
        if (!"run".equals(flags.remove("mode"))) throw new IllegalArgumentException("Use --mode configure/run");
        var model = JsonFiles.JSON.readTree(Path.of(flags.get("model")).toFile());
        var scenario = ExecutionCheckpointFiles.convert(model.required("scenario"), SimulationScenario.class);
        if (!flags.keySet().equals(Set.of("model", "policy", "rule", "out")))
            throw new IllegalArgumentException("Only --model --policy --rule --out");
        var horizon = switch (flags.get("policy")) {
            case "next" -> HorizonPolicy.next();
            case "multi" -> HorizonPolicy.multi(scenario.contacts().size() - 1);
            default -> throw new IllegalArgumentException("Only next/multi");
        };
        var rule = PlanTieBreak.valueOf(flags.get("rule"));
        var out = Path.of(flags.get("out"));
        if (Files.exists(out.resolve("checkpoint.json")) || Files.exists(out.resolve("result.json")))
            throw new IllegalArgumentException("Refusing to replace an earlier attempt");
        Files.createDirectories(out);

        var options = PlannerOptions.acceleratedServiceFirst();
        var planner = new ExactHorizonPlanner(new SearchLimits(2_000_000, Duration.ofSeconds(30)), options,
                DecisionTimeModel.V2_EVENT_BOUNDARIES, rule);
        SecurityPolicy policy = new SecurityPolicy() {
            public PolicyConfiguration configuration() { return horizon; }
            public ContinuationReuseGuard.Rules continuationRules() { return planner.continuationRules(); }
            public PolicySelection select(PlanningObservation o, List<PlanningAction> candidates) {
                return planner.selectUnrestricted(o, "SE2.4 " + rule + " SERVICE_FIRST reuse=ON");
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
        var fixture = new PolicyExperimentFixtures.Fixture(scenario.name(), "AUTONOMOUS", "Frozen SE2.4 new-day ISS input",
                Map.of("model", flags.get("model"), "tieBreak", rule.name(), "traversal", "SERVICE_FIRST", "reuse", "true"), scenario, initial);
        var exported = FourPolicyExperimentMain.exportCase(out, "actual", fixture, result, elapsed, true);
        var data = new LinkedHashMap<String, Object>();
        data.put("status", result.status()); data.put("tieBreak", rule); data.put("policy", horizon);
        data.put("options", options); data.put("reuseEnabled", true); data.put("summary", exported);
        data.put("measurement", runtime); data.put("computation", result.computation());
        data.put("simulationMetrics", result.simulation() == null ? null : result.simulation().metrics());
        data.put("securityMetrics", result.simulation() == null ? null : new SecurityMetricsCollector().collect(scenario, result.snapshot().journal()));
        data.put("physicalTimeAdvancedBySearch", false);
        JsonFiles.write(out.resolve("result.json"), data);
        System.out.println(result.status() + " SEARCH=" + result.computation().searches() + " SUFFIX_REUSE=" + result.computation().suffixReuses());
    }
    private static void configure(Map<String, String> flags) throws Exception {
        if (!flags.keySet().equals(Set.of("mode", "schedule", "from", "count", "out")))
            throw new IllegalArgumentException("Configure requires schedule/from/count/out");
        int from = Integer.parseInt(flags.get("from")), count = Integer.parseInt(flags.get("count"));
        if (from < 0 || (count != 3 && count != 4)) throw new IllegalArgumentException("Three or four original consecutive contacts");
        var raw = SecurityEvaluationSe2Fixtures.fragment(Path.of(flags.get("schedule")), from, count, 5400,
                PolicyExperimentFixtures.TrafficWorkload.LIGHT);
        var base = SecurityEvaluationSe2Fixtures.noticeFixture("SE2.4-ISS-" + from + "-" + count, raw, 1);
        var first = base.scenario().contacts().getFirst().window();
        var target = new org.contactaware.security.ContextId(first.satelliteId(), first.groundStationId());
        var attackAt = first.start().plusNanos(Math.multiplyExact(Duration.between(first.start(), first.end()).toNanos(), 90) / 100);
        var control = SecurityEvaluationSe2Main.incidentScenario(base.scenario(), target, null, "none");
        var attacked = SecurityEvaluationSe2Main.incidentScenario(base.scenario(), target, attackAt, "detected");
        var out = JsonFiles.newOutput(Path.of(flags.get("out")));
        JsonFiles.write(out.resolve("none.json"), Map.of("scenario", control, "provenance", base.input()));
        JsonFiles.write(out.resolve("late-detected.json"), Map.of("scenario", attacked, "provenance", base.input()));
        var observations = new LinkedHashMap<Integer, PlanningObservation>();
        for (int h : List.of(1, count - 1)) {
            var a = PlanningObservation.from(new SimulationEngine().start(control), h);
            var b = PlanningObservation.from(new SimulationEngine().start(attacked), h);
            if (!a.equals(b)) throw new IllegalStateException("Hidden attack changed initial planning observation");
            observations.put(h, a);
        }
        JsonFiles.write(out.resolve("initialization.json"), Map.of("initialization", base.initialization(),
                "initialState", control.initialState(), "target", target, "attackAt", attackAt,
                "groundNoticeAt", attackAt.plusSeconds(2), "initialObservationEquality", true,
                "observations", observations));
        System.out.println("Configured original windows; empty-key initialization; hidden attack projection equal");
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
