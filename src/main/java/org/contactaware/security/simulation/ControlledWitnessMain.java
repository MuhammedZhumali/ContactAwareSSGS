package org.contactaware.security.simulation;

import org.contactaware.contacts.JsonFiles;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;

/** Full actual X/Y runs with first-action execution and a fresh search at every decision boundary. */
public final class ControlledWitnessMain {
    private ControlledWitnessMain() { }

    public record Experiment(String provenance, String accounting, SearchLimits perDecisionLimits,
                             PlannerOptions options, ControlledSimulationResult result) { }
    public record Summary(ControlledSimulationResult.Status status, int decisions, long exploredNodes,
                          long searchNanos, SimulationResult.Metrics metrics) { }

    public static void main(String[] args) throws Exception {
        if (args.length < 3 || args.length > 4) throw new IllegalArgumentException(
                "Usage: ControlledWitnessMain <new-or-empty-output-directory> <max-nodes-per-decision;0=unlimited> "
                        + "<max-millis-per-decision;0=unlimited> [X-NEXT|X-MULTI|Y-NEXT|Y-MULTI]");
        if (args.length == 4 && !Set.of("X-NEXT", "X-MULTI", "Y-NEXT", "Y-MULTI").contains(args[3]))
            throw new IllegalArgumentException("Unknown witness/policy selection");
        var limits = new SearchLimits(Long.parseLong(args[1]), Duration.ofMillis(Long.parseLong(args[2])));
        var options = PlannerOptions.accelerated();
        var out = JsonFiles.newOutput(Path.of(args[0]));
        var summaries = new TreeMap<String, Summary>();
        for (var sequence : HorizonWitness.Sequence.values())
            for (var policy : List.of(HorizonPolicy.next(), HorizonPolicy.multi(2))) {
                var name = sequence + "-" + policy.name();
                if (args.length == 4 && !name.equals(args[3])) continue;
                var exact = new ExactHorizonPlanner(limits, options);
                HorizonPlanner planner = observation -> {
                    var forecast = exact.plan(observation);
                    if (forecast.elapsedNanos() >= 1_000_000_000L || forecast.status() != HorizonPlanResult.Status.COMPLETE)
                        System.out.printf(Locale.ROOT, "  %s decision at %s: %s nodes=%d search=%.3fs%n", name,
                                observation.at(), forecast.status(), forecast.exploredNodes(), forecast.elapsedNanos() / 1e9);
                    return forecast;
                };
                var result = new RecedingHorizonController(policy, planner).run(HorizonPlannerMain.checkpoint(sequence));
                var summary = new Summary(result.status(), result.decisions().size(), result.totalExploredNodes(),
                        result.totalSearchNanos(), result.simulation() == null ? null : result.simulation().metrics());
                JsonFiles.write(out.resolve(name + ".json"), new Experiment(
                        "SYNTHETIC_ASSUMPTION: original HorizonWitness; common PREPARE warmup; automatic receding execution",
                        "Final metrics come from the full actual event journal and include warmup exactly once. "
                                + "Forecast scores are incremental and cannot be summed. Search time is diagnostic only, "
                                + "without advancing simulation time. An unfinished run has a resumable checkpoint, not final metrics.",
                        limits, options, result));
                summaries.put(name, summary);
                // Persist each completed case even when a later case exhausts its decision budget.
                JsonFiles.write(out.resolve("summary.json"), summaries);
                System.out.printf(Locale.ROOT, "%s: %s at=%s decisions=%d nodes=%d search=%.3fs metrics=%s%n", name,
                        result.status(), result.snapshot().at(), result.decisions().size(), result.totalExploredNodes(),
                        result.totalSearchNanos() / 1e9, summary.metrics());
            }
    }
}
