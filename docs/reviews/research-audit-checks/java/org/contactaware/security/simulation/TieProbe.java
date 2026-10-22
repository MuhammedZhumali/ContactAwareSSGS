package org.contactaware.security.simulation;

import java.time.Duration;
import java.util.*;

/**
 * Audit probe (not project code). Replays the SE2 X/autonomous Multi (H=2) control run without attack.
 * At every decision whose chosen first action is a security operation, executes each alternative
 * root candidate through the common executor, re-solves from the child with the same exact planner and
 * reports whether the alternative attains the same five metrics (i.e. the choice was a canonical tie-break).
 */
public final class TieProbe {
    public static void main(String[] args) {
        var sequence = HorizonWitness.Sequence.valueOf(args.length > 0 ? args[0] : "X");
        int horizon = args.length > 1 ? Integer.parseInt(args[1]) : 2;
        var scenario = PolicyExperimentFixtures.witness(sequence, false).scenario();
        var engine = new SimulationEngine();
        var generator = new SecurityActionGenerator();
        var planner = new ExactHorizonPlanner(new SearchLimits(2_000_000, Duration.ZERO), PlannerOptions.accelerated());
        var current = engine.controlledSnapshot(engine.start(scenario));
        int decisions = 0;
        while (current.at().isBefore(scenario.end()) && decisions < 400) {
            var observation = PlanningObservation.from(current, horizon);
            var candidates = generator.candidates(observation);
            var result = planner.plan(observation);
            var chosen = result.firstAction();
            if (chosen != null && chosen.isSecurityOperation()) {
                System.out.printf("t=%s chosen=%s %s score=%s%n", sec(observation), chosen.kind(), chosen.contextId(), result.score());
                for (var alternative : candidates) {
                    if (alternative.equals(chosen)) continue;
                    var child = SecurityPolicyController.executeAction(current, observation, alternative, OperationOutcomes.nominal());
                    var childObservation = PlanningObservation.from(child, horizon);
                    if (!childObservation.evaluationEnd().equals(observation.evaluationEnd())) {
                        System.out.printf("   alt %-8s -> horizon changed, skipped%n", alternative.kind());
                        continue;
                    }
                    var continuation = planner.plan(childObservation);
                    if (continuation.score() == null) { System.out.printf("   alt %-8s -> %s%n", alternative.kind(), continuation.status()); continue; }
                    var total = combine(observation, childObservation, continuation.score());
                    int order = new PlanningResultComparator().compareMetrics(total, result.score());
                    System.out.printf("   alt %-8s until=%s -> %s total=%s%n", alternative.kind(),
                            Duration.between(HorizonWitness.EPOCH, alternative.until()).toMillis() / 1000.0,
                            order == 0 ? "TIED" : order > 0 ? "worse" : "BETTER?!", total);
                }
            }
            current = SecurityPolicyController.executeAction(current, observation, chosen, OperationOutcomes.nominal());
            decisions++;
        }
    }

    static double sec(PlanningObservation observation) {
        return Duration.between(HorizonWitness.EPOCH, observation.at()).toMillis() / 1000.0;
    }

    /** Root-relative metrics of (executed alternative step + optimal continuation from the child). */
    static BranchEvaluation combine(PlanningObservation root, PlanningObservation child, BranchEvaluation continuation) {
        long servedRoot = served(root), servedChild = served(child);
        return new BranchEvaluation(servedChild - servedRoot + continuation.servedContacts(),
                child.usefulSecureTime().minus(root.usefulSecureTime()).plus(continuation.usefulSecureTime()),
                child.accruedCosts().totalCpu().minus(root.accruedCosts().totalCpu()).plus(continuation.controlCpu()),
                child.accruedCosts().managementBytes() - root.accruedCosts().managementBytes() + continuation.managementBytes(),
                child.createdKeys() - root.createdKeys() + continuation.newKeys());
    }

    static long served(PlanningObservation observation) {
        long count = 0;
        for (var contact : observation.visibleContacts()) {
            var p = observation.payloadProgress().get(contact.window().id());
            long up = p == null ? 0 : p.uplinkBytes(), down = p == null ? 0 : p.downlinkBytes();
            if (up >= contact.payload().minimumUplinkBytes() && down >= contact.payload().minimumDownlinkBytes()) count++;
        }
        return count;
    }
}
