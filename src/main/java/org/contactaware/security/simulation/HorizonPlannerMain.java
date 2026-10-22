package org.contactaware.security.simulation;

import org.contactaware.contacts.JsonFiles;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;

/** Offline search experiment on the original synthetic witness, without a receding controller. */
public final class HorizonPlannerMain {
    private HorizonPlannerMain() {}

    public record Experiment(String provenance, String accounting, SearchLimits limits, PlannerOptions options,
                             PlanningObservation root, HorizonPlanResult result,
                             Duration cpuIncludingWarmup, Long bytesIncludingWarmup,
                             Long keysIncludingWarmup) {}

    /** Warmup only: neither OPEN nor ROTATE has been selected when the observation is taken. */
    public static PlanningObservation observation(HorizonWitness.Sequence sequence, int horizon) {
        return PlanningObservation.from(checkpoint(sequence), horizon);
    }

    /** Actual warmup checkpoint, retaining its journal for controlled full-run accounting. */
    public static ExecutionSnapshot checkpoint(HorizonWitness.Sequence sequence) {
        var source = HorizonWitness.scenario(sequence, HorizonWitness.Plan.NEXT);
        var scenario = new SimulationScenario(sequence + "-uncommitted", source.start(), source.end(),
                source.initialState(), source.constraints(), source.costs(), source.contacts(),
                List.of(HorizonWitness.operation("warmup", org.contactaware.security.SecurityAction.PREPARE,
                        -2, HorizonWitness.A, HorizonWitness.NEW_A, null)));
        var simulation = new SimulationEngine();
        return simulation.advance(simulation.start(scenario), HorizonWitness.at(0));
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 3 || args.length > 4) throw new IllegalArgumentException(
                "Usage: HorizonPlannerMain <new-or-empty-output-directory> <max-nodes; 0=unlimited> <max-millis; 0=unlimited> [accelerated|reuse-only|reference]");
        var limits = new SearchLimits(Long.parseLong(args[1]), Duration.ofMillis(Long.parseLong(args[2])));
        var options = args.length == 3 || args[3].equals("accelerated") ? PlannerOptions.accelerated()
                : args[3].equals("reference") ? PlannerOptions.reference()
                : args[3].equals("reuse-only") ? PlannerOptions.reuseOnly() : null;
        if (options == null) throw new IllegalArgumentException("Unknown planner mode");
        var out = JsonFiles.newOutput(Path.of(args[0]));
        var summaries = new TreeMap<String, HorizonPlanResult>();
        for (var sequence : HorizonWitness.Sequence.values()) for (int horizon : List.of(1, 2)) {
            var root = observation(sequence, horizon);
            var result = new ExactHorizonPlanner(limits, options).plan(root);
            var name = sequence + (horizon == 1 ? "-NEXT" : "-MULTI");
            // Independently replay the returned terminal continuation, including INCOMPLETE incumbents.
            if (result.score() != null) {
                var engine = new BranchEngine();
                var replay = engine.create(root);
                for (var action : result.bestPlan()) replay = engine.execute(replay, action);
                if (!replay.snapshot().at().equals(root.evaluationEnd())
                        || !new ContinuationEvaluator().evaluate(replay).evaluation().equals(result.score()))
                    throw new IllegalStateException("Terminal replay differs from returned score for " + name);
            }
            var experiment = new Experiment("SYNTHETIC_ASSUMPTION: original HorizonWitness contacts/costs; common warmup only",
                    "Score is incremental from t=0 to this observation's evaluationEnd; totals add past warmup once. "
                            + "H=1 and H=2 scores cover different intervals and are not full-run policy comparisons. "
                            + "INCOMPLETE is not an optimality certificate; elapsedNanos measures the search only.",
                    limits, options, root, result, result.score() == null ? null : root.accruedCosts().totalCpu().plus(result.score().controlCpu()),
                    result.score() == null ? null : Math.addExact(root.accruedCosts().managementBytes(), result.score().managementBytes()),
                    result.score() == null ? null : Math.addExact(root.createdKeys(), result.score().newKeys()));
            JsonFiles.write(out.resolve(name + ".json"), experiment);
            summaries.put(name, result);
            System.out.printf(Locale.ROOT, "%s: %s nodes=%d search=%.3fs first=%s score=%s%n", name,
                    result.status(), result.exploredNodes(), result.elapsedNanos() / 1_000_000_000.0,
                    result.firstAction() == null ? null : result.firstAction().kind(), result.score());
            var diagnostics = result.diagnostics();
            System.out.printf(Locale.ROOT, "  leaves=%d depth=%d WAIT-duplicates=%d memo-hits=%d pruned=%d generation=%.3fs execution=%.3fs%n",
                    diagnostics.terminalPlans(), diagnostics.maxDepth(), diagnostics.collapsedWaitTransitions(),
                    diagnostics.memoHits(), diagnostics.boundPrunedNodes(), diagnostics.generationNanos() / 1_000_000_000.0,
                    diagnostics.executionNanos() / 1_000_000_000.0);
        }
        JsonFiles.write(out.resolve("summary.json"), summaries);
    }
}
