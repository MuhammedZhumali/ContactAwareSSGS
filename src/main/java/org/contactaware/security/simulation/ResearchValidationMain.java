package org.contactaware.security.simulation;

import org.contactaware.contacts.JsonFiles;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;

/** Registered R1 harness; model files, algorithm budgets and delay assumptions are explicit inputs. */
public final class ResearchValidationMain {
    private ResearchValidationMain() { }
    public static void main(String[] args) throws Exception {
        var f=new HashMap<String,String>();
        for(int i=0;i<args.length;i+=2)
            if(i+1==args.length || !args[i].startsWith("--") || f.put(args[i].substring(2),args[i+1])!=null)
                throw new IllegalArgumentException("Unique --flag value pairs required");
        if("configure".equals(f.get("mode"))) {
            var fixture=PolicyExperimentFixtures.selectedSchedule(Path.of(f.get("schedule")),Integer.parseInt(f.get("count")),
                    Duration.ofSeconds(Long.parseLong(f.get("cp"))),1,PolicyExperimentFixtures.TrafficWorkload.valueOf(f.get("workload")));
            var destination=Path.of(f.get("out")); if(Files.exists(destination)) throw new IllegalArgumentException("Existing model");
            JsonFiles.write(destination,Map.of("scenario",fixture.scenario(),"input",fixture.input(),"provenance",fixture.provenance()));
            return;
        }
        var out=Path.of(f.get("out"));
        if(Files.exists(out.resolve("checkpoint.json")) || Files.exists(out.resolve("result.json")))
            throw new IllegalArgumentException("Refusing an existing attempt");
        Files.createDirectories(out);
        var input=JsonFiles.JSON.readTree(Path.of(f.get("model")).toFile());
        var scenario=ExecutionCheckpointFiles.convert(input.required("scenario"),SimulationScenario.class);
        var rule=PlanTieBreak.valueOf(f.get("rule"));
        int h=Integer.parseInt(f.get("horizon"));
        var horizon=h==1?HorizonPolicy.next():HorizonPolicy.multi(h);
        var planner=new ExactHorizonPlanner(new SearchLimits(Long.parseLong(f.get("nodes")),Duration.ofMillis(Long.parseLong(f.get("search-ms")))),
                PlannerOptions.acceleratedServiceFirst(),DecisionTimeModel.V2_EVENT_BOUNDARIES,rule);
        var bounded=new BudgetedHorizonPolicy(horizon,planner,rule,
                PracticalPlanPortfolio.Variant.valueOf(f.getOrDefault("portfolio","SEED_AND_RETENTION")));
        SecurityPolicy policy=switch(f.get("mode")) {
            case "practical" -> bounded;
            case "fixed" -> new FixedPeriodPolicy(Duration.ofSeconds(Long.parseLong(f.get("period-s"))));
            case "percontact" -> new PerContactPolicy(PerContactPolicy.AdvancePreparation.JUST_IN_TIME);
            case "exact" -> new SecurityPolicy() {
                public PolicyConfiguration configuration(){return horizon;}
                public ContinuationReuseGuard.Rules continuationRules(){return planner.continuationRules();}
                public PolicySelection select(PlanningObservation o,List<PlanningAction> a){return planner.selectUnrestricted(o,"R1 exact offline");}
            };
            default -> throw new IllegalArgumentException("mode exact/practical/fixed/percontact");
        };
        var initial=new SimulationEngine().start(scenario);
        var budget=Duration.ofSeconds(Long.parseLong(f.get("controller-s")));
        SecurityPolicyController.CheckpointListener checkpoint=(snapshot,n)->{
            try {
                var d=ExecutionCheckpointFiles.writeWithDiagnostics(out.resolve("checkpoint.json"),snapshot);
                var line=JsonFiles.JSON.writer().without(com.fasterxml.jackson.databind.SerializationFeature.INDENT_OUTPUT)
                        .writeValueAsString(Map.of("at",snapshot.at(),"decisions",n,"diagnostics",d));
                Files.writeString(out.resolve("checkpoint-writes.jsonl"),line+System.lineSeparator(),StandardOpenOption.CREATE,StandardOpenOption.APPEND);
            }catch(java.io.IOException e){throw new java.io.UncheckedIOException(e);}
        };
        var measurement=new SearchRuntimeTelemetry.Recorder(); long start=System.nanoTime();
        ControlledSimulationResult run; List<PlanningLatencyController.Decision> delays=List.of();
        List<SecurityPolicyController.DecisionTiming> decisionTimings=List.of();
        var decisionBudget=Duration.ofMillis(Long.parseLong(f.getOrDefault("decision-budget-ms","0")));
        long delay=Long.parseLong(f.getOrDefault("latency-ms","0"));
        double scale=Double.parseDouble(f.getOrDefault("host-scale","0"));
        if(delay>0 || scale>0) {
            if (!decisionBudget.isZero())
                throw new IllegalArgumentException("Host decision deadline is currently supported by the offline controller; latency mode has its own explicit modeled deadline");
            var result=new PlanningLatencyController(policy,new PlanningLatencyController.Model(Duration.ofMillis(delay),scale,
                    Duration.ofMillis(Long.parseLong(f.getOrDefault("deadline-ms","250")))),budget,checkpoint).run(initial);
            run=result.execution(); delays=result.latencyDecisions();
        }else {
            var controller=new SecurityPolicyController(policy,budget,checkpoint,true,decisionBudget);
            run=controller.run(initial);decisionTimings=controller.decisionTimings();
        }
        long elapsed=System.nanoTime()-start;
        checkpoint.save(run.snapshot(),run.decisions().size());
        var fixture=new PolicyExperimentFixtures.Fixture(scenario.name(),"FROZEN_INPUT","R1; see registered model and manifest",f,scenario,initial);
        var exported=FourPolicyExperimentMain.exportCase(out,"actual",fixture,run,elapsed,true);
        JsonFiles.write(out.resolve("practical-searches.json"),bounded.attempts());
        JsonFiles.write(out.resolve("practical-feasible-reuse.json"),bounded.feasibleReuseAudit());
        JsonFiles.write(out.resolve("practical-portfolio.json"),bounded.portfolioAudit());
        JsonFiles.write(out.resolve("practical-portfolio-stops.json"),bounded.portfolioStops());
        JsonFiles.write(out.resolve("latency-decisions.json"),exportLatencyDecisions(delays));
        JsonFiles.write(out.resolve("decision-timings.json"),decisionTimings);
        var result=new LinkedHashMap<String,Object>();
        result.put("status",run.status()); result.put("mode",f.get("mode")); result.put("tieBreak",rule);result.put("horizon",h);
        result.put("summary",exported); result.put("computation",run.computation()); result.put("measurement",measurement.snapshot(elapsed,null));
        result.put("simulationMetrics",run.simulation()==null?null:run.simulation().metrics());
        result.put("securityMetrics",run.simulation()==null?null:new SecurityMetricsCollector().collect(scenario,run.snapshot().journal()));
        result.put("configuration",f);result.put("physicalTimeAdvancedBySearch",delay>0||scale>0);
        result.put("feasibleSuffixReuses",bounded.feasibleReuses());
        result.put("actualSearchAttempts",bounded.attempts().size());
        result.put("portfolioSelections",bounded.portfolioAudit().size());
        result.put("decisionBudgetNanos",decisionBudget.toNanos());
        result.put("decisionDeadlineMisses",decisionTimings.stream().filter(SecurityPolicyController.DecisionTiming::deadlineMiss).count());
        JsonFiles.write(out.resolve("result.json"),result);
        System.out.println(run.status()+" decisions="+run.decisions().size()+" boundedSearches="+bounded.attempts().size());
    }

    /** A certificate is an in-process capability, not a serializable artifact or reusable proof. */
    static List<Map<String,Object>> exportLatencyDecisions(List<PlanningLatencyController.Decision> decisions) {
        return decisions.stream().map(d->{
            Map<String,Object> selection=new LinkedHashMap<>();
            selection.put("action",d.selection().action());selection.put("search",d.selection().search());
            selection.put("reason",d.selection().reason());
            Map<String,Object> exported=new LinkedHashMap<>();
            exported.put("origin",d.origin());exported.put("selection",selection);exported.put("hostNanos",d.hostNanos());
            exported.put("modeledLatency",d.modeledLatency());exported.put("availableAt",d.availableAt());
            exported.put("deadlineMiss",d.deadlineMiss());exported.put("disposition",d.disposition());
            return exported;
        }).toList();
    }
}
