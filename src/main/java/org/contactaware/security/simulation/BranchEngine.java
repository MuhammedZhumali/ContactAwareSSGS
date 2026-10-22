package org.contactaware.security.simulation;

import org.contactaware.security.ScheduledSecurityAction;
import java.time.Instant;
import java.util.*;

/** Nominal, bounded execution of explicitly chosen actions, with no search or policy selection. */
public final class BranchEngine {
    private final SimulationEngine engine = new SimulationEngine();
    private final SecurityActionGenerator generator = new SecurityActionGenerator();

    public PlanningBranch create(PlanningObservation observation) {
        Objects.requireNonNull(observation);
        return new PlanningBranch(observation, engine.planningSnapshot(observation), List.of());
    }

    public PlanningBranch execute(PlanningBranch branch, PlanningAction action) {
        var observation = branch.observation();
        if (!generator.candidates(observation).contains(action))
            throw new IllegalArgumentException("Action is not an admissible candidate at this decision boundary");
        return executeGenerated(branch, observation, action);
    }

    /** Trusted search path: action must come from the generator for this same immutable observation. */
    PlanningBranch executeGenerated(PlanningBranch branch, PlanningObservation observation, PlanningAction action) {
        ExecutionSnapshot next;
        if (action.kind() == PlanningAction.Kind.WAIT || action.kind() == PlanningAction.Kind.CONTINUE) {
            Instant until = action.until();
            // A requested wait may be interrupted by a newly relevant event or JIT preparation start.
            for (var wake : observation.wakeUps()) if (wake.isBefore(until)) until = wake;
            next = engine.planningAdvance(branch.snapshot(), until, action.kind() == PlanningAction.Kind.CONTINUE);
        } else {
            var intent = new ScheduledSecurityAction(action.securityAction(), action.at(), action.contextId(),
                    action.keys(), "Chosen nominal branch action");
            next = engine.planningStart(branch.snapshot(),
                    new PlannedOperation(nextOperationId(observation.issuedOperationIds()), intent, action.contactId()));
        }
        var plan = new ArrayList<>(branch.plan());
        plan.add(action);
        return new PlanningBranch(branch.origin(), next, plan);
    }

    static String nextOperationId(Set<String> issued) {
        long generation = 1;
        while (issued.contains("branch-operation:" + generation)) generation = Math.incrementExact(generation);
        return "branch-operation:" + generation;
    }
}
