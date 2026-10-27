package org.contactaware.security.simulation;

import org.contactaware.contacts.JsonFiles;
import java.nio.file.*;
import java.util.*;

/** Focused validation of the no-DFS treatment; no full campaign execution. */
public final class AituSeedRetentionCheck {
    public static void main(String[] args) throws Exception {
        var source=ExecutionCheckpointFiles.convert(JsonFiles.JSON.readTree(Path.of(args[0]).toFile()).required("scenario"),SimulationScenario.class);
        var root=PlanningObservation.from(AituInitialState.create(source,"prepared"),1);
        HorizonPlanner forbidden=o->{throw new AssertionError("DFS invoked by no-DFS treatment");};
        int[] calls={0};
        HorizonPlanner empty=o->{
            calls[0]++;
            var s=new SearchDiagnostics.Counter();s.stopReason=SearchDiagnostics.StopReason.NODE_LIMIT;
            return new HorizonPlanResult(List.of(),null,null,HorizonPlanResult.Status.INCOMPLETE,0,0,s.snapshot());
        };
        var off=new AituSeedRetentionPortfolio(forbidden,PlanTieBreak.CANONICAL,AituSeedRetentionPortfolio.Variant.SEED_AND_RETENTION_WITHOUT_SEARCH);
        var original=new PracticalPlanPortfolio(empty,PlanTieBreak.CANONICAL,PracticalPlanPortfolio.Variant.SEED_AND_RETENTION);
        var copied=new AituSeedRetentionPortfolio(empty,PlanTieBreak.CANONICAL,AituSeedRetentionPortfolio.Variant.SEED_AND_RETENTION);
        var generator=new SecurityActionGenerator();var engine=new BranchEngine();
        var evidence=new ArrayList<Map<String,Object>>();
        for(int i=0;i<2;i++) {
            var actions=generator.candidates(root);
            var a=off.select(root,actions);var b=original.select(root,actions);var c=copied.select(root,actions);
            if(a.search()!=null)throw new AssertionError("Synthetic search reported in no-DFS mode");
            if(a.audit()==null || b.audit()==null || c.audit()==null)throw new AssertionError("No terminal audit");
            if(!a.audit().selected().equals(b.audit().selected()) || !b.audit().selected().equals(c.audit().selected()))
                throw new AssertionError("Full winner or score changed");
            if(!a.selection().action().equals(b.selection().action()) || !b.selection().action().equals(c.selection().action()))
                throw new AssertionError("First action changed");
            if(i==1 && !a.audit().retention().equals("FEASIBLE_RETAINED"))throw new AssertionError(a.audit().retention());
            evidence.add(Map.of("rootIndex",i,"retention",a.audit().retention(),"winner",a.audit().selected(),
                    "noDfsSearchTransitions",a.audit().searchTransitions(),"seedTransitions",a.audit().seedTransitions(),
                    "suffixTransitions",a.audit().suffixTransitions()));
            root=engine.execute(engine.create(root),a.selection().action()).observation();
        }
        if(calls[0]!=4)throw new AssertionError("Unexpected original/copied planner call count");
        JsonFiles.write(Path.of(args[1]),Map.of("status","PASS","originalAndCopiedPlannerCalls",calls[0],"disabledPlannerCalls",0,
                "roots",evidence,"frozenPortfolioClass",PracticalPlanPortfolio.class.getProtectionDomain().getCodeSource().getLocation().toString(),
                "newPortfolioClass",AituSeedRetentionPortfolio.class.getProtectionDomain().getCodeSource().getLocation().toString()));
        System.out.println("PASS: two roots; full winner equality; retained continuation; no DFS invoked");
    }
}
