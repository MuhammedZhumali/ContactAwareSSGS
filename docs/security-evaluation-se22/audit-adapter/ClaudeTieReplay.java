package org.contactaware.security.simulation;

import org.contactaware.contacts.JsonFiles;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;

/** Calls original unmodified Claude run(), restricting only its unfiltered main's case selection. */
public final class ClaudeTieReplay {
    public static void main(String[] args) throws Exception {
        int horizon = Integer.parseInt(args[0]);
        boolean lazy = Boolean.parseBoolean(args[1]);
        Path out = Path.of(args[2]);
        if (Files.exists(out)) throw new IllegalArgumentException("Existing output");
        var base = PolicyExperimentFixtures.witness(HorizonWitness.Sequence.X, false).scenario();
        var scenario = SecurityEvaluationSe2Main.incidentScenario(base, HorizonWitness.A, HorizonWitness.at(10), "detected");
        long started = System.nanoTime();
        ControlledSimulationResult result;
        try (var scope = SearchControl.open(Duration.ofSeconds(60), SearchDiagnostics.StopReason.CONTROLLER_TIME_LIMIT)) {
            result = TieBreakSensitivity.run(scenario, horizon, lazy);
        }
        var summary = new LinkedHashMap<String, Object>();
        summary.put("status", result.status()); summary.put("horizon", horizon); summary.put("lazy", lazy);
        summary.put("elapsedNanos", System.nanoTime()-started);
        summary.put("metrics", result.simulation() == null ? null : result.simulation().metrics());
        summary.put("security", result.simulation() == null ? null : new SecurityMetricsCollector().collect(scenario, result.snapshot().journal()));
        summary.put("actions", result.decisions().stream().map(ControlledSimulationResult.Decision::executedAction).toList());
        JsonFiles.write(out, summary);
        System.out.println("Claude original run: H="+horizon+" lazy="+lazy+" status="+result.status());
    }
}
