package org.contactaware.security.simulation;

import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import static org.assertj.core.api.Assertions.*;

class MeasuredPlanningLatencyTest {
    @Test void hostDelayIncludesInitialGenerationAndSelectionAtTheCriticalPoint() {
        var clock=new AtomicLong();
        var scenario=new ContinueSuffixPrepareOptimalityTest().fixture();
        var controller=new PlanningLatencyController(policy(clock),
                new PlanningLatencyController.Model(Duration.ZERO,1,Duration.ofMillis(250)),
                Duration.ofSeconds(5),(s,n)->{},clock::get);
        PlanningLatencyController.Result result;
        try(var scope=SearchControl.open(Duration.ZERO,SearchDiagnostics.StopReason.CONTROLLER_TIME_LIMIT,
                clock::get,phase->{if(phase.equals("generation.candidates"))clock.addAndGet(100_000_000);})) {
            result=controller.run(new SimulationEngine().start(scenario));
        }
        var first=result.latencyDecisions().getFirst();
        assertThat(first.hostNanos()).isEqualTo(200_000_000);
        assertThat(first.modeledLatency()).isEqualTo(Duration.ofMillis(200));
        assertThat(first.availableAt()).isEqualTo(first.origin().at().plusMillis(200));
        assertThat(first.deadlineMiss()).isFalse();
        assertThat(result.execution().status()).isEqualTo(ControlledSimulationResult.Status.FINISHED);
        assertThat(result.execution().computation().selectionNanos())
                .isEqualTo(result.latencyDecisions().stream().mapToLong(PlanningLatencyController.Decision::hostNanos).sum());
        assertThat(result.execution().snapshot().journal()).noneMatch(e->e.type()==SimulationEvent.Type.ACTION_FAILED);
    }

    @Test void measuredLateDecisionAdvancesEventsButCannotExecuteItsProposedOpen() {
        var clock=new AtomicLong();var scenario=new ContinueSuffixPrepareOptimalityTest().fixture();
        var result=new PlanningLatencyController(policy(clock),
                new PlanningLatencyController.Model(Duration.ZERO,1,Duration.ofMillis(50)),
                Duration.ofSeconds(5),(s,n)->{},clock::get).run(new SimulationEngine().start(scenario));
        assertThat(result.execution().status()).isEqualTo(ControlledSimulationResult.Status.FINISHED);
        assertThat(result.latencyDecisions()).allMatch(PlanningLatencyController.Decision::deadlineMiss);
        assertThat(result.execution().snapshot().journal()).noneMatch(e->e.type()==SimulationEvent.Type.PAYLOAD_TRANSFERRED);
        assertThat(result.execution().snapshot().journal()).anyMatch(e->e.type()==SimulationEvent.Type.KEY_EXPIRED);
        assertThat(result.execution().computation().selectionNanos()).isPositive();
    }

    @Test void outerControllerStopKeepsItsReasonAndCannotPublishFinalQuality() {
        var scenario=new ContinueSuffixPrepareOptimalityTest().fixture();
        var counter=new SearchDiagnostics.Counter();counter.stopReason=SearchDiagnostics.StopReason.CONTROLLER_TIME_LIMIT;
        SecurityPolicy policy=new SecurityPolicy() {
            public PolicyConfiguration configuration(){return HorizonPolicy.next();}
            public PolicySelection select(PlanningObservation o,List<PlanningAction> c){return new PolicySelection(null,
                    new HorizonPlanResult(List.of(),null,null,HorizonPlanResult.Status.INCOMPLETE,0,0,counter.snapshot()),"global stop");}
        };
        var result=new PlanningLatencyController(policy,
                new PlanningLatencyController.Model(Duration.ZERO,1,Duration.ofSeconds(2)),Duration.ZERO,(s,n)->{})
                .run(new SimulationEngine().start(scenario));
        assertThat(result.execution().status()).isEqualTo(ControlledSimulationResult.Status.CONTROLLER_TIME_LIMIT);
        assertThat(result.execution().simulation()).isNull();
        assertThat(result.execution().computation().searches()).isEqualTo(1);
    }

    private SecurityPolicy policy(AtomicLong clock) {
        return new SecurityPolicy() {
            public PolicyConfiguration configuration(){return HorizonPolicy.next();}
            public PolicySelection select(PlanningObservation o,List<PlanningAction> candidates) {
                clock.addAndGet(100_000_000);
                return PolicySelection.rule(BudgetedHorizonPolicy.fallback(o,candidates,PlanTieBreak.CANONICAL),"test host delay");
            }
        };
    }
}
