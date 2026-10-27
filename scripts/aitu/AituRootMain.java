package org.contactaware.security.simulation;

import org.contactaware.contacts.JsonFiles;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;

/** Paired root diagnostics. Never passes an oracle continuation to an ordinary policy run. */
public final class AituRootMain {
    public static void main(String[] args) throws Exception {
        var f = new HashMap<String,String>();
        for (int i=0;i<args.length;i+=2) f.put(args[i].substring(2),args[i+1]);
        var out=Path.of(f.get("out")); Files.createDirectories(out);
        if(Files.exists(out.resolve("root-result.json"))) throw new IllegalArgumentException("Existing attempt");
        var model=JsonFiles.JSON.readTree(Path.of(f.get("model")).toFile());
        var scenario=ExecutionCheckpointFiles.convert(model.required("scenario"),SimulationScenario.class);
        var snapshot=AituInitialState.create(scenario,f.get("initialization"));
        int h=Integer.parseInt(f.get("horizon"));
        var root=PlanningObservation.from(snapshot,h);
        var before=JsonFiles.JSON.writeValueAsString(snapshot);
        var actions=new SecurityActionGenerator().candidates(root);
        ExecutionCheckpointFiles.write(out.resolve("initial-checkpoint.json"),snapshot);
        JsonFiles.write(out.resolve("observation.json"),root);
        JsonFiles.write(out.resolve("root-actions.json"),actions);
        var rule=PlanTieBreak.valueOf(f.get("rule"));
        var exact=new ExactHorizonPlanner(new SearchLimits(Long.parseLong(f.getOrDefault("exact-nodes","2000000")),
                Duration.ofMillis(Long.parseLong(f.getOrDefault("exact-ms","30000")))),
                PlannerOptions.acceleratedServiceFirst(),DecisionTimeModel.V2_EVENT_BOUNDARIES,rule);
        IncumbentProbe.clear();
        var winner=exact.plan(root);
        JsonFiles.write(out.resolve("exact-result.json"),winner);
        JsonFiles.write(out.resolve("exact-improvements.json"),IncumbentProbe.events());
        if (f.getOrDefault("equivalence-only","false").equals("true")) {
            JsonFiles.write(out.resolve("root-result.json"),Map.of("status",winner.status(),"snapshotUnchanged",
                    before.equals(JsonFiles.JSON.writeValueAsString(snapshot))));
            return;
        }
        var practicalPlanner=new ExactHorizonPlanner(new SearchLimits(5000,Duration.ofSeconds(1)),
                PlannerOptions.acceleratedServiceFirst(),DecisionTimeModel.V2_EVENT_BOUNDARIES,rule);
        var portfolio=new PracticalPlanPortfolio(practicalPlanner,rule,PracticalPlanPortfolio.Variant.SEED_AND_RETENTION);
        IncumbentProbe.clear();
        PracticalPlanPortfolio.Decision practical=null;
        String practicalStop=null;
        try(var scope=SearchControl.open(Duration.ofSeconds(2),SearchDiagnostics.StopReason.DECISION_TIME_LIMIT)) {
            practical=portfolio.select(root,actions);
        } catch(SearchControl.Stopped stop) { practicalStop=stop.reason()+":"+stop.checkpoint(); }
        JsonFiles.write(out.resolve("practical-audit.json"),portfolio.audits());
        JsonFiles.write(out.resolve("practical-stops.json"),portfolio.stoppedWork());
        JsonFiles.write(out.resolve("practical-improvements.json"),IncumbentProbe.events());
        if(practical!=null && practical.search()!=null) JsonFiles.write(out.resolve("practical-search.json"),practical.search());
        var report=new LinkedHashMap<String,Object>();
        report.put("exactStatus",winner.status());report.put("practicalStop",practicalStop);
        report.put("sameSnapshotAndObservation",true);
        report.put("ordinaryPracticalRetainedCandidate","NONE: fresh paired root");
        report.put("rule",rule);report.put("evaluationEnd",root.evaluationEnd());
        if(winner.status()==HorizonPlanResult.Status.COMPLETE) {
            var engine=new BranchEngine();var branch=engine.create(root);
            for(var action:winner.bestPlan()) branch=engine.execute(branch,action);
            var evaluated=new ContinuationEvaluator().evaluate(branch);
            boolean agrees=branch.snapshot().at().equals(root.evaluationEnd()) && evaluated.evaluation().equals(winner.score())
                    && evaluated.plan().equals(winner.bestPlan());
            if(!agrees) throw new IllegalStateException("Exact terminal replay mismatch");
            report.put("exactReplay",evaluated);report.put("exactReplayAccepted",true);
            var comparator=new PlanningResultComparator(rule);
            report.put("exactVsPracticalOrder",practical==null || practical.audit()==null || practical.audit().selected()==null
                    ? null : comparator.compare(evaluated,practical.audit().selected()));
            // Separate diagnostic portfolio, never the object used by a controller.
            var diagnostic=new PracticalPlanPortfolio(practicalPlanner,rule,PracticalPlanPortfolio.Variant.SEED_AND_RETENTION);
            diagnostic.offerForReplay(root,winner.bestPlan());
            try(var scope=SearchControl.open(Duration.ofSeconds(2),SearchDiagnostics.StopReason.DECISION_TIME_LIMIT)) {
                diagnostic.select(root,actions);
                report.put("diagnosticReplayDisposition","RETURNED; inspect audit candidate list and retention reason");
            }catch(SearchControl.Stopped stop){report.put("diagnosticReplayDisposition",stop.reason()+":"+stop.checkpoint());}
            JsonFiles.write(out.resolve("DIAGNOSTIC-ONLY-oracle-portfolio.json"),diagnostic.audits());
            JsonFiles.write(out.resolve("DIAGNOSTIC-ONLY-oracle-stops.json"),diagnostic.stoppedWork());
        } else report.put("exactReplayAccepted",null);
        boolean unchanged=before.equals(JsonFiles.JSON.writeValueAsString(snapshot))
                && root.equals(PlanningObservation.from(snapshot,h))
                && actions.equals(new SecurityActionGenerator().candidates(root));
        if(!unchanged)throw new IllegalStateException("Root mutated");
        report.put("snapshotObservationActionsUnchanged",unchanged);
        JsonFiles.write(out.resolve("root-result.json"),report);
        System.out.println("Root exact="+winner.status()+" nodes="+winner.exploredNodes()+" practical="+practicalStop);
    }
}
