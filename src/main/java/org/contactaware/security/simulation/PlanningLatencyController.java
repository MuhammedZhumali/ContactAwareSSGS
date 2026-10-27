package org.contactaware.security.simulation;

import java.time.*;
import java.util.*;
import java.util.function.LongSupplier;

/** Separate critical-path latency sensitivity, never used by the offline exact reference.
 * The logical coordinator waits while computing; committed work and physical events continue.
 * No optimality proof survives shifting a decision to a new physical instant. */
public final class PlanningLatencyController {
    public record Model(Duration fixedLatency, double hostRuntimeScale, Duration decisionBudget) {
        public Model {
            Objects.requireNonNull(fixedLatency); Objects.requireNonNull(decisionBudget);
            if(fixedLatency.isNegative() || decisionBudget.isNegative() || !Double.isFinite(hostRuntimeScale) || hostRuntimeScale<0)
                throw new IllegalArgumentException("Invalid latency assumption");
        }
        Duration latency(long hostNanos) {
            double scaled=hostRuntimeScale*hostNanos;
            if(scaled>Long.MAX_VALUE) throw new IllegalArgumentException("Latency overflow");
            return fixedLatency.plusNanos((long)Math.ceil(scaled));
        }
    }
    public record Decision(PlanningObservation origin, PolicySelection selection, long hostNanos,
                           Duration modeledLatency, Instant availableAt, boolean deadlineMiss,
                           String disposition) { }
    public record Result(ControlledSimulationResult execution,List<Decision> latencyDecisions) {
        public Result { latencyDecisions=List.copyOf(latencyDecisions); }
    }
    private final SecurityPolicy policy;
    private final Model model;
    private final Duration controllerBudget;
    private final SecurityPolicyController.CheckpointListener listener;
    private final LongSupplier clock;
    public PlanningLatencyController(SecurityPolicy policy,Model model,Duration controllerBudget,
                                     SecurityPolicyController.CheckpointListener listener) {
        this(policy, model, controllerBudget, listener, System::nanoTime);
    }
    PlanningLatencyController(SecurityPolicy policy,Model model,Duration controllerBudget,
                              SecurityPolicyController.CheckpointListener listener,LongSupplier clock) {
        this.policy=Objects.requireNonNull(policy); this.model=Objects.requireNonNull(model);
        this.controllerBudget=Objects.requireNonNull(controllerBudget); this.listener=Objects.requireNonNull(listener);
        this.clock=Objects.requireNonNull(clock);
    }
    public Result run(ExecutionSnapshot initial) {
        var engine=new SimulationEngine(); var generator=new SecurityActionGenerator();
        var current=engine.controlledSnapshot(initial);
        var decisions=new ArrayList<ControlledSimulationResult.Decision>();
        var delays=new ArrayList<Decision>();
        ControlledSimulationResult.Status status=ControlledSimulationResult.Status.FINISHED;
        SimulationResult completed=null;
        long started=System.nanoTime(),planningNanos=0,searches=0;
        try(var control=SearchControl.open(controllerBudget,SearchDiagnostics.StopReason.CONTROLLER_TIME_LIMIT)) {
            while(current.at().isBefore(current.scenario().end())) {
                listener.save(current,decisions.size()); SearchControl.checkpoint("latency.controller");
                // Include observation/candidate construction in the measured planning pipeline.
                // Checkpoint persistence, actual execution and fresh admission remain separate.
                long before=clock.getAsLong();
                PlanningObservation origin=null;
                PolicySelection selection=null;
                IncidentResponse.Response response=null;
                boolean hostDeadline=false;
                try(var decision=SearchControl.open(model.decisionBudget(),SearchDiagnostics.StopReason.DECISION_TIME_LIMIT)) {
                    SearchControl.checkpoint("latency.observation");
                    origin=PlanningObservation.from(current,policy.configuration().horizon());
                    origin=origin.withAdditionalWakeUps(policy.additionalWakeUps(origin));
                    var candidates=generator.candidates(origin);
                    response=IncidentResponse.next(current,origin,candidates);
                    if(response==null) selection=policy.select(origin,candidates);
                    SearchControl.checkpoint("latency.selection.complete");
                } catch(SearchControl.Stopped stopped) {
                    if(stopped.reason()!=SearchDiagnostics.StopReason.DECISION_TIME_LIMIT)throw stopped;
                    hostDeadline=true;
                    // No rollout or replacement optimization outside the expired scope.
                    selection=PolicySelection.rule(null,"DECISION_TIME_LIMIT: discard stale result");
                    if(origin==null) origin=PlanningObservation.from(current,policy.configuration().horizon());
                }
                // The existing local emergency mechanism does not wait for optimization.
                if(response!=null) {
                    var next=response.notice()!=null ? engine.controlledStart(current,response.notice())
                            : SecurityPolicyController.executeAction(current,origin,response.action(),OperationOutcomes.nominal());
                    decisions.add(new ControlledSimulationResult.Decision(origin,null,response.action(),next.at(),
                            ControlledSimulationResult.DecisionSource.RULE,response.reason()));
                    current=next; continue;
                }
                long host=Math.max(0,clock.getAsLong()-before);
                planningNanos=Math.addExact(planningNanos,host);
                var search=selection.search();
                if(search!=null)searches++;
                if(search!=null && search.status()!=HorizonPlanResult.Status.COMPLETE) {
                    status=search.status()==HorizonPlanResult.Status.INCOMPLETE ? stopStatus(search.diagnostics().stopReason())
                            : ControlledSimulationResult.Status.NO_FEASIBLE_PLAN;
                    delays.add(new Decision(origin,selection,host,Duration.ZERO,current.at(),false,"SEARCH_STOPPED")); break;
                }
                var latency=model.latency(host); var available=origin.at().plus(latency);
                var end=current.scenario().end(); if(available.isAfter(end)) available=end;
                while(current.at().isBefore(available)) {
                    SearchControl.checkpoint("latency.advanceCommittedWork");
                    current=engine.controlledAdvance(current,available,false);
                }
                boolean miss=hostDeadline || latency.compareTo(model.decisionBudget())>0;
                if(current.at().equals(end)) {
                    delays.add(new Decision(origin,selection,host,latency,available,miss,"END_DURING_COMPUTATION")); break;
                }
                var now=PlanningObservation.from(current,policy.configuration().horizon());
                now=now.withAdditionalWakeUps(policy.additionalWakeUps(now));
                var fresh=generator.candidates(now);
                var mandatory=IncidentResponse.next(current,now,fresh);
                if(mandatory!=null) {
                    delays.add(new Decision(origin,selection,host,latency,available,miss,"SUPERSEDED_BY_OBSERVED_INCIDENT"));
                    continue;
                }
                var selected=selection.action();
                PlanningAction action=null;
                // WAIT is event-bound in the ordinary executor, even when its requested
                // until is later than the earliest wake. A completed wake cannot become
                // a different future WAIT merely because both have null context/keys.
                if(!miss && selected!=null && selected.kind()==PlanningAction.Kind.WAIT) {
                    var intendedWake=selected.until();
                    for(var wake:origin.wakeUps())if(wake.isBefore(intendedWake))intendedWake=wake;
                    if(!now.at().isBefore(intendedWake)) {
                        delays.add(new Decision(origin,selection,host,latency,available,false,"WAIT_EVENT_REACHED_REPLAN"));
                        continue; // Actual time advanced; next selection and its cost are measured again.
                    }
                }
                if(!miss && selected!=null)
                    action=fresh.stream().filter(a->sameIntent(a,selected))
                            .min(Comparator.comparing(PlanningAction::until)).orElse(null);
                if(!miss && selected!=null && selected.kind()==PlanningAction.Kind.WAIT && action!=null
                        && now.at().isAfter(origin.at())) {
                    var originalWake=selected.until();
                    for(var wake:origin.wakeUps())if(wake.isBefore(originalWake))originalWake=wake;
                    var freshWake=action.until();
                    for(var wake:now.wakeUps())if(wake.isBefore(freshWake))freshWake=wake;
                    if(!originalWake.equals(freshWake)) {
                        delays.add(new Decision(origin,selection,host,latency,available,false,"WAIT_EVENT_CHANGED_REPLAN"));
                        continue; // An interrupted operation may remove its obsolete completion wake.
                    }
                }
                if(!miss && selected!=null && action==null && now.at().isAfter(origin.at())) {
                    delays.add(new Decision(origin,selection,host,latency,available,false,"STALE_INTENT_REPLAN"));
                    continue;
                }
                String disposition=action!=null ? "REVALIDATED_FIRST_ACTION" : miss ? "DEADLINE_MISS_WAIT" : "STALE_ACTION_WAIT";
                if(action==null) action=fresh.stream().filter(a->a.kind()==PlanningAction.Kind.WAIT)
                        .min(Comparator.comparing(PlanningAction::until)).orElse(null);
                var next=SecurityPolicyController.executeAction(current,now,action,OperationOutcomes.nominal());
                decisions.add(new ControlledSimulationResult.Decision(now,null,action,next.at(),
                        ControlledSimulationResult.DecisionSource.RULE,disposition));
                delays.add(new Decision(origin,selection,host,latency,available,miss,disposition));
                if(next.at().equals(current.at()) && next.pendingOperations().equals(current.pendingOperations())) {
                    status=ControlledSimulationResult.Status.EXECUTION_STALLED; break;
                }
                current=next;
            }
            if(status==ControlledSimulationResult.Status.FINISHED) {
                var finish=engine.finishControlled(current); current=finish.snapshot(); completed=finish.result();
            }
        } catch(SearchControl.Stopped stopped) {
            status=stopStatus(stopped.reason());
        }
        listener.save(current,decisions.size());
        var computation=new ControlledSimulationResult.Computation(System.nanoTime()-started,0,0,
                planningNanos,searches,0,Map.of());
        return new Result(new ControlledSimulationResult(policy.configuration(),status,current,completed,decisions,computation),delays);
    }
    private static ControlledSimulationResult.Status stopStatus(SearchDiagnostics.StopReason reason) {
        return switch(reason) {
            case CANCELLED -> ControlledSimulationResult.Status.CANCELLED;
            case INTERRUPTED -> ControlledSimulationResult.Status.INTERRUPTED;
            case CONTROLLER_TIME_LIMIT -> ControlledSimulationResult.Status.CONTROLLER_TIME_LIMIT;
            default -> ControlledSimulationResult.Status.SEARCH_INCOMPLETE;
        };
    }
    private static boolean sameIntent(PlanningAction a,PlanningAction b) {
        return a.kind()==b.kind() && Objects.equals(a.contextId(),b.contextId()) && Objects.equals(a.keys(),b.keys())
                && Objects.equals(a.contactId(),b.contactId());
    }
}
