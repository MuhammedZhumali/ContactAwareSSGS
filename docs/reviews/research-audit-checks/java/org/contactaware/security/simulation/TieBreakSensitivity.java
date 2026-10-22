package org.contactaware.security.simulation;

import java.time.Duration;
import java.util.*;

/**
 * Audit probe (not project code). SE2 X/Y attack grid, detected mode, under the common controller,
 * executor and incident response. Two receding-horizon policies with the SAME exact five-metric optimum:
 *  - CANONICAL: project rule (first action of the canonical optimal plan);
 *  - LAZY: among root candidates whose optimal continuation attains the same five metrics, prefer
 *    CONTINUE, then WAIT (earliest), then security operations, i.e. defer management while optimal.
 * Only the tie-break among metric-optimal first actions differs.
 */
public final class TieBreakSensitivity {
    public static void main(String[] args) {
        var points = new LinkedHashMap<String, Long>();
        points.put("p10", 2L); points.put("p30", 6L); points.put("original", 10L);
        points.put("p70", 14L); points.put("p90", 18L); points.put("gap-midpoint", 25L);
        for (var sequence : HorizonWitness.Sequence.values())
            for (int horizon : List.of(1, 2))
                for (boolean lazy : List.of(false, true)) {
                    var base = PolicyExperimentFixtures.witness(sequence, false).scenario();
                    var control = run(base, horizon, lazy);
                    var m0 = control.simulation().metrics();
                    System.out.printf("%s H=%d %-9s control: status=%s metrics=%s%n", sequence, horizon,
                            lazy ? "LAZY" : "CANONICAL", control.status(), m0);
                    for (var point : points.entrySet()) {
                        var scenario = SecurityEvaluationSe2Main.incidentScenario(base, HorizonWitness.A,
                                HorizonWitness.at(point.getValue()), "detected");
                        var result = run(scenario, horizon, lazy);
                        if (result.status() != ControlledSimulationResult.Status.FINISHED) {
                            System.out.printf("   %-12s %s%n", point.getKey(), result.status()); continue;
                        }
                        var security = new SecurityMetricsCollector().collect(scenario, result.snapshot().journal());
                        System.out.printf("   %-12s recovery=%s detToRec=%s censorAt=%s exposedBefore/after=%d/%d confidential=%d%n",
                                point.getKey(), security.recoveryStatus(), security.detectionToRecovery(), security.censorAt(),
                                security.decryptableBeforeBytes(), security.decryptableAfterBytes(), security.confidentialDeliveredBytes());
                    }
                }
    }

    static ControlledSimulationResult run(SimulationScenario scenario, int horizon, boolean lazy) {
        var planner = new ExactHorizonPlanner(new SearchLimits(2_000_000, Duration.ZERO), PlannerOptions.accelerated());
        var configuration = horizon == 1 ? HorizonPolicy.next() : HorizonPolicy.multi(horizon);
        SecurityPolicy policy = new SecurityPolicy() {
            @Override public PolicyConfiguration configuration() { return configuration; }
            @Override public PolicySelection select(PlanningObservation observation, List<PlanningAction> candidates) {
                var search = planner.plan(observation);
                if (search.status() != HorizonPlanResult.Status.COMPLETE) return new PolicySelection(null, search, "incomplete");
                if (!lazy) return new PolicySelection(search.firstAction(), search, "canonical");
                // Candidate ranking for the lazy rule; ties are re-verified exactly through the executor.
                var ordered = new ArrayList<>(candidates);
                ordered.sort(Comparator.comparingInt((PlanningAction a) -> switch (a.kind()) {
                    case CONTINUE -> 0; case WAIT -> 1; default -> 2; }).thenComparing(PlanningAction::until));
                for (var action : ordered) {
                    if (action.equals(search.firstAction())) break;
                    if (tied(observation, action, search.score(), planner)) return PolicySelection.rule(action, "lazy tie");
                }
                return new PolicySelection(search.firstAction(), search, "canonical");
            }
        };
        return new SecurityPolicyController(policy).run(scenario);
    }

    /** Tie test needs the actual snapshot; reconstruct the nominal child via a planning branch instead. */
    static boolean tied(PlanningObservation observation, PlanningAction action, BranchEvaluation best, ExactHorizonPlanner planner) {
        var engine = new BranchEngine();
        var child = engine.executeGenerated(engine.create(observation), observation, action);
        if (PlanningStateKey.from(child.snapshot()).rejected()) return false;
        if (child.snapshot().at().equals(observation.evaluationEnd())) {
            return new PlanningResultComparator().compareMetrics(new ContinuationEvaluator().evaluate(child).evaluation(), best) == 0;
        }
        var continuation = planner.plan(child.observation());
        if (continuation.score() == null) return false;
        var total = TieProbe.combine(observation, child.observation(), continuation.score());
        return new PlanningResultComparator().compareMetrics(total, best) == 0;
    }
}
