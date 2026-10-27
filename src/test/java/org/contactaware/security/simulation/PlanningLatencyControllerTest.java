package org.contactaware.security.simulation;

import org.junit.jupiter.api.Test;
import java.time.Duration;
import static org.assertj.core.api.Assertions.*;

class PlanningLatencyControllerTest {
    @Test void modeledDelayIsChargedAtDecisionsAndMissesWaitWithoutUsingExpiredKeys() {
        var scenario=new ContinueSuffixPrepareOptimalityTest().fixture();
        var policy=new FixedPeriodPolicy(Duration.ofSeconds(100));
        var latency=new PlanningLatencyController(policy,new PlanningLatencyController.Model(Duration.ofMillis(500),0,Duration.ofMillis(100)),
                Duration.ofSeconds(5),(s,n)->{});
        var result=latency.run(new SimulationEngine().start(scenario));
        assertThat(result.execution().status()).isEqualTo(ControlledSimulationResult.Status.FINISHED);
        assertThat(result.latencyDecisions()).isNotEmpty().allMatch(d->d.deadlineMiss());
        assertThat(result.execution().snapshot().journal()).noneMatch(e->e.type()==SimulationEvent.Type.PAYLOAD_TRANSFERRED);
        assertThat(result.latencyDecisions().getFirst().availableAt()).isEqualTo(scenario.start().plusMillis(500));
        assertThat(result.execution().snapshot().journal()).anyMatch(e->e.type()==SimulationEvent.Type.KEY_EXPIRED);
    }
    @Test void zeroDelayPreservesRulePolicyPhysicalResult() {
        var scenario=new ContinueSuffixPrepareOptimalityTest().fixture();
        var policy=new PerContactPolicy(PerContactPolicy.AdvancePreparation.JUST_IN_TIME);
        var initial=new SimulationEngine().start(scenario);
        var expected=new SecurityPolicyController(policy).run(initial);
        var actual=new PlanningLatencyController(policy,new PlanningLatencyController.Model(Duration.ZERO,0,Duration.ZERO),
                Duration.ofSeconds(5),(s,n)->{}).run(initial).execution();
        assertThat(actual.status()).isEqualTo(expected.status());
        assertThat(actual.simulation().metrics()).isEqualTo(expected.simulation().metrics());
        assertThat(actual.snapshot().securityState()).isEqualTo(expected.snapshot().securityState());
    }
}
