package org.contactaware.security.simulation;

import org.contactaware.contacts.JsonFiles;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;

/** Isolated SE2.2 harness; immutable SE2 scenarios and unchanged physical execution. */
public final class SecurityEvaluationSe22Main {
    private static final Path SOURCE = Path.of("target/security-evaluation-se2");
    private static final SearchLimits LIMITS = new SearchLimits(2_000_000, Duration.ofSeconds(30));
    private SecurityEvaluationSe22Main() { }

    public static void main(String[] args) throws Exception {
        var flags = new HashMap<String, String>();
        for (int i=0; i<args.length; i+=2) {
            if (i+1 == args.length || !args[i].startsWith("--") || flags.put(args[i].substring(2), args[i+1]) != null)
                throw new IllegalArgumentException("Expected unique --flag value");
        }
        var out = Path.of(Objects.requireNonNull(flags.get("out")));
        if (Files.exists(out.resolve("result.json")) || Files.exists(out.resolve("checkpoint.json")))
            throw new IllegalArgumentException("Refusing existing result/checkpoint");
        Files.createDirectories(out);
        switch (flags.get("mode")) {
            case "controller" -> controller(flags.get("case"), PlanTieBreak.valueOf(flags.get("rule")), out);
            case "probe" -> probe(Integer.parseInt(flags.get("index")), Integer.parseInt(flags.get("horizon")), out);
            default -> throw new IllegalArgumentException("Unknown mode");
        }
    }

    private static ExactHorizonPlanner planner(PlanTieBreak rule) {
        return new ExactHorizonPlanner(LIMITS, PlannerOptions.accelerated(), DecisionTimeModel.V2_EVENT_BOUNDARIES, rule);
    }

    public record KeyDecision(int index, PlanningObservation observation, PlanningAction executed,
                              HorizonPlanResult search, String reason) { }

    private static void controller(String sourceCase, PlanTieBreak rule, Path out) throws Exception {
        var spec = JsonFiles.JSON.readTree(SOURCE.resolve("summary.json").toFile()).required(sourceCase);
        if (!"V2_EVENT_BOUNDARIES".equals(spec.required("timingModel").asText()))
            throw new IllegalArgumentException("SE2.2 protocol uses original time model");
        var model = JsonFiles.JSON.readTree(SOURCE.resolve(spec.required("modelFile").asText()).toFile());
        var scenario = ExecutionCheckpointFiles.convert(model.required("scenario"), SimulationScenario.class);
        var config = switch (spec.required("policy").asText()) {
            case "next" -> HorizonPolicy.next(); case "multi" -> HorizonPolicy.multi(2);
            default -> throw new IllegalArgumentException("Only Next/Multi in SE2.2");
        };
        var searcher = planner(rule);
        SecurityPolicy policy = new SecurityPolicy() {
            public PolicyConfiguration configuration() { return config; }
            public PolicySelection select(PlanningObservation observation, List<PlanningAction> candidates) {
                var result = searcher.plan(observation);
                return new PolicySelection(result.status() == HorizonPlanResult.Status.COMPLETE ? result.firstAction() : null,
                        result, "SE2.2 exact horizon; tie-break=" + rule);
            }
        };
        var controller = new SecurityPolicyController(policy, Duration.ofSeconds(60), (snapshot, count) -> {
            try {
                ExecutionCheckpointFiles.write(out.resolve("checkpoint.json"), snapshot);
                ExecutionCheckpointFiles.writeAtomic(out.resolve("progress.json"), Map.of("decisions", count, "at", snapshot.at()));
            } catch (java.io.IOException e) { throw new java.io.UncheckedIOException(e); }
        });
        var initial = new SimulationEngine().start(scenario);
        var measurement = new SearchRuntimeTelemetry.Recorder();
        long started = System.nanoTime();
        var result = controller.run(initial);
        long elapsed = System.nanoTime() - started;
        var telemetry = measurement.snapshot(elapsed, null);
        ExecutionCheckpointFiles.write(out.resolve("checkpoint.json"), result.snapshot());
        var fixture = new PolicyExperimentFixtures.Fixture(sourceCase, "AUTONOMOUS", "SE2.2 frozen SE2 input",
                Map.of("sourceCase", sourceCase, "tieBreak", rule.name()), scenario, initial);
        var exported = FourPolicyExperimentMain.exportCase(out, "actual", fixture, result, elapsed, false);
        var keyDecisions = new ArrayList<KeyDecision>();
        for (int i=0; i<result.decisions().size(); i++) {
            var d = result.decisions().get(i);
            if (d.executedAction() != null && (d.executedAction().kind() == PlanningAction.Kind.PREPARE
                    || d.executedAction().kind() == PlanningAction.Kind.INSTALL))
                keyDecisions.add(new KeyDecision(i, d.observation(), d.executedAction(), d.search(), d.reason()));
        }
        JsonFiles.write(out.resolve("key-decisions.json"), keyDecisions);
        var data = new LinkedHashMap<String, Object>();
        data.put("status", result.status()); data.put("sourceCase", sourceCase); data.put("tieBreak", rule);
        data.put("summary", exported); data.put("measurement", telemetry);
        data.put("simulationMetrics", result.simulation() == null ? null : result.simulation().metrics());
        data.put("securityMetrics", result.simulation() == null ? null
                : new SecurityMetricsCollector().collect(scenario, result.snapshot().journal()));
        data.put("physicalTimeAdvancedBySearch", false);
        JsonFiles.write(out.resolve("result.json"), data);
    }

    private static void probe(int index, int horizon, Path out) throws Exception {
        var replay = RecordedDecisionReplay.before(SOURCE, "X-multi-none", index);
        var origin = PlanningObservation.from(replay.snapshot(), horizon);
        ExecutionCheckpointFiles.write(out.resolve("checkpoint.json"), replay.snapshot());
        JsonFiles.write(out.resolve("observation.json"), origin);
        var candidates = new SecurityActionGenerator().candidates(origin);
        var rows = new ArrayList<Map<String, Object>>();
        var data = new LinkedHashMap<String, Object>();
        data.put("sourceCase", "X-multi-none"); data.put("index", index); data.put("horizon", horizon);
        data.put("at", origin.at()); data.put("evaluationEnd", origin.evaluationEnd());
        data.put("historicalSearchesPerformed", 0); data.put("checkedEvents", replay.checkedEvents());
        data.put("candidates", candidates); data.put("searches", rows);
        data.put("status", "RUNNING");
        try (var scope = SearchControl.open(Duration.ofSeconds(120), SearchDiagnostics.StopReason.CONTROLLER_TIME_LIMIT)) {
            for (var rule : PlanTieBreak.values()) {
                var searcher = planner(rule);
                SearchControl.checkpoint("probe.root");
                var optimum = searcher.plan(origin);
                rows.add(Map.of("rule", rule, "kind", "ROOT", "result", optimum));
                ExecutionCheckpointFiles.writeAtomic(out.resolve("progress.json"), data);
                for (int i=0; i<candidates.size(); i++) {
                    SearchControl.checkpoint("probe.alternative");
                    var alternative = searcher.planWithFirstAction(origin, candidates.get(i));
                    var row = new LinkedHashMap<String, Object>();
                    row.put("rule", rule); row.put("kind", "FIXED_FIRST"); row.put("candidateIndex", i);
                    row.put("firstAction", candidates.get(i)); row.put("result", alternative);
                    boolean proved = optimum.status() == HorizonPlanResult.Status.COMPLETE
                            && alternative.status() == HorizonPlanResult.Status.COMPLETE;
                    row.put("provedEqualOptimum", proved ? new PlanningResultComparator().compareMetrics(optimum.score(), alternative.score()) == 0 : null);
                    rows.add(row);
                    ExecutionCheckpointFiles.writeAtomic(out.resolve("progress.json"), data);
                }
            }
            data.put("status", rows.stream().allMatch(r -> ((HorizonPlanResult)r.get("result")).status() == HorizonPlanResult.Status.COMPLETE)
                    ? "COMPLETE" : "INCOMPLETE");
        } catch (SearchControl.Stopped stopped) { data.put("status", stopped.reason()); }
        JsonFiles.write(out.resolve("result.json"), data);
    }
}
