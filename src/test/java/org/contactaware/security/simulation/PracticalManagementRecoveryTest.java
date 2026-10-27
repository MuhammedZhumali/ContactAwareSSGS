package org.contactaware.security.simulation;

import org.contactaware.security.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import java.time.Duration;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.contactaware.security.simulation.HorizonWitness.*;
import static org.contactaware.security.simulation.SimulationEvent.Type.*;

class PracticalManagementRecoveryTest {
    @Test void bothHorizonsRetryUnconfirmedInstallAndActuallyDeliverAfterRestoration() {
        var transitions=new LifecycleTransitions(CONSTRAINTS);
        var state=transitions.createContext(SecurityState.empty(SATELLITE,at(-3)),A,"up","down",at(-3));
        state=transitions.prepareKeys(state,A,NEW_A,at(-2));
        var scenario=scenario(state,new ChannelInterruption("C1",at(2),at(3)));
        for(int h:List.of(1,2)) {
            var engine=new SimulationEngine();
            var initial=engine.controlledSnapshot(engine.start(scenario));
            initial=engine.controlledStart(initial,operation(SecurityAction.INSTALL,NEW_A));
            var result=controller(h).run(initial);
            assertThat(result.status()).isEqualTo(ControlledSimulationResult.Status.FINISHED);
            assertThat(result.simulation().metrics().servedContacts()).isEqualTo(1);
            assertThat(result.snapshot().uncertainContexts()).isEmpty();
            var events=result.snapshot().journal();
            assertThat(events).filteredOn(e->e.type()==STEP_INTERRUPTED && e.action()==SecurityAction.INSTALL).singleElement()
                    .satisfies(e->assertThat(e.usage()).isEqualTo(scenario.costs().cost(SecurityAction.INSTALL).consumedUntil(Duration.ofSeconds(2))));
            assertThat(events).filteredOn(e->e.type()==STEP_STARTED && e.action()==SecurityAction.INSTALL).hasSize(2);
            assertThat(events).anyMatch(e->e.type()==STEP_COMPLETED && e.action()==SecurityAction.INSTALL && e.at().equals(at(7)));
            assertThat(events).anyMatch(e->e.type()==PAYLOAD_TRANSFERRED && e.at().isAfter(at(9)));
            assertThat(events).filteredOn(e->e.type()==ACTION_FAILED).singleElement().satisfies(e->{
                assertThat(e.operationId()).isEqualTo("committed-prefix");
                assertThat(e.failureCode()).isEqualTo("CHANNEL_DOWN");
            });
            assertThat(events).noneMatch(e->e.type()==PAYLOAD_REJECTED);
        }
    }

    @ParameterizedTest @EnumSource(value=SecurityAction.class,names={"SWITCH","OPEN","REUSE"})
    void uncertaintyAfterInterruptedOnlineActionDoesNotBecomeFictitiousRecovery(SecurityAction action) {
        var state=initialState();
        if(action==SecurityAction.SWITCH) {
            var transitions=new LifecycleTransitions(CONSTRAINTS);
            state=transitions.prepareKeys(state,A,NEW_A,at(-2));
            state=transitions.confirmInstallation(state,A,NEW_A,window("provision",A,-2,0),at(-1));
        }
        var scenario=scenario(state,new ChannelInterruption("C1",at(0).plusMillis(500),at(2)));
        for(int h:List.of(1,2)) {
            var engine=new SimulationEngine();var initial=engine.controlledSnapshot(engine.start(scenario));
            initial=engine.controlledStart(initial,operation(action,action==SecurityAction.SWITCH?NEW_A:null));
            var result=controller(h).run(initial);
            assertThat(result.status()).isEqualTo(ControlledSimulationResult.Status.FINISHED);
            assertThat(result.simulation().metrics().servedContacts()).isZero();
            assertThat(result.snapshot().uncertainContexts()).contains(A);
            assertThat(result.snapshot().journal()).anyMatch(e->e.type()==CHANNEL_UP);
            assertThat(result.snapshot().journal()).anyMatch(e->e.type()==STEP_INTERRUPTED && e.action()==action);
            assertThat(result.snapshot().journal()).noneMatch(e->e.type()==PAYLOAD_TRANSFERRED);
            assertThat(result.snapshot().journal()).noneMatch(e->e.type()==STEP_STARTED && e.at().isAfter(at(0))
                    && e.action()!=null && e.action().requiresContact());
        }
    }

    private SecurityPolicyController controller(int h) {
        var policy=new BudgetedHorizonPolicy(h==1?HorizonPolicy.next():HorizonPolicy.multi(h),
                new ExactHorizonPlanner(new SearchLimits(1,Duration.ofSeconds(1))),PlanTieBreak.CANONICAL);
        return new SecurityPolicyController(policy,Duration.ZERO,(s,n)->{},true,Duration.ofSeconds(2));
    }
    private SimulationScenario scenario(SecurityState initial,ChannelInterruption interruption) {
        return new SimulationScenario("practical management recovery",at(0),at(20),initial,CONSTRAINTS,
                SecurityCostProfile.syntheticWitness(),List.of(new SimulationContact(window("C1",A,0,20),
                SimulationContact.PayloadProfile.syntheticWitness())),List.of(),List.of(),List.of(interruption));
    }
    private PlannedOperation operation(SecurityAction action,KeyPairIds pair) {
        return new PlannedOperation("committed-prefix",new ScheduledSecurityAction(action,at(0),A,pair,"Test handoff"),"C1");
    }
}
