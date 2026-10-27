package org.contactaware.security.simulation;

import org.contactaware.contacts.JsonFiles;
import org.junit.jupiter.api.Test;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

/** Componentwise practical-vs-full-DFS quality on finite small full executions, no percentage score. */
class R1SmallQualityTest {
    @Test void recordsEveryObjectiveComponentAgainstCompletedReferenceForTwoNontrivialFixtures() throws Exception {
        var rows=new ArrayList<Map<String,Object>>();
        for(var fixture:List.of(new ContinueSuffixOptimalityTest().fixture(),new ContinueSuffixPrepareOptimalityTest().fixture()))
            for(var rule:PlanTieBreak.values()) {
                // Use the already established suffix witnesses' actual warm-up, common to all caps.
                var initial=new SimulationEngine().controlledSnapshot(new SimulationEngine().start(fixture));
                var kinds=new ArrayList<>(List.of(PlanningAction.Kind.OPEN,PlanningAction.Kind.WAIT));
                if(fixture.name().equals(new ContinueSuffixOptimalityTest().fixture().name())) kinds.add(PlanningAction.Kind.CONTINUE);
                for(var kind:kinds) {
                    var observation=PlanningObservation.from(initial,2);
                    var action=new SecurityActionGenerator().candidates(observation).stream().filter(a->a.kind()==kind)
                            .min(Comparator.comparing(PlanningAction::until)).orElseThrow();
                    initial=SecurityPolicyController.executeAction(initial,observation,action,OperationOutcomes.nominal());
                }
                var exact=new ExactHorizonPlanner(new SearchLimits(250000,Duration.ofSeconds(30)),PlannerOptions.reference(),DecisionTimeModel.V2_EVENT_BOUNDARIES,rule);
                var oracle=new RecedingHorizonController(HorizonPolicy.multi(2),exact).run(initial);
                if(oracle.status()!=ControlledSimulationResult.Status.FINISHED) {
                    rows.add(Map.of("fixture",fixture.name(),"rule",rule,"referenceStatus",oracle.status(),"referenceDecisions",oracle.decisions()));
                    JsonFiles.write(Path.of("target/r1-small-quality.json"),rows);
                    continue; // Bounded oracle failure is evidence, never a fabricated quality score.
                }
                assertThat(oracle.decisions()).filteredOn(d->d.search()!=null).allMatch(d->d.search().status()==HorizonPlanResult.Status.COMPLETE);
                var truth=oracle.simulation().metrics();
                for(long cap:List.of(1L,50L,5000L)) {
                    var policy=new BudgetedHorizonPolicy(HorizonPolicy.multi(2),new ExactHorizonPlanner(new SearchLimits(cap,Duration.ofSeconds(30)),
                            PlannerOptions.acceleratedServiceFirst(),DecisionTimeModel.V2_EVENT_BOUNDARIES,rule),rule);
                    var result=new SecurityPolicyController(policy,Duration.ofSeconds(30),(s,n)->{},true).run(initial);
                    assertThat(result.status()).isEqualTo(ControlledSimulationResult.Status.FINISHED);
                    var got=result.simulation().metrics();
                    var delta=new LinkedHashMap<String,Object>();
                    delta.put("servedContacts",got.servedContacts()-truth.servedContacts());
                    delta.put("usefulTimeNanos",got.usefulSecureTime().minus(truth.usefulSecureTime()).toNanos());
                    delta.put("cpuNanos",got.controlCosts().totalCpu().minus(truth.controlCosts().totalCpu()).toNanos());
                    delta.put("managementBytes",got.controlCosts().managementBytes()-truth.controlCosts().managementBytes());
                    delta.put("newKeys",got.createdKeys()-truth.createdKeys());
                    rows.add(Map.of("fixture",fixture.name(),"rule",rule,"nodeLimit",cap,"reference",truth,"practical",got,
                            "deltaPracticalMinusReference",delta,"searches",policy.attempts()));
                }
            }
        JsonFiles.write(Path.of("target/r1-small-quality.json"),rows);
    }
}
