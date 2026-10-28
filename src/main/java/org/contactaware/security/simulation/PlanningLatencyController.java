package org.contactaware.security.simulation;

import java.time.*;
import java.util.*;
import java.util.function.Consumer;
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
                           String disposition, ControlledSimulationResult.ContinuationAudit continuation) {
        public Decision(PlanningObservation origin, PolicySelection selection, long hostNanos,
                        Duration modeledLatency, Instant availableAt, boolean deadlineMiss, String disposition) {
            this(origin,selection,hostNanos,modeledLatency,availableAt,deadlineMiss,disposition,null);
        }
    }
    public record Result(ControlledSimulationResult execution,List<Decision> latencyDecisions) {
        public Result { latencyDecisions=List.copyOf(latencyDecisions); }
    }
    private final SecurityPolicy policy;
    private final Model model;
    private final Duration controllerBudget;
    private final SecurityPolicyController.CheckpointListener listener;
    private final LongSupplier clock;
    private final Consumer<String> decisionCheckpointHook;
    public PlanningLatencyController(SecurityPolicy policy,Model model,Duration controllerBudget,
                                     SecurityPolicyController.CheckpointListener listener) {
        this(policy, model, controllerBudget, listener, System::nanoTime);
    }
    PlanningLatencyController(SecurityPolicy policy,Model model,Duration controllerBudget,
                              SecurityPolicyController.CheckpointListener listener,LongSupplier clock) {
        this(policy,model,controllerBudget,listener,clock,ignored -> {});
    }
    PlanningLatencyController(SecurityPolicy policy,Model model,Duration controllerBudget,
                              SecurityPolicyController.CheckpointListener listener,LongSupplier clock,
                              Consumer<String> decisionCheckpointHook) {
        this.policy=Objects.requireNonNull(policy); this.model=Objects.requireNonNull(model);
        this.controllerBudget=Objects.requireNonNull(controllerBudget); this.listener=Objects.requireNonNull(listener);
        this.clock=Objects.requireNonNull(clock);
        this.decisionCheckpointHook=Objects.requireNonNull(decisionCheckpointHook);
    }
    public Result run(ExecutionSnapshot initial) {
        var engine=new SimulationEngine(); var generator=new SecurityActionGenerator();
        var current=engine.controlledSnapshot(initial);
        var decisions=new ArrayList<ControlledSimulationResult.Decision>();
        var delays=new ArrayList<Decision>();
        var guard=new ContinuationReuseGuard();
        ContinuationReuseGuard.ProvenContinuation proof=null;
        long guardTotal=0,preparationTotal=0,suffixReuses=0;
        var guardReasons=new TreeMap<String,Long>();
        ControlledSimulationResult.Status status=ControlledSimulationResult.Status.FINISHED;
        SimulationResult completed=null;
        long started=System.nanoTime(),planningNanos=0,searches=0;
        try(var control=SearchControl.open(controllerBudget,SearchDiagnostics.StopReason.CONTROLLER_TIME_LIMIT)) {
            while(current.at().isBefore(current.scenario().end())) {
                listener.save(current,decisions.size()); SearchControl.checkpoint("latency.controller");
                // Include observation, candidates, proof verification/preparation and policy selection.
                // Checkpoint persistence, actual execution and fresh admission remain separate.
                long before=clock.getAsLong();
                PlanningObservation origin=null;
                PolicySelection selection=null;
                IncidentResponse.Response response=null;
                boolean hostDeadline=false,reused=false;
                var checkedProof=proof;
                ContinuationReuseGuard.Result verdict=null;
                long guardNanos=0,preparationNanos=0;
                try(var decision=SearchControl.open(model.decisionBudget(),SearchDiagnostics.StopReason.DECISION_TIME_LIMIT,
                        clock,decisionCheckpointHook)) {
                    SearchControl.checkpoint("latency.observation");
                    origin=PlanningObservation.from(current,policy.configuration().horizon());
                    origin=origin.withAdditionalWakeUps(policy.additionalWakeUps(origin));
                    var candidates=generator.candidates(origin);
                    response=IncidentResponse.next(current,origin,candidates);
                    if(response==null) {
                        var rules=policy.continuationRules();
                        long phase=clock.getAsLong();
                        ContinuationReuseGuard.Assessment assessment;
                        try {
                            SearchControl.checkpoint("latency.reuseGuard");
                            assessment=guard.assess(proof,origin,rules);
                        } finally {guardNanos=Math.max(0,clock.getAsLong()-phase);guardTotal+=guardNanos;}
                        verdict=assessment.result();
                        SearchControl.checkpoint("latency.reuseGuard.complete");
                        if(verdict.status()==ContinuationReuseGuard.Status.ALLOWED) {
                            phase=clock.getAsLong();
                            try {SearchControl.checkpoint("latency.reusePreparation");proof=assessment.derive();}
                            finally {long elapsed=Math.max(0,clock.getAsLong()-phase);preparationNanos+=elapsed;preparationTotal+=elapsed;}
                            if(proof.firstAction()==null)throw new IllegalStateException("Proven suffix unexpectedly empty inside a contact");
                            selection=PolicySelection.rule(proof.firstAction(),"First action of proven continuation "+proof.reference());
                            reused=true;
                        }
                        if(!reused) {proof=null;selection=policy.select(origin,candidates);}
                        SearchControl.checkpoint("latency.selection.complete");
                        var search=selection.search();
                        if(search!=null && search.status()==HorizonPlanResult.Status.COMPLETE
                                && rules!=null && policy.configuration() instanceof HorizonPolicy && selection.certificate()!=null
                                && selection.certificate().matches(origin,search,rules)) {
                            phase=clock.getAsLong();
                            try {SearchControl.checkpoint("latency.reusePreparation");proof=guard.start(selection.certificate().evidence(),"decision:"+delays.size());}
                            finally {long elapsed=Math.max(0,clock.getAsLong()-phase);preparationNanos+=elapsed;preparationTotal+=elapsed;}
                        }
                        if(selection.action()!=null && !candidates.contains(selection.action()))
                            throw new IllegalArgumentException("Policy selected an inadmissible first action");
                    } else {
                        proof=null;
                        verdict=new ContinuationReuseGuard.Result(ContinuationReuseGuard.Status.REJECTED,
                                "EMERGENCY_RESPONSE: observed incident takes precedence");
                    }
                    SearchControl.checkpoint("latency.decision.complete");
                } catch(SearchControl.Stopped stopped) {
                    if(stopped.reason()!=SearchDiagnostics.StopReason.DECISION_TIME_LIMIT)throw stopped;
                    hostDeadline=true;
                    proof=null;reused=false;
                    // No rollout or replacement optimization outside the expired scope.
                    selection=PolicySelection.rule(null,"DECISION_TIME_LIMIT: discard stale result");
                    if(origin==null) origin=PlanningObservation.from(current,policy.configuration().horizon());
                }
                if(verdict!=null)guardReasons.merge(verdict.status()+":"+verdict.reason().split(":",2)[0],1L,Long::sum);
                var audit=new ControlledSimulationResult.ContinuationAudit(proof==null?null:proof.reference(),proof==null?-1:proof.offset(),
                        checkedProof==null?null:checkedProof.reference(),checkedProof==null?-1:checkedProof.offset(),verdict,guardNanos,preparationNanos);
                // The existing local emergency mechanism does not wait for optimization.
                if(response!=null) {
                    var next=response.notice()!=null ? engine.controlledStart(current,response.notice())
                            : SecurityPolicyController.executeAction(current,origin,response.action(),OperationOutcomes.nominal());
                    decisions.add(new ControlledSimulationResult.Decision(origin,null,response.action(),next.at(),
                            ControlledSimulationResult.DecisionSource.RULE,response.reason(),audit));
                    current=next; continue;
                }
                long host=Math.max(0,clock.getAsLong()-before);
                planningNanos=Math.addExact(planningNanos,host);
                var search=selection.search();
                if(search!=null)searches++;
                if(search!=null && search.status()!=HorizonPlanResult.Status.COMPLETE) {
                    status=search.status()==HorizonPlanResult.Status.INCOMPLETE ? stopStatus(search.diagnostics().stopReason())
                            : ControlledSimulationResult.Status.NO_FEASIBLE_PLAN;
                    proof=null;
                    delays.add(new Decision(origin,selection,host,Duration.ZERO,current.at(),false,"SEARCH_STOPPED",audit)); break;
                }
                var latency=model.latency(host); var available=origin.at().plus(latency);
                var end=current.scenario().end(); if(available.isAfter(end)) available=end;
                while(current.at().isBefore(available)) {
                    SearchControl.checkpoint("latency.advanceCommittedWork");
                    current=engine.controlledAdvance(current,available,false);
                }
                boolean miss=hostDeadline || latency.compareTo(model.decisionBudget())>0;
                if(miss)proof=null;
                if(current.at().equals(end)) {
                    delays.add(new Decision(origin,selection,host,latency,available,miss,"END_DURING_COMPUTATION",audit)); break;
                }
                var now=PlanningObservation.from(current,policy.configuration().horizon());
                now=now.withAdditionalWakeUps(policy.additionalWakeUps(now));
                var fresh=generator.candidates(now);
                var mandatory=IncidentResponse.next(current,now,fresh);
                if(mandatory!=null) {
                    proof=null;
                    delays.add(new Decision(origin,selection,host,latency,available,miss,"SUPERSEDED_BY_OBSERVED_INCIDENT",audit));
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
                        proof=null;
                        delays.add(new Decision(origin,selection,host,latency,available,false,"WAIT_EVENT_REACHED_REPLAN",audit));
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
                        proof=null;
                        delays.add(new Decision(origin,selection,host,latency,available,false,"WAIT_EVENT_CHANGED_REPLAN",audit));
                        continue; // An interrupted operation may remove its obsolete completion wake.
                    }
                }
                if(!miss && selected!=null && action==null && now.at().isAfter(origin.at())) {
                    proof=null;
                    delays.add(new Decision(origin,selection,host,latency,available,false,"STALE_INTENT_REPLAN",audit));
                    continue;
                }
                String disposition=action!=null ? "REVALIDATED_FIRST_ACTION" : miss ? "DEADLINE_MISS_WAIT" : "STALE_ACTION_WAIT";
                boolean provenExecution=reused && !miss && now.equals(origin) && Objects.equals(action,selected);
                if(action==null) {
                    proof=null;
                    action=fresh.stream().filter(a->a.kind()==PlanningAction.Kind.WAIT)
                            .min(Comparator.comparing(PlanningAction::until)).orElse(null);
                }
                var next=SecurityPolicyController.executeAction(current,now,action,OperationOutcomes.nominal());
                decisions.add(new ControlledSimulationResult.Decision(now,null,action,next.at(),
                        provenExecution?ControlledSimulationResult.DecisionSource.SUFFIX_REUSE:ControlledSimulationResult.DecisionSource.RULE,disposition,audit));
                if(provenExecution)suffixReuses++;
                // A shifted first action retains only original evidence for the next full guard check;
                // its old certificate is never attached to the execution at the shifted instant.
                delays.add(new Decision(origin,selection,host,latency,available,miss,disposition,audit));
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
        var computation=new ControlledSimulationResult.Computation(System.nanoTime()-started,guardTotal,preparationTotal,
                planningNanos,searches,suffixReuses,guardReasons);
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
