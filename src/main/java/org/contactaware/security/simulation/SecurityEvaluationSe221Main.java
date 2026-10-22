package org.contactaware.security.simulation;

import org.contactaware.contacts.JsonFiles;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.io.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;

/** SE2.2.1 finite repetitions. This harness never modifies earlier experiment files. */
public final class SecurityEvaluationSe221Main {
    private static final Path SOURCE=Path.of("target/security-evaluation-se2");
    private static final SearchLimits LIMITS=new SearchLimits(2_000_000,Duration.ofSeconds(30));
    private SecurityEvaluationSe221Main() { }
    public static void main(String[] args) throws Exception {
        var flags=new HashMap<String,String>();
        for(int i=0;i<args.length;i+=2) {
            if(i+1==args.length || !args[i].startsWith("--") || flags.put(args[i].substring(2),args[i+1])!=null)
                throw new IllegalArgumentException("Unique --flag value pairs required");
        }
        Path out=Path.of(Objects.requireNonNull(flags.get("out")));
        if(Files.exists(out.resolve("result.json")) || Files.exists(out.resolve("checkpoint.json")))
            throw new IllegalArgumentException("Refusing existing attempt");
        Files.createDirectories(out);
        var order=PlannerOptions.TraversalOrder.valueOf(flags.get("traversal"));
        var rule=PlanTieBreak.valueOf(flags.getOrDefault("rule","CONTINUE_WAIT"));
        var old=PlannerOptions.accelerated();
        var options=new PlannerOptions(old.collapseEquivalentWaits(),old.memoize(),order,old.maxMemoEntries(),
                old.optimisticBounds(),old.preExecutionWaitReduction(),old.canonicalBoundPruning());
        var planner=new ExactHorizonPlanner(LIMITS,options,DecisionTimeModel.V2_EVENT_BOUNDARIES,rule);
        if("search".equals(flags.get("mode"))) {
            var snapshot=ExecutionCheckpointFiles.read(Path.of(flags.get("checkpoint")));
            var observation=PlanningObservation.from(snapshot,2);
            JsonFiles.write(out.resolve("observation.json"),observation);
            var result=planner.plan(observation);
            JsonFiles.write(out.resolve("result.json"),result);
            if(result.score()!=null) {
                var engine=new BranchEngine(); var branch=engine.create(observation);
                for(var action:result.bestPlan()) branch=engine.execute(branch,action);
                var score=new ContinuationEvaluator().evaluate(branch).evaluation();
                if(!branch.snapshot().at().equals(observation.evaluationEnd()) || !score.equals(result.score()))
                    throw new IllegalStateException("Representative plan replay differs");
                JsonFiles.write(out.resolve("plan-replay.json"),Map.of("status","PASS","score",score,
                    "at",branch.snapshot().at(),"searchStatus",result.status(),"doesNotProveIncompleteOptimality",true));
            }
            System.out.println(result.status()+" nodes="+result.exploredNodes()+" elapsed="+result.elapsedNanos());
        } else if("controller".equals(flags.get("mode"))) {
            try { controller(flags.get("case"),rule,options,planner,out); }
            catch(UncheckedIOException error) {
                var failure=new LinkedHashMap<String,Object>();
                failure.put("status",error.getCause() instanceof ExecutionCheckpointFiles.WriteFailure ? "CHECKPOINT_WRITE_FAILED" : "IO_FAILED");
                failure.put("exception",error.toString());
                if(error.getCause() instanceof ExecutionCheckpointFiles.WriteFailure e) failure.put("writeDiagnostics",e.diagnostics());
                try { JsonFiles.write(out.resolve("failure.json"),failure); }
                catch(IOException secondary) { error.addSuppressed(secondary); }
                throw error;
            }
        } else throw new IllegalArgumentException("Unknown mode");
    }

    private static void save(Path out,ExecutionSnapshot snapshot,int count) {
        Object diagnostic;
        try { diagnostic=ExecutionCheckpointFiles.writeWithDiagnostics(out.resolve("checkpoint.json"),snapshot); }
        catch(ExecutionCheckpointFiles.WriteFailure failure) {
            trace(out,Map.of("at",snapshot.at(),"completedDecisions",count,"diagnostics",failure.diagnostics()));
            throw new UncheckedIOException(failure);
        } catch(IOException failure) { throw new UncheckedIOException(failure); }
        trace(out,Map.of("at",snapshot.at(),"completedDecisions",count,"diagnostics",diagnostic));
    }

    private static void trace(Path out,Object row) {
        try {
            String line=JsonFiles.JSON.writer().without(SerializationFeature.INDENT_OUTPUT).writeValueAsString(row);
            System.out.println("CHECKPOINT_WRITE "+line);
            Files.writeString(out.resolve("checkpoint-writes.jsonl"),line+System.lineSeparator(),
                    StandardOpenOption.CREATE,StandardOpenOption.APPEND);
        } catch(IOException e) { throw new UncheckedIOException(e); }
    }

    private static void controller(String sourceCase,PlanTieBreak rule,PlannerOptions options,ExactHorizonPlanner planner,Path out) throws Exception {
        var spec=JsonFiles.JSON.readTree(SOURCE.resolve("summary.json").toFile()).required(sourceCase);
        var model=JsonFiles.JSON.readTree(SOURCE.resolve(spec.required("modelFile").asText()).toFile());
        var scenario=ExecutionCheckpointFiles.convert(model.required("scenario"),SimulationScenario.class);
        var config=switch(spec.required("policy").asText()) {
            case "next" -> HorizonPolicy.next(); case "multi" -> HorizonPolicy.multi(2);
            default -> throw new IllegalArgumentException("Only Next/Multi");
        };
        if(!"V2_EVENT_BOUNDARIES".equals(spec.required("timingModel").asText())) throw new IllegalArgumentException("Frozen timing required");
        SecurityPolicy policy=new SecurityPolicy() {
            public PolicyConfiguration configuration() { return config; }
            public PolicySelection select(PlanningObservation o,List<PlanningAction> candidates) {
                var result=planner.plan(o);
                return new PolicySelection(result.status()==HorizonPlanResult.Status.COMPLETE?result.firstAction():null,
                        result,"SE2.2.1 tie="+rule+" traversal="+options.traversalOrder());
            }
        };
        var initial=new SimulationEngine().start(scenario);
        var controller=new SecurityPolicyController(policy,Duration.ofSeconds(60),(snapshot,count)->save(out,snapshot,count));
        var measurement=new SearchRuntimeTelemetry.Recorder();
        long started=System.nanoTime(); var result=controller.run(initial); long elapsed=System.nanoTime()-started;
        var runtime=measurement.snapshot(elapsed,null);
        save(out,result.snapshot(),result.decisions().size());
        var fixture=new PolicyExperimentFixtures.Fixture(sourceCase,"AUTONOMOUS","SE2.2.1 unchanged SE2 input",
                Map.of("sourceCase",sourceCase,"tieBreak",rule.name(),"traversal",options.traversalOrder().name()),scenario,initial);
        var exported=FourPolicyExperimentMain.exportCase(out,"actual",fixture,result,elapsed,false);
        var keys=new ArrayList<SecurityEvaluationSe22Main.KeyDecision>();
        for(int i=0;i<result.decisions().size();i++) {
            var d=result.decisions().get(i);
            if(d.executedAction()!=null && (d.executedAction().kind()==PlanningAction.Kind.PREPARE || d.executedAction().kind()==PlanningAction.Kind.INSTALL))
                keys.add(new SecurityEvaluationSe22Main.KeyDecision(i,d.observation(),d.executedAction(),d.search(),d.reason()));
        }
        JsonFiles.write(out.resolve("key-decisions.json"),keys);
        var data=new LinkedHashMap<String,Object>();
        data.put("status",result.status()); data.put("sourceCase",sourceCase); data.put("tieBreak",rule);
        data.put("options",options); data.put("summary",exported); data.put("measurement",runtime);
        data.put("simulationMetrics",result.simulation()==null?null:result.simulation().metrics());
        data.put("securityMetrics",result.simulation()==null?null:new SecurityMetricsCollector().collect(scenario,result.snapshot().journal()));
        data.put("physicalTimeAdvancedBySearch",false);
        JsonFiles.write(out.resolve("result.json"),data);
    }
}
