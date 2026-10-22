package org.contactaware.security.simulation;

import org.contactaware.security.SecurityAction;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.contactaware.security.simulation.HorizonWitness.*;

/** Original, unscaled X/Y contacts and costs; no supplied PREPARE or fallback incumbent. */
class AutonomousPolicyComparisonTest {
    @Test void autonomousXCompletesEveryExactDecisionAndStagesAInTheFirstContact() {
        var next = run(Sequence.X, HorizonPolicy.next());
        var multi = run(Sequence.X, HorizonPolicy.multi(2));
        assertMetrics(next, 2, 38, 120, 160, 0);
        assertMetrics(multi, 3, 37, 830, 1240, 2);
        assertThat(multi.decisions()).anySatisfy(d -> {
            assertThat(d.executedAction().kind()).isEqualTo(PlanningAction.Kind.INSTALL);
            assertThat(d.executedAction().contextId()).isEqualTo(A);
            assertThat(d.executedAction().contactId()).isEqualTo("C1");
        });
    }

    @Test void autonomousYDoesNotPrepareUnnecessaryMaterialAndMatchesNext() {
        var next = run(Sequence.Y, HorizonPolicy.next());
        var multi = run(Sequence.Y, HorizonPolicy.multi(2));
        assertMetrics(next, 3, 42, 180, 240, 0);
        assertMetrics(multi, 3, 42, 180, 240, 0);
        assertThat(multi.simulation().events()).noneMatch(event -> event.action() == SecurityAction.PREPARE);
    }

    private ControlledSimulationResult run(Sequence sequence, HorizonPolicy policy) {
        var fixture = PolicyExperimentFixtures.witness(sequence, false);
        var result = new RecedingHorizonController(policy,
                new ExactHorizonPlanner(new SearchLimits(100_000, Duration.ZERO))).run(fixture.checkpoint());
        assertThat(result.status()).isEqualTo(ControlledSimulationResult.Status.FINISHED);
        assertThat(result.decisions()).allSatisfy(d -> {
            assertThat(d.search().status()).isEqualTo(HorizonPlanResult.Status.COMPLETE);
            assertThat(d.search().diagnostics().stopReason()).isEqualTo(SearchDiagnostics.StopReason.NONE);
        });
        assertThat(new MetricsCollector().collect(fixture.scenario().contacts(), result.simulation().events()))
                .isEqualTo(result.simulation().metrics());
        return result;
    }

    private void assertMetrics(ControlledSimulationResult result, long served, long usefulSeconds,
                               long cpuMillis, long bytes, long keys) {
        var metrics = result.simulation().metrics();
        assertThat(metrics.servedContacts()).isEqualTo(served);
        assertThat(metrics.usefulSecureTime()).isEqualTo(Duration.ofSeconds(usefulSeconds));
        assertThat(metrics.controlCosts().totalCpu()).isEqualTo(Duration.ofMillis(cpuMillis));
        assertThat(metrics.controlCosts().managementBytes()).isEqualTo(bytes);
        assertThat(metrics.createdKeys()).isEqualTo(keys);
    }
}
