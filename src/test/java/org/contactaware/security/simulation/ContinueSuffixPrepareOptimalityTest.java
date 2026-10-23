package org.contactaware.security.simulation;

import org.contactaware.contacts.JsonFiles;
import org.contactaware.security.*;
import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.contactaware.security.simulation.HorizonWitness.*;
import static org.contactaware.security.simulation.PlanningAction.Kind.*;

/** A second finite suffix witness: generation and nonzero management traffic, without a prepared spare. */
class ContinueSuffixPrepareOptimalityTest {
    private static final int H = 2;
    private final SimulationEngine simulation = new SimulationEngine();
    private final BranchEngine branches = new BranchEngine();
    private final SecurityActionGenerator generator = new SecurityActionGenerator();
    private final ContinuationEvaluator evaluator = new ContinuationEvaluator();

    @Test
    void suffixCreatesAndUsesTheSameNewPairWithoutRechargingThePast() throws Exception {
        // Actual OPEN [0,1) is past work: 0.5 CPU seconds and 8 management bytes.
        var actual = simulation.controlledSnapshot(simulation.start(fixture()));
        for (var kind : List.of(OPEN, WAIT)) {
            var observation = PlanningObservation.from(actual, H);
            var action = generator.candidates(observation).stream().filter(a -> a.kind() == kind)
                    .min(Comparator.comparing(PlanningAction::until)).orElseThrow();
            actual = SecurityPolicyController.executeAction(actual, observation, action, OperationOutcomes.nominal());
        }
        var before = PlanningObservation.from(actual, H);
        assertThat(before.at()).isEqualTo(at(1));
        assertThat(before.securityState().keys()).hasSize(2);
        assertThat(before.securityState().keys().values()).allMatch(k -> k.status() == KeyStatus.ACTIVE);
        assertThat(before.accruedCosts().totalCpu()).isEqualTo(Duration.ofMillis(500));
        assertThat(before.accruedCosts().managementBytes()).isEqualTo(8);
        assertThat(actual.createdKeys()).isZero(); // Initial g1 is pre-existing material, not a new simulated PREPARE.
        assertThat(before.ongoingOperations()).isEmpty();
        assertThat(before.inFlightFrame()).isNull();
        assertThat(generator.candidates(before).stream().map(PlanningAction::kind).toList()).contains(CONTINUE, PREPARE);
        var nextIds = KeyGenerationIds.next(A, before.issuedKeyIds(), before.constraints().blockedKeyIds());
        assertThat(nextIds.uplink()).endsWith(":g2:up");
        assertThat(nextIds.downlink()).endsWith(":g2:down");

        var original = solve("original", before);
        assertThat(original.firstAction().kind()).isEqualTo(CONTINUE);
        assertThat(original.score()).isEqualTo(score(1, 2, 2500, 56, 2));
        var predicted = branches.execute(branches.create(before), original.firstAction());
        int journalSize = actual.journal().size();
        var afterActual = SecurityPolicyController.executeAction(actual, before, original.firstAction(), OperationOutcomes.nominal());
        var after = PlanningObservation.from(afterActual, H); // Ordinary controller projection, no old plan or memo.
        assertThat(after).isEqualTo(predicted.observation());
        assertThat(after.currentContact()).isEqualTo(before.currentContact());
        assertThat(after.visibleContacts()).isEqualTo(before.visibleContacts());
        assertThat(after.evaluationEnd()).isEqualTo(before.evaluationEnd());
        assertThat(after.at()).isEqualTo(at(2));
        assertThat(after.inFlightFrame()).isNull();
        assertThat(after.ongoingOperations()).isEmpty();
        assertThat(after.payloadProgress().get("C1")).isEqualTo(new ExecutionSnapshot.PayloadProgress(100, 0, false));
        assertThat(after.issuedKeyIds()).isEqualTo(before.issuedKeyIds());
        assertThat(after.issuedOperationIds()).isEqualTo(before.issuedOperationIds());
        assertThat(after.accruedCosts()).isEqualTo(before.accruedCosts());
        assertThat(afterActual.createdKeys()).isZero();
        // The frame ends exactly at the old deadline: delivery precedes deterministic expiry at t=2.
        var prefixEvents = afterActual.journal().subList(journalSize, afterActual.journal().size());
        var firstFrames = prefixEvents.stream().filter(e -> e.type() == SimulationEvent.Type.PAYLOAD_TRANSFERRED).toList();
        assertThat(firstFrames).hasSize(1);
        assertThat(firstFrames.getFirst().uplinkPayloadBytes()).isEqualTo(100);
        assertThat(firstFrames.getFirst().keyIds().uplink()).isEqualTo(before.securityState().context(A).uplink().keyId());
        assertThat(prefixEvents).anyMatch(e -> e.type() == SimulationEvent.Type.KEY_EXPIRED);

        var fresh = solve("fresh", after); // New planner and new full DFS, reference mode without memo or pruning.
        var suffix = List.copyOf(original.bestPlan().subList(1, original.bestPlan().size()));
        assertThat(fresh.bestPlan()).containsExactlyElementsOf(suffix); // Includes start/end times and generated key IDs.
        assertThat(fresh.score()).isEqualTo(score(1, 1, 2500, 56, 2));
        assertThat(suffix.stream().filter(PlanningAction::isSecurityOperation).map(PlanningAction::kind).toList())
                .containsExactly(PREPARE, INSTALL, SWITCH, OPEN);

        var prefixScore = evaluator.evaluate(predicted).evaluation();
        assertThat(prefixScore).isEqualTo(score(0, 1, 0, 0, 0));
        var rebased = branches.create(after);
        assertThat(evaluator.evaluate(rebased).evaluation()).isEqualTo(score(0, 0, 0, 0, 0));
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
        var end = rebased.snapshot();
        assertThat(end.at()).isEqualTo(after.evaluationEnd());
        assertThat(rebased.observation()).isEqualTo(predicted.observation());
        assertThat(evaluator.evaluate(predicted).evaluation()).isEqualTo(original.score());
        assertThat(evaluator.evaluate(rebased).evaluation()).isEqualTo(fresh.score());
        assertThat(add(prefixScore, fresh.score())).isEqualTo(original.score());
        assertThat(end.createdKeys()).isEqualTo(2);
        assertThat(end.securityState().keys()).hasSize(4);
        for (var id : List.of(nextIds.uplink(), nextIds.downlink())) {
            assertThat(end.securityState().key(id).createdAt()).isEqualTo(at(3));
            assertThat(end.securityState().key(id).activatedAt()).isEqualTo(at(5));
        }
        var newFrames = end.journal().stream().filter(e -> e.type() == SimulationEvent.Type.PAYLOAD_TRANSFERRED
                && e.keyIds().equals(nextIds)).toList();
        assertThat(newFrames).hasSize(1);
        // PAYLOAD_TRANSFERRED carries the admitted pair even when optional attack evidence is disabled.
        assertThat(newFrames.getFirst().at().minus(newFrames.getFirst().usefulTime())).isEqualTo(at(6));
        assertThat(newFrames.getFirst().at()).isEqualTo(at(7));
        assertThat(newFrames.getFirst().downlinkFrames()).isEqualTo(1);
        assertThat(newFrames.getFirst().downlinkPayloadBytes()).isEqualTo(100);
        assertThat(end.securityState().key(nextIds.downlink()).nextNonceCounter()).isEqualTo(1);
        assertThat(end.journal().stream().mapToLong(SimulationEvent::createdKeys).sum()).isEqualTo(2);
        assertThat(end.journal().stream().filter(e -> e.action() == SecurityAction.INSTALL)
                .mapToLong(e -> e.usage().managementBytes()).sum()).isEqualTo(48);
        assertThat(end.accruedCosts().totalCpu()).isEqualTo(Duration.ofSeconds(3));
        assertThat(end.accruedCosts().managementBytes()).isEqualTo(64); // 8 past + 56 future, not 64 charged again.
        assertThat(end.accruedCosts().managementBytes() - after.accruedCosts().managementBytes())
                .isEqualTo(fresh.score().managementBytes());
        emit("CONTINUE_PREPARE_CHECK", Map.of("status", "PASS", "matchedSuffixSteps", suffix.size(),
                "prefixScore", prefixScore, "generatedPair", nextIds, "cumulativeCosts", end.accruedCosts(),
                "createdKeys", end.createdKeys(), "beforeAt", before.at(), "afterAt", after.at(),
                "evaluationEnd", after.evaluationEnd()));
    }

    private HorizonPlanResult solve(String stage, PlanningObservation observation) throws Exception {
        var planner = new ExactHorizonPlanner(new SearchLimits(250_000, Duration.ofSeconds(30)), PlannerOptions.reference(),
                DecisionTimeModel.V2_EVENT_BOUNDARIES, PlanTieBreak.CONTINUE_WAIT);
        var result = planner.plan(observation);
        // Persist even an INCOMPLETE search before asserting; no fixture tuning or budget increases on failure.
        var evidence = new TreeMap<String, Object>();
        evidence.put("stage", stage);
        evidence.put("status", result.status());
        evidence.put("nodes", result.exploredNodes());
        evidence.put("elapsedNanos", result.elapsedNanos());
        evidence.put("score", result.score());
        evidence.put("plan", result.bestPlan());
        evidence.put("diagnostics", result.diagnostics());
        emit("CONTINUE_PREPARE_SEARCH", evidence);
        assertThat(result.status()).as("%s: nodes=%s; stop=%s", stage, result.exploredNodes(), result.diagnostics().stopReason())
                .isEqualTo(HorizonPlanResult.Status.COMPLETE);
        return result;
    }

    private static void emit(String label, Object evidence) throws Exception {
        System.out.println(label + "=" + JsonFiles.JSON.writer()
                .without(com.fasterxml.jackson.databind.SerializationFeature.INDENT_OUTPUT).writeValueAsString(evidence));
    }

    private static BranchEvaluation score(long served, long usefulSeconds, long cpuMillis, long bytes, long keys) {
        return new BranchEvaluation(served, Duration.ofSeconds(usefulSeconds), Duration.ofMillis(cpuMillis), bytes, keys);
    }

    private static BranchEvaluation add(BranchEvaluation a, BranchEvaluation b) {
        return new BranchEvaluation(a.servedContacts() + b.servedContacts(), a.usefulSecureTime().plus(b.usefulSecureTime()),
                a.controlCpu().plus(b.controlCpu()), a.managementBytes() + b.managementBytes(), a.newKeys() + b.newKeys());
    }

    SimulationScenario fixture() {
        var constraints = new SecurityConstraints(Duration.ofSeconds(8), Duration.ofSeconds(30), Duration.ofSeconds(30), Set.of(A), Set.of());
        var transitions = new LifecycleTransitions(constraints);
        var oldIds = KeyGenerationIds.next(A, Set.of(), Set.of());
        var state = transitions.createContext(SecurityState.empty(SATELLITE, at(-7)), A, "A-SA-up", "A-SA-down", at(-7));
        state = transitions.prepareKeys(state, A, oldIds, at(-7));
        var history = window("history", A, -6, 0);
        state = transitions.confirmInstallation(state, A, oldIds, history, at(-6));
        state = transitions.activateKeys(state, A, oldIds, history, at(-6));
        state = transitions.bindKeys(state, A, oldIds, history, at(-6));
        state = transitions.startContext(state, A, history, at(-6));
        state = transitions.recordProtectedTraffic(state, A, at(-5), at(-4), 1, 1);
        state = transitions.stopContext(state, A, at(0)); // g1 expires at t=2; there is no PRE_ACTIVE spare.
        var operations = new EnumMap<SecurityAction, SecurityCostProfile.OperationCost>(SecurityAction.class);
        for (var action : List.of(SecurityAction.PREPARE, SecurityAction.SWITCH))
            operations.put(action, new SecurityCostProfile.OperationCost(List.of(
                    phase(SecurityCostProfile.Resource.GROUND_CPU, 1000, 0))));
        operations.put(SecurityAction.INSTALL, new SecurityCostProfile.OperationCost(List.of(
                phase(SecurityCostProfile.Resource.UPLINK, 500, 32), phase(SecurityCostProfile.Resource.DOWNLINK, 500, 16))));
        for (var action : List.of(SecurityAction.OPEN, SecurityAction.REUSE))
            operations.put(action, new SecurityCostProfile.OperationCost(List.of(
                    phase(SecurityCostProfile.Resource.GROUND_CPU, 500, 0), phase(SecurityCostProfile.Resource.DOWNLINK, 500, 8))));
        for (var action : List.of(SecurityAction.STOP, SecurityAction.EXPIRE_CONTEXT))
            operations.put(action, new SecurityCostProfile.OperationCost(List.of()));
        var costs = new SecurityCostProfile("one-frame-prepare-suffix-oracle", SecurityCostProfile.Provenance.SYNTHETIC_ASSUMPTION,
                "Finite test only; no hardware calibration", operations);
        var payload = new SimulationContact.PayloadProfile(100, 100, 100, 100, 100, 100);
        return new SimulationScenario("one-frame-prepare-suffix", at(0), at(7), state, constraints, costs,
                List.of(new SimulationContact(window("C1", A, 0, 7), payload)), List.of());
    }

    private static SecurityCostProfile.Phase phase(SecurityCostProfile.Resource resource, long millis, long bytes) {
        return new SecurityCostProfile.Phase(resource, Duration.ofMillis(millis), bytes);
    }
}
