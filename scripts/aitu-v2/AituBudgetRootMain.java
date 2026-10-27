package org.contactaware.security.simulation;

import org.contactaware.contacts.JsonFiles;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;

/** Ordinary practical portfolio at one preserved observation; diagnostic output only. */
public final class AituBudgetRootMain {
    public static void main(String[] args) throws Exception {
        var f=new HashMap<String,String>();
        for(int i=0;i<args.length;i+=2) f.put(args[i].substring(2),args[i+1]);
        var out=Path.of(f.get("out")); Files.createDirectories(out);
        if(Files.exists(out.resolve("root-result.json"))) throw new IllegalArgumentException("Existing attempt");
        var root=ExecutionCheckpointFiles.read(Path.of(f.get("observation")),PlanningObservation.class);
        var snapshot=ExecutionCheckpointFiles.read(Path.of(f.get("checkpoint")));
        if(!root.equals(PlanningObservation.from(snapshot,2)))throw new IllegalArgumentException("Checkpoint/observation mismatch");
        var before=JsonFiles.JSON.writeValueAsString(root);
        var actions=new SecurityActionGenerator().candidates(root);
        JsonFiles.write(out.resolve("observation.json"),root);
        JsonFiles.write(out.resolve("root-actions.json"),actions);
        var rule=PlanTieBreak.valueOf(f.get("rule"));
        var planner=new ExactHorizonPlanner(new SearchLimits(Long.parseLong(f.get("nodes")),Duration.ofSeconds(30)),
                PlannerOptions.acceleratedServiceFirst(),DecisionTimeModel.V2_EVENT_BOUNDARIES,rule);
        var portfolio=new PracticalPlanPortfolio(planner,rule,PracticalPlanPortfolio.Variant.SEED_AND_RETENTION);
        var telemetry=new SearchRuntimeTelemetry.Recorder();
        long started=System.nanoTime();
        IncumbentProbe.clear();
        PracticalPlanPortfolio.Decision decision=null;
        String stop=null;
        try(var scope=SearchControl.open(Duration.ofSeconds(32),SearchDiagnostics.StopReason.DECISION_TIME_LIMIT)) {
            decision=portfolio.select(root,actions);
        } catch(SearchControl.Stopped stopped) {stop=stopped.reason()+":"+stopped.checkpoint();}
        long elapsed=System.nanoTime()-started;
        JsonFiles.write(out.resolve("portfolio.json"),portfolio.audits());
        JsonFiles.write(out.resolve("stopped-work.json"),portfolio.stoppedWork());
        JsonFiles.write(out.resolve("improvements.json"),IncumbentProbe.events());
        var report=new LinkedHashMap<String,Object>();
        report.put("selectionStop",stop);report.put("allowedNodes",Long.parseLong(f.get("nodes")));
        report.put("rule",rule);report.put("at",root.at());report.put("evaluationEnd",root.evaluationEnd());
        report.put("runtime",telemetry.snapshot(elapsed,null));
        report.put("retainedInput","NONE: fresh ordinary portfolio; no offered plan or oracle");
        report.put("certificatePresent",decision!=null && decision.selection().certificate()!=null);
        report.put("replayAccepted",false);
        if(decision!=null) {
            JsonFiles.write(out.resolve("search.json"),decision.search());
            report.put("firstAction",decision.selection().action());report.put("selectionReason",decision.selection().reason());
            if(decision.audit()!=null && decision.audit().selected()!=null) {
                var selected=decision.audit().selected();
                JsonFiles.write(out.resolve("selected.json"),selected);
                var engine=new BranchEngine();var branch=engine.create(root);
                for(var action:selected.plan())branch=engine.execute(branch,action);
                var replay=new ContinuationEvaluator().evaluate(branch);
                boolean accepted=branch.snapshot().at().equals(root.evaluationEnd()) && replay.equals(selected);
                if(!accepted)throw new IllegalStateException("Selected plan replay changed score or full plan");
                report.put("replayAccepted",true);report.put("replayEnd",branch.snapshot().at());
                JsonFiles.write(out.resolve("replay.json"),replay);
            }
        }
        boolean unchanged=before.equals(JsonFiles.JSON.writeValueAsString(root))
                && root.equals(PlanningObservation.from(snapshot,2))
                && actions.equals(new SecurityActionGenerator().candidates(root));
        if(!unchanged)throw new IllegalStateException("Observable root mutated");
        report.put("rootUnchanged",true);
        report.put("status",decision==null?"SELECTION_STOPPED":decision.search().status().name());
        JsonFiles.write(out.resolve("root-result.json"),report);
        System.out.println(report.get("status")+" selected="+report.get("selectionReason")+" replay="+report.get("replayAccepted"));
    }
}
