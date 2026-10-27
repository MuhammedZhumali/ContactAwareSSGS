package org.contactaware.security.simulation;

import org.contactaware.security.ContextId;
import java.time.Instant;
import java.util.*;

/** Explicit practical mode. A bounded incumbent is feasible, never certified optimal.
 * Exact controller behavior is unchanged. All incomplete searches remain in attempts(). */
public final class BudgetedHorizonPolicy implements SecurityPolicy {
    private final HorizonPolicy configuration;
    private final HorizonPlanner planner;
    private final PlanTieBreak tieBreak;
    private final List<Attempt> attempts = new ArrayList<>();
    private final BranchEngine branches = new BranchEngine();
    private final List<FeasibleReuseAudit> feasibleReuseAudit = new ArrayList<>();
    private FeasibleSuffix feasibleSuffix;
    private long feasibleReuses;
    private final PracticalPlanPortfolio portfolio;

    public record Attempt(Instant at, HorizonPlanResult search, String disposition,
                          PlanningAction selected, long heapUsedBytes) { }
    public record FeasibleReuseAudit(String reason, int sourceSearchIndex, int offset, Instant at) { }
    private record FeasibleSuffix(PlanningBranch predicted, PlanningObservation observation,
                                  List<PlanningAction> plan, int sourceSearchIndex, int offset) { }

    public BudgetedHorizonPolicy(HorizonPolicy configuration, ExactHorizonPlanner planner, PlanTieBreak tieBreak) {
        this(configuration, (HorizonPlanner) planner, tieBreak);
    }
    BudgetedHorizonPolicy(HorizonPolicy configuration, HorizonPlanner planner, PlanTieBreak tieBreak) {
        this(configuration, planner, tieBreak, PracticalPlanPortfolio.Variant.SEED_AND_RETENTION);
    }
    public BudgetedHorizonPolicy(HorizonPolicy configuration, HorizonPlanner planner, PlanTieBreak tieBreak,
                                PracticalPlanPortfolio.Variant variant) {
        this.configuration=Objects.requireNonNull(configuration);
        this.planner=Objects.requireNonNull(planner); this.tieBreak=Objects.requireNonNull(tieBreak);
        this.portfolio=variant==PracticalPlanPortfolio.Variant.PC1 ? null : new PracticalPlanPortfolio(planner,tieBreak,variant);
    }
    @Override public HorizonPolicy configuration() { return configuration; }
    @Override public ContinuationReuseGuard.Rules continuationRules() { return planner.continuationRules(); }
    public List<Attempt> attempts() { return List.copyOf(attempts); }
    public long feasibleReuses() { return feasibleReuses; }
    public List<FeasibleReuseAudit> feasibleReuseAudit() { return List.copyOf(feasibleReuseAudit); }
    public List<PracticalPlanPortfolio.Audit> portfolioAudit() { return portfolio==null ? List.of() : portfolio.audits(); }
    public List<PracticalPlanPortfolio.StoppedWork> portfolioStops() { return portfolio==null ? List.of() : portfolio.stoppedWork(); }

    @Override public PolicySelection select(PlanningObservation observation, List<PlanningAction> candidates) {
        Objects.requireNonNull(observation); Objects.requireNonNull(candidates);
        SearchControl.checkpoint("practical.selection");
        if(portfolio!=null) {
            var decision=portfolio.select(observation,candidates);
            if(decision.search()!=null) record(observation,decision.search(),decision.audit()==null?"STOP":decision.audit().source().name(),decision.selection().action());
            return decision.selection();
        }
        var retained = feasibleSuffix;
        feasibleSuffix = null;
        if (retained != null) {
            SearchControl.checkpoint("practical.feasibleReuse");
            var action = retained.plan().get(retained.offset());
            String rejection = !observation.equals(retained.observation()) ? "REJECTED_OBSERVATION_MISMATCH"
                    : !candidates.contains(action) ? "REJECTED_CANDIDATE_MISMATCH" : null;
            if (rejection == null) {
                retainAfterContinue(retained.predicted(), action, retained.plan(),
                        retained.sourceSearchIndex(), retained.offset() + 1);
                feasibleReuses = Math.incrementExact(feasibleReuses);
                feasibleReuseAudit.add(new FeasibleReuseAudit("FEASIBLE_FULL_OBSERVATION_MATCH",
                        retained.sourceSearchIndex(), retained.offset(), observation.at()));
                return PolicySelection.rule(action, "PRACTICAL_FEASIBLE_SUFFIX: matching nominal observation; "
                        + "source search " + retained.sourceSearchIndex() + ", offset " + retained.offset()
                        + "; no optimality certificate");
            }
            feasibleReuseAudit.add(new FeasibleReuseAudit(rejection, retained.sourceSearchIndex(),
                    retained.offset(), observation.at()));
        }
        var exact=planner.selectUnrestricted(observation, "R1 bounded search; only COMPLETE is optimal");
        var result=Objects.requireNonNull(exact.search());
        if (result.status()==HorizonPlanResult.Status.COMPLETE) {
            record(observation,result,"EXACT_COMPLETE",exact.action());
            return exact;
        }
        // An outer controller stop, cancellation or interruption must never become permission to run on.
        if (result.diagnostics().stopReason()!=SearchDiagnostics.StopReason.NODE_LIMIT
                && result.diagnostics().stopReason()!=SearchDiagnostics.StopReason.TIME_LIMIT) {
            record(observation,result,"STOP",null); return exact;
        }
        SearchControl.checkpoint("practical.afterSearch");
        if (result.score()!=null && result.firstAction()!=null && candidates.contains(result.firstAction())) {
            record(observation,result,"FEASIBLE_INCUMBENT",result.firstAction());
            if (result.status() == HorizonPlanResult.Status.INCOMPLETE
                    && result.firstAction().kind() == PlanningAction.Kind.CONTINUE) {
                var root = branches.create(observation);
                // Extra timers or other facts not represented by restoration cannot seed reuse.
                if (root.observation().equals(observation))
                    retainAfterContinue(root, result.firstAction(), result.bestPlan(), attempts.size() - 1, 1);
            }
            return PolicySelection.rule(result.firstAction(),"PRACTICAL_INCUMBENT: bounded terminal continuation; no optimality certificate");
        }
        var action=fallback(observation,candidates,tieBreak);
        record(observation,result,"SAFE_FALLBACK",action);
        return PolicySelection.rule(action,"PRACTICAL_FALLBACK: no terminal incumbent; admissible local service/bootstrap, else WAIT");
    }
    private void record(PlanningObservation o,HorizonPlanResult result,String disposition,PlanningAction action) {
        var r=Runtime.getRuntime();
        attempts.add(new Attempt(o.at(),result,disposition,action,r.totalMemory()-r.freeMemory()));
    }

    /** Retain only a nominal full-frame endpoint. A selected service step ends this reuse chain. */
    private void retainAfterContinue(PlanningBranch before, PlanningAction action, List<PlanningAction> plan,
                                     int sourceSearchIndex, int nextOffset) {
        if (action.kind() != PlanningAction.Kind.CONTINUE || nextOffset >= plan.size()) return;
        var observation = before.observation();
        var contact = observation.currentContact();
        if (contact == null || !action.at().equals(observation.at())
                || !action.until().equals(observation.at().plus(contact.payload().frameDuration()))
                || !action.until().isBefore(contact.window().end())) return;
        SearchControl.checkpoint("practical.feasiblePrediction");
        var after = branches.execute(before, action);
        if (!after.snapshot().at().equals(action.until()) || after.snapshot().inFlightFrame() != null) return;
        long frames = after.snapshot().journal().subList(before.snapshot().journal().size(), after.snapshot().journal().size())
                .stream().filter(e -> e.type() == SimulationEvent.Type.PAYLOAD_TRANSFERRED).count();
        if (frames != 1) return;
        var predicted = after.observation();
        if (!contact.equals(predicted.currentContact())) return;
        feasibleSuffix = new FeasibleSuffix(after, predicted, List.copyOf(plan), sourceSearchIndex, nextOffset);
    }

    /** Common deterministic fallback, not an optimizer or an extra objective criterion. */
    static PlanningAction fallback(PlanningObservation o,List<PlanningAction> actions,PlanTieBreak rule) {
        if (o.visibleContacts().isEmpty()) return null;
        var window=(o.currentContact()==null ? o.visibleContacts().getFirst() : o.currentContact()).window();
        var target=new ContextId(window.satelliteId(),window.groundStationId());
        var comparator=new PlanningResultComparator(rule);
        for (var kind : List.of(PlanningAction.Kind.CONTINUE,PlanningAction.Kind.OPEN,PlanningAction.Kind.REUSE,
                PlanningAction.Kind.SWITCH,PlanningAction.Kind.INSTALL,PlanningAction.Kind.PREPARE)) {
            var found=actions.stream().filter(a->a.kind()==kind && target.equals(a.contextId()))
                    .min(comparator::compareActions);
            if(found.isPresent()) return found.get();
        }
        return actions.stream().filter(a->a.kind()==PlanningAction.Kind.WAIT)
                .min(Comparator.comparing(PlanningAction::until)).orElseThrow();
    }
}
