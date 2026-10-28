package org.contactaware.security.simulation;

import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.*;
import java.util.function.Consumer;
import static org.assertj.core.api.Assertions.*;
import static org.contactaware.security.simulation.ControlledSimulationResult.DecisionSource.SUFFIX_REUSE;
import static org.contactaware.security.simulation.PlanningAction.Kind.*;

class MeasuredContinuationVerificationTest {
    @Test void zeroDelayPreservesOfflineProofLinksAndPhysicalExecution() {
        var initial=ready();
        for(var rule:PlanTieBreak.values()) for(var horizon:List.of(HorizonPolicy.next(),HorizonPolicy.multi(2))) {
            var expected=new SecurityPolicyController(policy(horizon,rule,() -> {}),Duration.ofSeconds(15),(s,n) -> {},true).run(initial);
            var actual=controller(policy(horizon,rule,() -> {}),new AtomicLong(),0,Duration.ofSeconds(2),p -> {}).run(initial);
            assertThat(actual.execution().status()).isEqualTo(ControlledSimulationResult.Status.FINISHED);
            assertThat(actual.execution().snapshot()).isEqualTo(expected.snapshot());
            assertThat(actual.execution().simulation()).isEqualTo(expected.simulation());
            assertThat(actual.execution().decisions()).extracting(ControlledSimulationResult.Decision::executedAction)
                    .containsExactlyElementsOf(expected.decisions().stream().map(ControlledSimulationResult.Decision::executedAction).toList());
            assertThat(actual.execution().computation().suffixReuses()).isPositive().isEqualTo(expected.computation().suffixReuses());
            assertThat(actual.execution().computation().searches()).isEqualTo(expected.computation().searches());
            assertThat(actual.latencyDecisions().stream().map(PlanningLatencyController.Decision::continuation)
                    .filter(a -> a.guard().status()==ContinuationReuseGuard.Status.ALLOWED).map(ControlledSimulationResult.ContinuationAudit::suffixOffset))
                    .containsExactlyElementsOf(expected.decisions().stream().filter(d -> d.source()==SUFFIX_REUSE)
                            .map(d -> d.continuation().suffixOffset()).toList());
        }
    }

    @Test void measuredCycleIncludesGuardAndPreparationAndRejectsShiftedFrameProof() {
        var clock=new AtomicLong();
        var selected=new AtomicInteger();
        var policy=policy(HorizonPolicy.next(),PlanTieBreak.CONTINUE_WAIT,() -> {
            selected.incrementAndGet();clock.addAndGet(50_000_000);
        });
        var actual=controller(policy,clock,1,Duration.ofSeconds(2),phase -> {
            if(phase.equals("latency.reuseGuard"))clock.addAndGet(20_000_000);
            if(phase.equals("latency.reusePreparation"))clock.addAndGet(30_000_000);
        }).run(ready());
        assertThat(actual.execution().status()).isEqualTo(ControlledSimulationResult.Status.FINISHED);
        var first=actual.latencyDecisions().getFirst();
        assertThat(first.selection().search().status()).isEqualTo(HorizonPlanResult.Status.COMPLETE);
        assertThat(first.selection().action().kind()).isEqualTo(CONTINUE);
        assertThat(first.hostNanos()).isEqualTo(100_000_000);
        assertThat(first.modeledLatency()).isEqualTo(Duration.ofMillis(100));
        assertThat(first.continuation().guardNanos()).isEqualTo(20_000_000);
        assertThat(first.continuation().preparationNanos()).isEqualTo(30_000_000);
        assertThat(first.continuation().rootProofReference()).isNotNull();
        var second=actual.latencyDecisions().get(1);
        assertThat(second.origin().at()).isEqualTo(first.selection().action().until().plusMillis(100));
        assertThat(second.continuation().checkedProofReference()).isEqualTo(first.continuation().rootProofReference());
        assertThat(second.continuation().guard().status()).isEqualTo(ContinuationReuseGuard.Status.REJECTED);
        assertThat(second.continuation().guard().reason()).startsWith("FRAME_NOT_COMPLETED:");
        assertThat(actual.execution().decisions()).noneMatch(d -> d.source()==SUFFIX_REUSE);
        assertThat(actual.execution().computation().suffixReuses()).isZero();
        assertThat(selected.get()).isEqualTo(actual.latencyDecisions().size());
        assertThat(actual.execution().computation().selectionNanos()).isEqualTo(clock.get())
                .isEqualTo(actual.latencyDecisions().stream().mapToLong(PlanningLatencyController.Decision::hostNanos).sum());
        assertThat(actual.execution().computation().guardNanos()).isEqualTo(actual.latencyDecisions().stream()
                .mapToLong(d -> d.continuation().guardNanos()).sum());
        assertThat(actual.execution().computation().preparationNanos()).isEqualTo(actual.latencyDecisions().stream()
                .mapToLong(d -> d.continuation().preparationNanos()).sum());
    }

    @Test void deadlineDuringProofPreparationDiscardsProofAndSelectedFrame() {
        var clock=new AtomicLong();
        var actual=controller(policy(HorizonPolicy.next(),PlanTieBreak.CONTINUE_WAIT,() -> {}),clock,0,Duration.ofNanos(10),
                phase -> {if(phase.equals("latency.reusePreparation"))clock.addAndGet(10);}).run(ready());
        assertThat(actual.execution().status()).isEqualTo(ControlledSimulationResult.Status.FINISHED);
        assertThat(actual.latencyDecisions()).singleElement().satisfies(d -> {
            assertThat(d.deadlineMiss()).isTrue();
            assertThat(d.disposition()).isEqualTo("DEADLINE_MISS_WAIT");
            assertThat(d.hostNanos()).isEqualTo(10);
            assertThat(d.continuation().rootProofReference()).isNull();
            assertThat(d.continuation().preparationNanos()).isEqualTo(10);
        });
        assertThat(actual.execution().decisions()).singleElement().satisfies(d -> assertThat(d.executedAction().kind()).isEqualTo(WAIT));
        assertThat(actual.execution().snapshot().journal()).noneMatch(e -> e.type()==SimulationEvent.Type.PAYLOAD_TRANSFERRED);
    }

    @Test void deadlineDuringGuardDiscardsPreviouslyAdoptedProof() {
        var clock=new AtomicLong();var guards=new AtomicInteger();
        var actual=controller(policy(HorizonPolicy.next(),PlanTieBreak.CONTINUE_WAIT,() -> {}),clock,0,Duration.ofNanos(10),
                phase -> {if(phase.equals("latency.reuseGuard") && guards.incrementAndGet()==2)clock.addAndGet(10);}).run(ready());
        assertThat(actual.execution().status()).isEqualTo(ControlledSimulationResult.Status.FINISHED);
        assertThat(actual.latencyDecisions()).hasSize(2);
        var missed=actual.latencyDecisions().getLast();
        assertThat(missed.deadlineMiss()).isTrue();
        assertThat(missed.continuation().checkedProofReference()).isNotNull();
        assertThat(missed.continuation().rootProofReference()).isNull();
        assertThat(missed.continuation().guardNanos()).isEqualTo(10);
        assertThat(actual.execution().computation().suffixReuses()).isZero();
        assertThat(actual.execution().snapshot().journal()).filteredOn(e -> e.type()==SimulationEvent.Type.PAYLOAD_TRANSFERRED).hasSize(1);
    }

    private PlanningLatencyController controller(SecurityPolicy policy,AtomicLong clock,double scale,Duration budget,Consumer<String> hook) {
        return new PlanningLatencyController(policy,new PlanningLatencyController.Model(Duration.ZERO,scale,budget),
                Duration.ofSeconds(15),(s,n) -> {},clock::get,hook);
    }
    private SecurityPolicy policy(HorizonPolicy horizon,PlanTieBreak rule,Runnable onSelection) {
        var planner=new ExactHorizonPlanner(new SearchLimits(50_000,Duration.ofSeconds(5)),PlannerOptions.accelerated(),
                DecisionTimeModel.V2_EVENT_BOUNDARIES,rule);
        return new SecurityPolicy() {
            public PolicyConfiguration configuration(){return horizon;}
            public ContinuationReuseGuard.Rules continuationRules(){return planner.continuationRules();}
            public PolicySelection select(PlanningObservation observation,List<PlanningAction> candidates) {
                onSelection.run();return planner.selectUnrestricted(observation,"small complete proof fixture");
            }
        };
    }
    private ExecutionSnapshot ready() {
        var engine=new SimulationEngine();
        var current=engine.controlledSnapshot(engine.start(new RecedingHorizonControllerTest().keyed(6,List.of())));
        for(var kind:List.of(OPEN,WAIT)) {
            var observation=PlanningObservation.from(current,1);
            var action=new SecurityActionGenerator().candidates(observation).stream().filter(a -> a.kind()==kind)
                    .min(Comparator.comparing(PlanningAction::until)).orElseThrow();
            current=SecurityPolicyController.executeAction(current,observation,action,OperationOutcomes.nominal());
        }
        return current;
    }
}
