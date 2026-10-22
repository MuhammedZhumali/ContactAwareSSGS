package org.contactaware.security.simulation;

import org.contactaware.contacts.JsonFiles;
import org.contactaware.security.*;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import static org.contactaware.security.simulation.HorizonWitness.*;

/** Separate timing experiment. Never writes to or changes the frozen v2 experiment directories. */
public final class TimeModelSensitivityMain {
    private TimeModelSensitivityMain() {}

    public record CounterexampleResult(DecisionTimeModel timeModel, boolean discardOffHorizonPreparation,
                                       PlanningResult result, long exploredNodes, long terminalPlans) {}
    public record Protocol(String model, String extraWaitRule, String filtering, String scope,
                           SearchLimits xySearchLimits) {}

    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("Usage: TimeModelSensitivityMain <new-or-empty-output-directory>");
        var out = JsonFiles.newOutput(Path.of(args[0]));
        var limits = new SearchLimits(100_000, Duration.ofSeconds(10));
        JsonFiles.write(out.resolve("protocol.json"), new Protocol("SYNTHETIC_ASSUMPTION; opt-in timing sensitivity",
                "At each decision, add a WAIT wakeup at observed time + observed PREPARE duration, if within the frozen horizon. "
                        + "Existing earlier events still interrupt WAIT/CONTINUE. No material or operation is created by the timer.",
                "Off-horizon PREPARE is retained in both production spaces. Its removal is tested only as a separate tiny exhaustive ablation.",
                "Four counterexample cells; prepared/autonomous X/Y; identical exact solver for Next H=1 and Multi H=2. "
                        + "Each timing model uses the same timer rule in forecast branches and actual execution.", limits));
        var root = counterexample();
        JsonFiles.write(out.resolve("counterexample.model.json"), root);
        var counterexamples = new ArrayList<CounterexampleResult>();
        for (var model : DecisionTimeModel.values()) for (boolean discard : List.of(false, true)) {
            var result = enumerate(root, model, discard);
            counterexamples.add(result);
            JsonFiles.write(out.resolve("counterexample-results.json"), counterexamples);
            System.out.println("counterexample " + model + " discard=" + discard + ": " + result.result().evaluation());
        }
        var summaries = new LinkedHashMap<String, FourPolicyExperimentMain.Summary>();
        for (var model : DecisionTimeModel.values()) for (boolean prepared : List.of(true, false))
            for (var sequence : Sequence.values()) for (var policy : List.of(HorizonPolicy.next(), HorizonPolicy.multi(2))) {
                var fixture = PolicyExperimentFixtures.witness(sequence, prepared);
                JsonFiles.write(out.resolve(fixture.id() + ".model.json"), new FourPolicyExperimentMain.FixtureModel(
                        fixture.id(), fixture.initialization(), fixture.provenance(), fixture.input(), fixture.scenario(),
                        fixture.checkpoint().at(), fixture.checkpoint().accruedCosts(), fixture.checkpoint().createdKeys(),
                        PolicyExperimentFixtures.payloadFeasibility(fixture.scenario().contacts())));
                String name = fixture.id() + "-" + policy.name() + "-" + model;
                System.out.println("Starting " + name);
                long start = System.nanoTime();
                var actual = controller(policy, model, limits).run(fixture.checkpoint());
                var summary = FourPolicyExperimentMain.exportCase(out, name, fixture, actual, System.nanoTime() - start, false);
                summaries.put(name, summary);
                JsonFiles.write(out.resolve("summary.json"), summaries);
                System.out.println(name + ": " + actual.status() + " " + summary.finalMetrics());
            }
    }

    public static SecurityPolicyController controller(HorizonPolicy policy, DecisionTimeModel model, SearchLimits limits) {
        var planner = new ExactHorizonPlanner(limits, PlannerOptions.accelerated(), model);
        return new SecurityPolicyController(new SecurityPolicy() {
            @Override public PolicyConfiguration configuration() { return policy; }
            @Override public List<Instant> additionalWakeUps(PlanningObservation observation) {
                return model.additionalWakeUps(observation);
            }
            @Override public PolicySelection select(PlanningObservation observation, List<PlanningAction> candidates) {
                var result = planner.plan(observation);
                return new PolicySelection(result.status() == HorizonPlanResult.Status.COMPLETE ? result.firstAction() : null,
                        result, "Timing sensitivity: " + model);
            }
        });
    }

    /** Independent exhaustive reference without memoization or optimistic pruning. */
    static CounterexampleResult enumerate(PlanningObservation root, DecisionTimeModel model, boolean discard) {
        long[] stats = {0, 0};
        var result = enumerate(new BranchEngine().create(root), model, discard, stats);
        return new CounterexampleResult(model, discard, result, stats[0], stats[1]);
    }

    private static PlanningResult enumerate(PlanningBranch branch, DecisionTimeModel model, boolean discard, long[] stats) {
        stats[0]++;
        if (branch.snapshot().at().equals(branch.origin().evaluationEnd())) {
            stats[1]++;
            return new ContinuationEvaluator().evaluate(branch);
        }
        var observation = model.apply(branch.observation());
        var actions = PlanningActionReduction.coalesceWaits(observation, new SecurityActionGenerator().candidates(observation));
        PlanningResult best = null;
        var engine = new BranchEngine();
        var comparator = new PlanningResultComparator();
        for (var action : actions) {
            if (discard && action.kind() == PlanningAction.Kind.PREPARE && B.equals(action.contextId())) continue;
            var candidate = enumerate(engine.executeGenerated(branch, observation, action), model, discard, stats);
            if (candidate != null && (best == null || comparator.compare(candidate, best) < 0)) best = candidate;
        }
        return best;
    }

    static PlanningObservation counterexample() {
        var constraints = new SecurityConstraints(Duration.ofSeconds(9), Duration.ofSeconds(100),
                Duration.ofSeconds(100), Set.of(A, B), Set.of());
        var transitions = new LifecycleTransitions(constraints);
        var state = transitions.createContext(SecurityState.empty(SATELLITE, at(-2)), A, "up-A", "down-A", at(-2));
        state = transitions.createContext(state, B, "up-B", "down-B", at(-2));
        state = transitions.prepareKeys(state, A, NEW_A, at(-1));
        state = transitions.confirmInstallation(state, A, NEW_A, window("historical-install", A, -1, 0), at(-1));
        var operations = new EnumMap<SecurityAction, SecurityCostProfile.OperationCost>(SecurityAction.class);
        for (var action : List.of(SecurityAction.PREPARE, SecurityAction.INSTALL, SecurityAction.SWITCH,
                SecurityAction.OPEN, SecurityAction.REUSE)) {
            long seconds = action == SecurityAction.PREPARE ? 4 : action == SecurityAction.INSTALL ? 3 : 1;
            operations.put(action, new SecurityCostProfile.OperationCost(List.of(new SecurityCostProfile.Phase(
                    SecurityCostProfile.Resource.GROUND_CPU, Duration.ofSeconds(seconds), 0))));
        }
        operations.put(SecurityAction.STOP, new SecurityCostProfile.OperationCost(List.of()));
        operations.put(SecurityAction.EXPIRE_CONTEXT, new SecurityCostProfile.OperationCost(List.of()));
        var costs = new SecurityCostProfile("synthetic-extra-decision-time", SecurityCostProfile.Provenance.SYNTHETIC_ASSUMPTION,
                "Finite-start counterexample: irrelevant preparation creates a decision time", operations);
        var payload = new SimulationContact.PayloadProfile(100, 100, Long.MAX_VALUE, 0, 100, 0);
        var scenario = new SimulationScenario("off-horizon-preparation-timer", at(0), at(14), state, constraints, costs,
                List.of(new SimulationContact(window("C1", A, 0, 9), payload),
                        new SimulationContact(window("C2", A, 12, 14), payload)), List.of());
        return PlanningObservation.from(new SimulationEngine().start(scenario), 1);
    }
}
