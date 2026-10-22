package org.contactaware.security.simulation;

import org.contactaware.security.*;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.contactaware.security.simulation.HorizonWitness.*;
import static org.contactaware.security.simulation.PlanningAction.Kind.*;

/** Manually selected primitive paths through the generator and shared engine; no search or policies. */
class PlanningContinuationIntegrationTest {
    private final SimulationEngine simulation = new SimulationEngine();
    private final BranchEngine engine = new BranchEngine();
    private final SecurityActionGenerator generator = new SecurityActionGenerator();
    private final ContinuationEvaluator evaluator = new ContinuationEvaluator();

    @Test void earlyXRenewalReproducesTheReferenceThroughGeneratedPrimitiveActions() {
        var branch = executeX(false);
        assertTotals(branch, 37, 830, 1240);
        assertThat(evaluator.evaluate(branch).evaluation()).isEqualTo(
                new BranchEvaluation(3, Duration.ofSeconds(37), Duration.ofMillis(630), 1240, 0));
        assertThat(branch.snapshot().securityState().key(NEW_A.uplink()).activatedAt()).isEqualTo(at(5));
        assertThat(branch.snapshot().securityState().key(NEW_A.uplink()).hardDeadline(CONSTRAINTS)).isEqualTo(at(155));
        assertThat(branch.plan()).extracting(PlanningAction::kind).contains(INSTALL, SWITCH, OPEN, REUSE, CONTINUE, WAIT);
    }

    @Test void stagedXInstallationSurvivesOldKeyExpiryAndMatchesAllFiveEarlyRenewalMetrics() {
        var early = executeX(false);
        var staged = executeX(true);
        assertTotals(staged, 37, 830, 1240);
        assertThat(evaluator.evaluate(staged).evaluation()).isEqualTo(evaluator.evaluate(early).evaluation());
        assertThat(staged.snapshot().securityState().key(NEW_A.uplink()).activatedAt()).isEqualTo(at(81));
        assertThat(staged.snapshot().securityState().key(NEW_A.uplink()).hardDeadline(CONSTRAINTS)).isEqualTo(at(231));
        assertThat(staged.snapshot().securityState().key(OLD_A.uplink()).deactivationReason()).isEqualTo(DeactivationReason.EXPIRED);
        assertThat(staged.snapshot().journal().stream().filter(e -> e.type() == SimulationEvent.Type.STEP_COMPLETED
                && e.action() == SecurityAction.INSTALL)).hasSize(1);
    }

    @Test void yNeedsOnlyReuseAndIncludesTheCommonPreparationExactlyOnce() {
        var branch = preparedOrigin(Sequence.Y);
        branch = completePrimitive(branch, REUSE, A);
        branch = transmitUntil(branch, at(20));
        branch = waitUntil(branch, at(30));
        branch = completePrimitive(branch, REUSE, B);
        branch = transmitUntil(branch, at(50));
        branch = waitUntil(branch, at(80));
        branch = completePrimitive(branch, REUSE, B);
        branch = transmitUntil(branch, at(85));

        assertTotals(branch, 42, 380, 240);
        assertThat(evaluator.evaluate(branch).evaluation()).isEqualTo(
                new BranchEvaluation(3, Duration.ofSeconds(42), Duration.ofMillis(180), 240, 0));
        assertThat(branch.plan()).noneMatch(a -> a.kind() == PREPARE || a.kind() == INSTALL || a.kind() == SWITCH);
        assertThat(branch.snapshot().securityState().key(NEW_A.uplink()).status()).isEqualTo(KeyStatus.PRE_ACTIVE);
        assertThat(branch.snapshot().securityState().key(NEW_A.uplink()).installedOnBothPeers()).isFalse();
    }

    @Test void preparationCreatesOneSpareAndActivationReleasesTheSlotForANewGeneration() {
        var base = scenario(Sequence.X, Plan.NEXT);
        var input = new SimulationScenario("manual-prepare-path", base.start(), base.end(), base.initialState(),
                base.constraints(), base.costs(), base.contacts(), List.of());
        var branch = engine.create(PlanningObservation.from(simulation.start(input), 2));
        var preparation = candidate(branch, PREPARE, A);
        var keys = preparation.keys();
        branch = engine.execute(branch, preparation);
        assertThat(branch.snapshot().pendingOperations()).hasSize(1);
        assertThat(branch.snapshot().issuedKeyIds()).contains(keys.uplink(), keys.downlink());
        assertThat(generator.candidates(branch.observation())).noneMatch(a -> a.kind() == PREPARE && A.equals(a.contextId()));

        branch = waitUntil(branch, at(0));
        assertThat(branch.snapshot().securityState().key(keys.uplink()).status()).isEqualTo(KeyStatus.PRE_ACTIVE);
        assertThat(generator.candidates(branch.observation())).noneMatch(a -> a.kind() == PREPARE && A.equals(a.contextId()));
        branch = completePrimitive(branch, INSTALL, A);
        branch = completePrimitive(branch, SWITCH, A);
        assertThat(candidate(branch, PREPARE, A).keys()).isNotEqualTo(keys);
        branch = completePrimitive(branch, OPEN, A);
        branch = transmitUntil(branch, at(20));

        assertThat(evaluator.evaluate(branch).evaluation()).isEqualTo(
                new BranchEvaluation(1, Duration.ofSeconds(14), Duration.ofMillis(710), 1080, 2));
        assertThat(branch.snapshot().createdKeys()).isEqualTo(2);
        assertThat(branch.snapshot().journal()).noneMatch(e -> e.type() == SimulationEvent.Type.ACTION_FAILED);
    }

    private PlanningBranch executeX(boolean staged) {
        var branch = preparedOrigin(Sequence.X);
        branch = completePrimitive(branch, INSTALL, A);
        if (staged) branch = completePrimitive(branch, REUSE, A);
        else {
            branch = completePrimitive(branch, SWITCH, A);
            branch = completePrimitive(branch, OPEN, A);
        }
        branch = transmitUntil(branch, at(20));
        branch = waitUntil(branch, at(30));
        branch = completePrimitive(branch, REUSE, B);
        branch = transmitUntil(branch, at(50));
        branch = waitUntil(branch, at(80));
        if (staged) {
            assertThat(branch.snapshot().securityState().key(OLD_A.uplink()).status()).isEqualTo(KeyStatus.DEACTIVATED);
            assertThat(branch.snapshot().securityState().key(NEW_A.uplink()).installedOnBothPeers()).isTrue();
            branch = completePrimitive(branch, SWITCH, A);
            branch = completePrimitive(branch, OPEN, A);
        } else branch = completePrimitive(branch, REUSE, A);
        return transmitUntil(branch, at(85));
    }

    private PlanningBranch preparedOrigin(Sequence sequence) {
        var base = scenario(sequence, Plan.NEXT);
        var warmupOnly = base.plan().stream().filter(p -> p.intent().action() == SecurityAction.PREPARE).toList();
        var input = new SimulationScenario("manual-continuation-" + sequence, base.start(), base.end(), base.initialState(),
                base.constraints(), base.costs(), base.contacts(), warmupOnly);
        var atC1 = simulation.advance(simulation.start(input), at(0));
        var observation = PlanningObservation.from(atC1, 2);
        assertThat(observation.accruedCosts().totalCpu()).isEqualTo(Duration.ofMillis(200));
        assertThat(observation.createdKeys()).isEqualTo(2);
        assertThat(observation.visibleContacts()).hasSize(3);
        return engine.create(observation);
    }

    private PlanningBranch completePrimitive(PlanningBranch branch, PlanningAction.Kind kind, ContextId context) {
        var action = candidate(branch, kind, context);
        var next = engine.execute(branch, action);
        assertThat(next.snapshot().at()).isEqualTo(action.at());
        return waitUntil(next, action.until());
    }

    private PlanningBranch transmitUntil(PlanningBranch branch, Instant until) {
        while (branch.snapshot().at().isBefore(until)) {
            var context = branch.observation().currentContact().window().groundStationId().equals("A") ? A : B;
            var action = candidate(branch, CONTINUE, context);
            var previous = branch.snapshot().at();
            branch = engine.execute(branch, action);
            assertThat(branch.snapshot().at()).isAfter(previous).isBeforeOrEqualTo(until);
            assertThat(branch.snapshot().inFlightFrame()).isNull();
        }
        return branch;
    }

    private PlanningBranch waitUntil(PlanningBranch branch, Instant until) {
        while (branch.snapshot().at().isBefore(until)) {
            var action = generator.candidates(branch.observation()).stream().filter(a -> a.kind() == WAIT
                    && !a.until().isAfter(until)).max(Comparator.comparing(PlanningAction::until)).orElseThrow();
            var previous = branch.snapshot().at();
            branch = engine.execute(branch, action);
            assertThat(branch.snapshot().at()).isAfter(previous).isBeforeOrEqualTo(until);
        }
        return branch;
    }

    private PlanningAction candidate(PlanningBranch branch, PlanningAction.Kind kind, ContextId context) {
        var matching = generator.candidates(branch.observation()).stream()
                .filter(a -> a.kind() == kind && context.equals(a.contextId())).toList();
        assertThat(matching).as("Manually requested %s at %s for %s", kind, branch.snapshot().at(), context).hasSize(1);
        return matching.getFirst();
    }

    private void assertTotals(PlanningBranch branch, long usefulSeconds, long totalCpuMillis, long bytes) {
        assertThat(branch.snapshot().at()).isEqualTo(at(85));
        assertThat(branch.snapshot().usefulSecureTime()).isEqualTo(Duration.ofSeconds(usefulSeconds));
        assertThat(branch.snapshot().accruedCosts().totalCpu()).isEqualTo(Duration.ofMillis(totalCpuMillis));
        assertThat(branch.snapshot().accruedCosts().managementBytes()).isEqualTo(bytes);
        assertThat(branch.snapshot().createdKeys()).isEqualTo(2);
        assertThat(evaluator.evaluate(branch).evaluation().servedContacts()).isEqualTo(3);
        assertThat(branch.snapshot().pendingOperations()).isEmpty();
        assertThat(branch.snapshot().inFlightFrame()).isNull();
        assertThat(generator.candidates(branch.observation())).isEmpty();
        assertThat(branch.snapshot().journal()).noneMatch(e -> e.type() == SimulationEvent.Type.ACTION_FAILED
                || e.type() == SimulationEvent.Type.SIMULATION_ENDED);
    }
}
