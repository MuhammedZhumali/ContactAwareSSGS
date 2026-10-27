package org.contactaware.security.simulation;

import org.contactaware.contacts.JsonFiles;
import java.nio.file.*;
import java.time.*;
import java.util.*;

/** Registered root ablation; replay saved actions, never search the preceding trajectory. */
public final class Pc2FixtureProbe {
    public static void main(String[] args) throws Exception {
        Path fixtures=Path.of(args[0]),output=Path.of(args[1]);Files.createDirectories(output);
        var rows=new ArrayList<Object>();
        for(var id:List.of("post-prepare-known-retained-winner","iss46-earliest-skipped-contact","demand-none-disadvantageous-wait")) {
            Path folder=fixtures.resolve(id),dest=output.resolve(id);Files.createDirectories(dest);
            var data=JsonFiles.JSON.readTree(folder.resolve("actual-snapshot-reconstruction.json").toFile());
            var scenario=ExecutionCheckpointFiles.convert(data.required("scenario"),SimulationScenario.class);
            var expected=ExecutionCheckpointFiles.convert(data.required("expectedObservation"),PlanningObservation.class);
            var actual=new SimulationEngine().controlledSnapshot(new SimulationEngine().start(scenario));
            for(var step:data.required("prefix")) {
                var observed=ExecutionCheckpointFiles.convert(step.required("observation"),PlanningObservation.class);
                if(!PlanningObservation.from(actual,observed.horizon()).equals(observed))throw new AssertionError("Prefix observation mismatch "+id+" "+actual.at());
                var action=step.required("executedAction").isNull()?null:ExecutionCheckpointFiles.convert(step.required("executedAction"),PlanningAction.class);
                actual=SecurityPolicyController.executeAction(actual,observed,action,OperationOutcomes.nominal());
                if(!actual.at().equals(Instant.parse(step.required("resumedAt").asText())))throw new AssertionError("Prefix execution mismatch");
            }
            if(!PlanningObservation.from(actual,expected.horizon()).equals(expected))throw new AssertionError("Actual root mismatch "+id);
            ExecutionCheckpointFiles.write(dest.resolve("actual-root.checkpoint.json"),actual);
            var rule=PlanTieBreak.valueOf(JsonFiles.JSON.readTree(folder.resolve("fixture.json").toFile()).required("tieBreak").asText());
            var saved=ExecutionCheckpointFiles.convert(JsonFiles.JSON.readTree(folder.resolve("saved-search.json").toFile()).required("search"),HorizonPlanResult.class);
            var baseline=replay(expected,saved.bestPlan());
            if(!baseline.evaluation().equals(saved.score()))throw new AssertionError("Saved score not reproduced "+id);
            var alternatives=new ArrayList<PlanningAction>();
            if(Files.exists(folder.resolve("alternative-plan.json")))
                for(var a:JsonFiles.JSON.readTree(folder.resolve("alternative-plan.json").toFile()))alternatives.add(ExecutionCheckpointFiles.convert(a,PlanningAction.class));
            var row=new LinkedHashMap<String,Object>();row.put("fixture",id);row.put("root",expected);row.put("pc1",baseline);
            // Same saved search candidate in every arm: isolate seed/retention from host traversal noise.
            for(var variant:List.of(PracticalPlanPortfolio.Variant.SEED_ONLY,PracticalPlanPortfolio.Variant.SEED_AND_RETENTION)) {
                var portfolio=new PracticalPlanPortfolio(o->saved,rule,variant);
                if(!alternatives.isEmpty())portfolio.offerForReplay(expected,alternatives);
                try(var budget=SearchControl.open(Duration.ofSeconds(2),SearchDiagnostics.StopReason.DECISION_TIME_LIMIT)) {
                    var decision=portfolio.select(expected,new SecurityActionGenerator().candidates(expected));
                    row.put(variant.name(),decision.audit());
                    var selected=decision.audit().selected();
                    if(selected!=null && !replay(expected,selected.plan()).equals(selected))throw new AssertionError("Selected score replay mismatch");
                    if(selected!=null && new PlanningResultComparator(rule).compare(selected,baseline)>0)throw new AssertionError("Lost baseline incumbent");
                }
            }
            row.put("actualUnchanged",PlanningObservation.from(actual,expected.horizon()).equals(expected));
            JsonFiles.write(dest.resolve("result.json"),row);rows.add(row);
        }
        JsonFiles.write(output.resolve("results.json"),rows);
        System.out.println("PASS three actual root reconstructions and PC1 -> seed -> seed+retention replay ablations");
    }
    private static PlanningResult replay(PlanningObservation root,List<PlanningAction> plan) {
        var engine=new BranchEngine();var branch=engine.create(root);
        for(var action:plan)branch=engine.execute(branch,action);
        if(!branch.snapshot().at().equals(root.evaluationEnd()))throw new AssertionError("Incomplete continuation");
        return new ContinuationEvaluator().evaluate(branch);
    }
}
