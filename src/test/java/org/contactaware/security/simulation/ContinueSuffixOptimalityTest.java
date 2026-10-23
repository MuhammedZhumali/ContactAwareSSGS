package org.contactaware.security.simulation;

import org.contactaware.contacts.JsonFiles;
import org.contactaware.security.*;
import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.contactaware.security.simulation.HorizonWitness.*;
import static org.contactaware.security.simulation.PlanningAction.Kind.*;

/** One finite witness, not a general suffix cache or a controller change. */
class ContinueSuffixOptimalityTest {
    private static final int H = 2;
    private final SimulationEngine simulation = new SimulationEngine();
    private final BranchEngine branches = new BranchEngine();
    private final SecurityActionGenerator generator = new SecurityActionGenerator();
    private final ContinuationEvaluator evaluator = new ContinuationEvaluator();

    @Test
    void oneCompletedFrameRetainsTheFullContinueWaitOptimumAfterRebasing() throws Exception {
        // Actual controller-mode warmup: OPEN, finish OPEN, deliver one uplink frame.
        var actual = simulation.controlledSnapshot(simulation.start(fixture()));
        for (var kind : List.of(OPEN, WAIT, CONTINUE)) {
            var observation = PlanningObservation.from(actual, H);
            var action = generator.candidates(observation).stream().filter(a -> a.kind() == kind)
                    .min(Comparator.comparing(PlanningAction::until)).orElseThrow();
            actual = SecurityPolicyController.executeAction(actual, observation, action, OperationOutcomes.nominal());
        }
        var before = PlanningObservation.from(actual, H); // Same projection as the controller; no extra V2 timers.
        assertThat(before.at()).isEqualTo(at(2));
        assertThat(before.payloadProgress().get("C1")).isEqualTo(new ExecutionSnapshot.PayloadProgress(100, 0, false));
        assertThat(before.accruedCosts().totalCpu()).isEqualTo(Duration.ofSeconds(1));
        assertThat(before.ongoingOperations()).isEmpty();
        assertThat(before.inFlightFrame()).isNull();

        var original = solve(before);
        assertThat(original.firstAction().kind()).isEqualTo(CONTINUE);
        assertThat(original.score()).isEqualTo(score(2, 3, 3));
        // INSTALL is a genuine equal-objective alternative, not just an unused generated action.
        var install = generator.candidates(before).stream().filter(a -> a.kind() == INSTALL).findFirst().orElseThrow();
        var installFirst = planner().planWithFirstAction(before, install);
        assertComplete(installFirst);
        assertThat(installFirst.score()).isEqualTo(original.score());
        assertThat(new PlanningResultComparator(PlanTieBreak.CONTINUE_WAIT).comparePlans(
                original.bestPlan(), installFirst.bestPlan())).isNegative();

        var predicted = branches.execute(branches.create(before), original.firstAction());
        int journalSize = actual.journal().size();
        // Execute ONLY the first optimal action using the controller's existing actual executor.
        var afterActual = SecurityPolicyController.executeAction(actual, before, original.firstAction(), OperationOutcomes.nominal());
        var after = PlanningObservation.from(afterActual, H);
        assertThat(after).isEqualTo(predicted.observation()); // Every observation field, including costs/IDs/timers.
        assertThat(after.currentContact()).isEqualTo(before.currentContact());
        assertThat(after.visibleContacts()).isEqualTo(before.visibleContacts());
        assertThat(after.evaluationEnd()).isEqualTo(before.evaluationEnd());
        assertThat(after.at()).isEqualTo(at(3));
        assertThat(after.inFlightFrame()).isNull();
        assertThat(after.payloadProgress().get("C1")).isEqualTo(new ExecutionSnapshot.PayloadProgress(100, 100, true));
        assertThat(after.securityState().context(A).downlink().nextSequenceNumber())
                .isEqualTo(before.securityState().context(A).downlink().nextSequenceNumber() + 1);
        var delivered = afterActual.journal().subList(journalSize, afterActual.journal().size()).stream()
                .filter(e -> e.type() == SimulationEvent.Type.PAYLOAD_TRANSFERRED).toList();
        assertThat(delivered).hasSize(1);
        assertThat(delivered.getFirst().downlinkPayloadBytes()).isEqualTo(100);
        assertThat(delivered.getFirst().usefulTime()).isEqualTo(Duration.ofSeconds(1));
        assertThat(after.issuedKeyIds()).isEqualTo(before.issuedKeyIds());
        assertThat(after.issuedOperationIds()).isEqualTo(before.issuedOperationIds());

        var fresh = solve(after); // A new full DFS; no reused cache, incumbent, plan or original score.
        var suffix = List.copyOf(original.bestPlan().subList(1, original.bestPlan().size()));
        assertThat(fresh.bestPlan()).containsExactlyElementsOf(suffix);
        assertThat(fresh.score()).isEqualTo(score(1, 2, 3));
        assertThat(suffix.stream().filter(PlanningAction::isSecurityOperation).map(PlanningAction::kind).toList())
                .containsExactly(INSTALL, SWITCH, OPEN);

        var prefixScore = evaluator.evaluate(predicted).evaluation();
        assertThat(prefixScore).isEqualTo(score(1, 1, 0)); // This frame crosses the C1 service threshold.
        var rebased = branches.create(after);
        assertThat(evaluator.evaluate(rebased).evaluation()).isEqualTo(score(0, 0, 0));
        for (var action : suffix) {
            var forecast = predicted.observation();
            var newRootForecast = rebased.observation();
            assertThat(newRootForecast).isEqualTo(forecast);
            assertThat(generator.candidates(newRootForecast)).isEqualTo(generator.candidates(forecast));
            assertThat(KeyGenerationIds.next(A, newRootForecast.issuedKeyIds(), newRootForecast.constraints().blockedKeyIds()))
                    .isEqualTo(KeyGenerationIds.next(A, forecast.issuedKeyIds(), forecast.constraints().blockedKeyIds()));
            assertThat(BranchEngine.nextOperationId(newRootForecast.issuedOperationIds()))
                    .isEqualTo(BranchEngine.nextOperationId(forecast.issuedOperationIds()));
            predicted = branches.execute(predicted, action);
            rebased = branches.execute(rebased, action);
        }
        assertThat(rebased.snapshot().at()).isEqualTo(after.evaluationEnd());
        assertThat(rebased.observation()).isEqualTo(predicted.observation());
        assertThat(evaluator.evaluate(predicted).evaluation()).isEqualTo(original.score());
        assertThat(evaluator.evaluate(rebased).evaluation()).isEqualTo(fresh.score());
        assertThat(add(prefixScore, fresh.score())).isEqualTo(original.score());
        assertThat(rebased.snapshot().accruedCosts().totalCpu()).isEqualTo(Duration.ofSeconds(4));
        // 1 s of prior OPEN stays in cumulative costs; both continuation scores charge only 3 s.
        System.out.println("CONTINUATION_REUSE_EVIDENCE=" + JsonFiles.JSON.writer()
                .without(com.fasterxml.jackson.databind.SerializationFeature.INDENT_OUTPUT).writeValueAsString(Map.of(
                        "original", summary(original), "fixedInstall", summary(installFirst), "fresh", summary(fresh),
                        "prefixScore", prefixScore, "matchedSuffixSteps", suffix.size(),
                        "beforeAt", before.at(), "afterAt", after.at(), "evaluationEnd", after.evaluationEnd())));
    }

    private ExactHorizonPlanner planner() {
        // Existing 250k finite-oracle guard, plus the existing 30 s per-search safeguard; no acceleration.
        return new ExactHorizonPlanner(new SearchLimits(250_000, Duration.ofSeconds(30)), PlannerOptions.reference(),
                DecisionTimeModel.V2_EVENT_BOUNDARIES, PlanTieBreak.CONTINUE_WAIT);
    }
    private HorizonPlanResult solve(PlanningObservation observation) {
        var result = planner().plan(observation);
        assertComplete(result);
        return result;
    }
    private void assertComplete(HorizonPlanResult result) {
        assertThat(result.status()).as("nodes=%s; stop=%s", result.exploredNodes(), result.diagnostics().stopReason())
                .isEqualTo(HorizonPlanResult.Status.COMPLETE);
    }
    private static BranchEvaluation score(long served, long usefulSeconds, long cpuSeconds) {
        return new BranchEvaluation(served, Duration.ofSeconds(usefulSeconds), Duration.ofSeconds(cpuSeconds), 0, 0);
    }
    private static BranchEvaluation add(BranchEvaluation a, BranchEvaluation b) {
        return new BranchEvaluation(a.servedContacts() + b.servedContacts(), a.usefulSecureTime().plus(b.usefulSecureTime()),
                a.controlCpu().plus(b.controlCpu()), a.managementBytes() + b.managementBytes(), a.newKeys() + b.newKeys());
    }
    private static Map<String, Object> summary(HorizonPlanResult result) {
        return Map.of("status", result.status(), "nodes", result.exploredNodes(), "elapsedNanos", result.elapsedNanos(),
                "score", result.score(), "plan", result.bestPlan());
    }
    SimulationScenario fixture() {
        // Same small INSTALL/CONTINUE tie family as SE2.2.1; the root is after one prior frame.
        var original = initialState();
        var keys = new TreeMap<String, KeyState>();
        original.keys().values().stream().filter(k -> k.contextId().equals(A)).forEach(k -> keys.put(k.id(), k));
        var state = new SecurityState(SATELLITE, original.asOf(), Map.of(A, original.context(A)), keys);
        state = new LifecycleTransitions(CONSTRAINTS).prepareKeys(state, A, NEW_A, at(0));
        var operations = new EnumMap<SecurityAction, SecurityCostProfile.OperationCost>(SecurityAction.class);
        for (var action : List.of(SecurityAction.PREPARE, SecurityAction.INSTALL, SecurityAction.SWITCH, SecurityAction.OPEN, SecurityAction.REUSE))
            operations.put(action, new SecurityCostProfile.OperationCost(List.of(new SecurityCostProfile.Phase(
                    SecurityCostProfile.Resource.GROUND_CPU, Duration.ofSeconds(action == SecurityAction.PREPARE ? 100 : 1), 0))));
        for (var action : List.of(SecurityAction.STOP, SecurityAction.EXPIRE_CONTEXT))
            operations.put(action, new SecurityCostProfile.OperationCost(List.of()));
        var costs = new SecurityCostProfile("one-frame-suffix-oracle", SecurityCostProfile.Provenance.SYNTHETIC_ASSUMPTION,
                "Finite test only; no hardware calibration", operations);
        var payload = new SimulationContact.PayloadProfile(100, 100, 100, 100, 100, 100);
        return new SimulationScenario("one-frame-suffix", at(0), at(84), state, CONSTRAINTS, costs,
                List.of(new SimulationContact(window("C1", A, 0, 5), payload),
                        new SimulationContact(window("C2", A, 80, 84), payload)), List.of());
    }
}
