package org.contactaware.security.simulation;

import org.contactaware.security.*;
import java.time.*;
import java.util.*;
import static org.contactaware.security.SecurityAction.*;
import static org.contactaware.security.simulation.HorizonWitness.*;

/**
 * Independent audit harness (not part of the project). Random small observations:
 * 1) reference DFS vs every acceleration (and reversed traversal): identical status/score/plan;
 * 2) optimistic bound admissibility: at every node of an exhaustive tree, bound >=lex best completion;
 * 3) memo-key sufficiency: equal PlanningStateKey => equal exhaustive best completion metrics.
 */
public final class AuditDifferential {
    static final SimulationEngine SIM = new SimulationEngine();
    static final BranchEngine ENGINE = new BranchEngine();
    static final SecurityActionGenerator GEN = new SecurityActionGenerator();
    static final ContinuationEvaluator EVAL = new ContinuationEvaluator();
    static final PlanningResultComparator CMP = new PlanningResultComparator();
    static long dfsNodes;
    static final long DFS_CAP = 400_000;

    public static void main(String[] args) {
        long seeds = args.length > 0 ? Long.parseLong(args[0]) : 200;
        long firstSeed = args.length > 1 ? Long.parseLong(args[1]) : 1;
        int compared = 0, skipped = 0, mismatches = 0, boundNodes = 0, boundViolations = 0, keyChecks = 0, keyViolations = 0;
        var kinds = new TreeMap<String, Integer>();
        for (long seed = firstSeed; seed < firstSeed + seeds; seed++) {
            PlanningObservation observation;
            try { observation = randomObservation(new Random(seed)); }
            catch (RuntimeException invalid) {
                skipped++; kinds.merge("invalid-fixture:" + invalid.getMessage(), 1, Integer::sum); continue;
            }
            var limits = new SearchLimits(3_000_000, Duration.ofSeconds(30));
            var reference = new ExactHorizonPlanner(limits, PlannerOptions.reference()).plan(observation);
            if (reference.status() == HorizonPlanResult.Status.INCOMPLETE) { skipped++; kinds.merge("ref-incomplete", 1, Integer::sum); continue; }
            compared++;
            kinds.merge("ref-" + reference.status() + (reference.firstAction() == null ? "" : "-" + reference.firstAction().kind()), 1, Integer::sum);
            if (reference.score() != null) kinds.merge("served=" + reference.score().servedContacts() + ",keys=" + reference.score().newKeys(), 1, Integer::sum);
            if (reference.score() != null) kinds.merge("planHas:" + new TreeSet<>(reference.bestPlan().stream().map(a -> a.kind().name()).toList()), 1, Integer::sum);
            var variants = new LinkedHashMap<String, ExactHorizonPlanner>();
            variants.put("accelerated", new ExactHorizonPlanner(limits, PlannerOptions.accelerated()));
            variants.put("accelerated-reversed", new ExactHorizonPlanner(limits, PlannerOptions.accelerated(), AuditDifferential::reverse));
            variants.put("reuseOnly", new ExactHorizonPlanner(limits, PlannerOptions.reuseOnly()));
            variants.put("boundOnly", new ExactHorizonPlanner(limits, new PlannerOptions(false, false, PlannerOptions.TraversalOrder.CANONICAL, 0, true, false, false)));
            variants.put("bound+canonical", new ExactHorizonPlanner(limits, new PlannerOptions(false, false, PlannerOptions.TraversalOrder.CANONICAL, 0, true, false, true)));
            variants.put("waitReductionOnly", new ExactHorizonPlanner(limits, new PlannerOptions(false, false, PlannerOptions.TraversalOrder.CANONICAL, 0, false, true, false)));
            variants.put("memoOnly-reversed", new ExactHorizonPlanner(limits, new PlannerOptions(false, true, PlannerOptions.TraversalOrder.CANONICAL, 0), AuditDifferential::reverse));
            for (var entry : variants.entrySet()) {
                var result = entry.getValue().plan(observation);
                if (result.status() == HorizonPlanResult.Status.INCOMPLETE) continue;
                if (result.status() != reference.status() || !Objects.equals(result.score(), reference.score())
                        || !result.bestPlan().equals(reference.bestPlan())) {
                    mismatches++;
                    System.out.printf("MISMATCH seed=%d variant=%s%n  ref=%s %s %s%n  got=%s %s %s%n", seed, entry.getKey(),
                            reference.status(), reference.score(), reference.bestPlan(), result.status(), result.score(), result.bestPlan());
                }
            }
            // Exhaustive tree with bound check and key-sufficiency check.
            var bound = new PlanningOptimisticBound(observation);
            var byKey = new HashMap<PlanningStateKey, Optional<BranchEvaluation>>();
            dfsNodes = 0;
            var violations = new ArrayList<String>();
            int[] counters = new int[4];
            try {
                exhaustive(ENGINE.create(observation), bound, byKey, violations, counters);
            } catch (CapReached cap) { kinds.merge("exhaustive-capped", 1, Integer::sum); }
            boundNodes += counters[0]; boundViolations += counters[1]; keyChecks += counters[2]; keyViolations += counters[3];
            for (var v : violations.subList(0, Math.min(3, violations.size()))) System.out.printf("seed=%d %s%n", seed, v);
        }
        System.out.printf("compared=%d skipped=%d planMismatches=%d boundNodes=%d boundViolations=%d keyRepeats=%d keyViolations=%d%n",
                compared, skipped, mismatches, boundNodes, boundViolations, keyChecks, keyViolations);
        System.out.println(kinds);
    }

    static final class CapReached extends RuntimeException { CapReached() { super(null, null, false, false); } }

    /** Best metric vector over all feasible terminal completions, by brute force (no pruning, no memo). */
    static Optional<BranchEvaluation> exhaustive(PlanningBranch branch, PlanningOptimisticBound bound,
                                                 Map<PlanningStateKey, Optional<BranchEvaluation>> byKey,
                                                 List<String> violations, int[] counters) {
        if (++dfsNodes > DFS_CAP) throw new CapReached();
        var key = PlanningStateKey.from(branch.snapshot());
        Optional<BranchEvaluation> best;
        if (key.rejected()) best = Optional.empty();
        else if (branch.snapshot().at().equals(branch.origin().evaluationEnd())) best = Optional.of(EVAL.evaluate(branch).evaluation());
        else {
            best = Optional.empty();
            var observation = branch.observation();
            for (var action : GEN.candidates(observation)) {
                var child = exhaustive(ENGINE.executeGenerated(branch, observation, action), bound, byKey, violations, counters);
                if (child.isPresent() && (best.isEmpty() || CMP.compareMetrics(child.get(), best.get()) < 0)) best = child;
            }
            if (best.isPresent()) {
                counters[0]++;
                var optimistic = bound.upperBound(branch);
                if (CMP.compareMetrics(optimistic, best.get()) > 0) {
                    counters[1]++;
                    violations.add("BOUND-VIOLATION at " + branch.plan() + " bound=" + optimistic + " best=" + best.get());
                }
            }
        }
        var prior = byKey.putIfAbsent(key, best);
        if (prior != null) {
            counters[2]++;
            if (!prior.equals(best)) { counters[3]++; violations.add("KEY-INSUFFICIENT at " + branch.plan() + " " + prior + " vs " + best); }
        }
        return best;
    }

    static List<PlanningAction> reverse(List<PlanningAction> actions) {
        var copy = new ArrayList<>(actions);
        Collections.reverse(copy);
        return copy;
    }

    static PlanningObservation randomObservation(Random r) {
        long period = 6 + r.nextInt(20);
        var constraints = new SecurityConstraints(Duration.ofSeconds(period), Duration.ofSeconds(period + r.nextInt(30)),
                Duration.ofSeconds(r.nextInt(3) == 0 ? 0 : 2 + r.nextInt(30)), Set.of(A, B), Set.of());
        var transitions = new LifecycleTransitions(constraints);
        var state = transitions.createContext(SecurityState.empty(SATELLITE, at(-60)), A, "A-SA-up", "A-SA-down", at(-60));
        state = transitions.createContext(state, B, "B-SA-up", "B-SA-down", at(-60));
        for (var ctx : List.of(A, B)) {
            var old = ctx == A ? OLD_A : OLD_B;
            // Chronological history: A in [-45,-20], B in [-19,-3]; LifecycleTransitions require monotone time.
            long creation = ctx == A ? -(25 + r.nextInt(21)) : -(8 + r.nextInt(12));
            long activation = creation + 1 + r.nextInt(2);
            long end = activation + 2;
            var past = window("hist-" + ctx.groundStationId(), ctx, activation, end);
            state = transitions.prepareKeys(state, ctx, old, at(creation));
            state = transitions.confirmInstallation(state, ctx, old, past, at(activation));
            state = transitions.activateKeys(state, ctx, old, past, at(activation));
            state = transitions.bindKeys(state, ctx, old, past, at(activation));
            state = transitions.startContext(state, ctx, past, at(activation));
            if (activation + 1 < end) state = transitions.recordProtectedTraffic(state, ctx, at(activation + 1), at(end), 1, 1);
            state = transitions.stopContext(state, ctx, at(end));
        }
        if (r.nextInt(3) == 0) state = transitions.prepareKeys(state, A, NEW_A, at(-1 - r.nextInt(3)));
        var costs = new EnumMap<SecurityAction, SecurityCostProfile.OperationCost>(SecurityAction.class);
        costs.put(PREPARE, new SecurityCostProfile.OperationCost(r.nextBoolean()
                ? List.of(phase(SecurityCostProfile.Resource.GROUND_CPU, 1 + r.nextInt(2), 0))
                : List.of(phase(SecurityCostProfile.Resource.GROUND_CPU, 1, 0), phase(SecurityCostProfile.Resource.WAIT, 1 + r.nextInt(3), 0))));
        for (var action : List.of(INSTALL, SWITCH, OPEN, REUSE)) costs.put(action, online(r));
        costs.put(STOP, new SecurityCostProfile.OperationCost(List.of()));
        costs.put(EXPIRE_CONTEXT, new SecurityCostProfile.OperationCost(List.of()));
        var profile = new SecurityCostProfile("audit-random", SecurityCostProfile.Provenance.SYNTHETIC_ASSUMPTION, "audit", costs);
        var contacts = new ArrayList<SimulationContact>();
        long t = r.nextInt(2);
        int count = 2 + r.nextInt(2);
        for (int i = 0; i < count; i++) {
            long len = 2 + r.nextInt(4);
            var ctx = r.nextBoolean() ? A : B;
            long minUp = r.nextInt(3) == 0 ? 0 : 100, minDown = minUp == 0 ? 100 : (r.nextBoolean() ? 0 : 100);
            var payload = new SimulationContact.PayloadProfile(100, 100, 100 * (1 + r.nextInt(3)), 100 * r.nextInt(3), minUp, Math.min(minDown, 100 * 2));
            if (payload.offeredDownlinkBytes() < payload.minimumDownlinkBytes() && r.nextBoolean())
                payload = new SimulationContact.PayloadProfile(100, 100, payload.offeredUplinkBytes(), 100, minUp, payload.minimumDownlinkBytes());
            contacts.add(new SimulationContact(window("C" + (i + 1), ctx, t, t + len), payload));
            t += len + r.nextInt(3);
        }
        var scenario = new SimulationScenario("audit", at(0), at(t + 1), state, constraints, profile, contacts, List.of());
        int horizon = 1 + r.nextInt(2);
        return PlanningObservation.from(SIM.start(scenario), horizon);
    }

    static SecurityCostProfile.OperationCost online(Random r) {
        var phases = new ArrayList<SecurityCostProfile.Phase>();
        phases.add(phase(SecurityCostProfile.Resource.GROUND_CPU, 1, 0));
        if (r.nextBoolean()) phases.add(new SecurityCostProfile.Phase(SecurityCostProfile.Resource.UPLINK, Duration.ofMillis(500), 10 + r.nextInt(50)));
        if (r.nextInt(3) == 0) phases.add(new SecurityCostProfile.Phase(SecurityCostProfile.Resource.SPACECRAFT_CPU, Duration.ofMillis(500), 0));
        return new SecurityCostProfile.OperationCost(phases);
    }

    static SecurityCostProfile.Phase phase(SecurityCostProfile.Resource resource, long seconds, long bytes) {
        return new SecurityCostProfile.Phase(resource, Duration.ofSeconds(seconds), bytes);
    }
}
