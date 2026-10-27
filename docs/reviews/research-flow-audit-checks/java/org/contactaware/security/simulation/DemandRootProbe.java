package org.contactaware.security.simulation;

import java.nio.file.Path;
import java.time.Duration;

/**
 * Audit probe (not project code). Loads a saved SE2.5 controller checkpoint (Multi stopped at C1 AOS),
 * rebuilds the policy observation for H and solves that single decision with the same options/tie-break
 * as the registered series but a larger node budget and no wall-clock limit. Prints the exact optimum and
 * the timing of security operations in the optimal plan. One decision only; no controller run.
 */
public final class DemandRootProbe {
    public static void main(String[] args) throws Exception {
        var snapshot = ExecutionCheckpointFiles.read(Path.of(args[0]));
        int horizon = Integer.parseInt(args[1]);
        var tieBreak = PlanTieBreak.valueOf(args[2]);
        long nodes = Long.parseLong(args[3]);
        var observation = PlanningObservation.from(snapshot, horizon);
        System.out.printf("root=%s H=%d visible=%d evaluationEnd=%s tieBreak=%s%n", observation.at(), horizon,
                observation.visibleContacts().size(), observation.evaluationEnd(), tieBreak);
        for (var c : observation.visibleContacts())
            System.out.printf("  contact %s %s [%s, %s) offered %d/%d min %d/%d%n", c.window().id(), c.window().groundStationId(),
                    c.window().start(), c.window().end(), c.payload().offeredUplinkBytes(), c.payload().offeredDownlinkBytes(),
                    c.payload().minimumUplinkBytes(), c.payload().minimumDownlinkBytes());
        var planner = new ExactHorizonPlanner(new SearchLimits(nodes, Duration.ZERO), PlannerOptions.acceleratedServiceFirst(),
                DecisionTimeModel.V2_EVENT_BOUNDARIES, tieBreak);
        long started = System.nanoTime();
        var result = planner.plan(observation);
        System.out.printf("status=%s nodes=%d elapsed=%.1fs score=%s first=%s%n", result.status(), result.exploredNodes(),
                (System.nanoTime() - started) / 1e9, result.score(), result.firstAction());
        for (var action : result.bestPlan())
            if (action.isSecurityOperation())
                System.out.printf("  %s %s at %s until %s contact=%s%n", action.kind(),
                        action.contextId().groundStationId(), action.at(), action.until(), action.contactId());
    }
}
