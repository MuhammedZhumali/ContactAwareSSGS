package org.contactaware.security.simulation;

import java.time.*;
import java.util.*;

/** PC2: bounded feasible candidates, all evaluated at the SAME actual observable root.
 * This is neither an optimality cache nor an additional objective or action filter. */
public final class PracticalPlanPortfolio {
    public enum Variant { PC1, SEED_ONLY, SEED_AND_RETENTION, SEED_WITHOUT_SEARCH }
    public enum Source { SEED, RETAINED, SEARCH, SAFE_FALLBACK }
    public record Candidate(Source source, PlanningResult result) { }
    public record Audit(Instant at, int horizon, Instant evaluationEnd, Source source,
                        PlanningResult selected, List<Candidate> candidates, String retention,
                        long seedNanos, long suffixNanos, long searchNanos, long selectionNanos,
                        long seedTransitions, long suffixTransitions, long searchTransitions,
                        long predictionTransitions, String seedStatus, SearchRuntimeTelemetry runtime) {
        public Audit { candidates=List.copyOf(candidates); }
        public long totalTransitions() { return seedTransitions+suffixTransitions+searchTransitions+predictionTransitions; }
    }
    public record Decision(PolicySelection selection, HorizonPlanResult search, Audit audit) { }
    public record StoppedWork(Instant at, String reason, String checkpoint, String phase, long elapsedNanos,
                              long activePhaseNanos, long seedTransitions, long suffixTransitions,
                              long searchTransitions, long predictionTransitions) { }
    private static final class Work {
        long started=System.nanoTime(),phaseStarted=started,seedSteps,suffixSteps,searchSteps,predictionSteps;
        String phase="SEED";
        void phase(String next) { phase=next;phaseStarted=System.nanoTime(); }
    }
    private record Retained(PlanningObservation predictedChild, List<PlanningAction> remaining,
                            Instant previousEnd) { }
    private final HorizonPlanner planner;
    private final PlanTieBreak tieBreak;
    private final Variant variant;
    private final PlanningResultComparator comparator;
    private final BranchEngine engine=new BranchEngine();
    private final FeasibleSeedPlanBuilder builder=new FeasibleSeedPlanBuilder();
    private final List<Audit> audits=new ArrayList<>();
    private final List<StoppedWork> stops=new ArrayList<>();
    private Retained retained;

    public PracticalPlanPortfolio(HorizonPlanner planner,PlanTieBreak tieBreak,Variant variant) {
        this.planner=Objects.requireNonNull(planner);this.tieBreak=Objects.requireNonNull(tieBreak);
        this.variant=Objects.requireNonNull(variant);comparator=new PlanningResultComparator(tieBreak);
    }
    public List<Audit> audits() { return List.copyOf(audits); }
    public List<StoppedWork> stoppedWork() { return List.copyOf(stops); }

    public Decision select(PlanningObservation root,List<PlanningAction> firstCandidates) {
        var work=new Work();
        try {
            var decision=selectControlled(root,firstCandidates,work);
            if(decision.audit()==null) stopped(root,work,decision.search().diagnostics().stopReason().name(),"search.returnedStop");
            return decision;
        } catch(SearchControl.Stopped stop) {
            stopped(root,work,stop.reason().name(),stop.checkpoint());throw stop;
        }
    }
    private void stopped(PlanningObservation root,Work w,String reason,String checkpoint) {
        retained=null;
        stops.add(new StoppedWork(root.at(),reason,checkpoint,w.phase,System.nanoTime()-w.started,
                System.nanoTime()-w.phaseStarted,w.seedSteps,w.suffixSteps,w.searchSteps,w.predictionSteps));
    }
    private Decision selectControlled(PlanningObservation root,List<PlanningAction> firstCandidates,Work work) {
        var runtime=new SearchRuntimeTelemetry.Recorder();
        long started=System.nanoTime(),seedNs=0,suffixNs=0,searchNs=0,seedSteps=0,suffixSteps=0,predictionSteps=0;
        var available=new ArrayList<Candidate>();
        var previous=retained;retained=null;
        String retainedStatus=previous==null?"NO_RETAINED_PLAN":"DISABLED",seedStatus="NOT_COMPLETED";
        long before=System.nanoTime();
        // Small sub-budgets reserve time for search and final comparison inside the same 2 s decision.
        // Parent cancellation/deadline always wins; no rollout continues after its scope expires.
        try(var scope=SearchControl.open(Duration.ofMillis(350),SearchDiagnostics.StopReason.TIME_LIMIT)) {
            var seed=builder.build(root,tieBreak); seedSteps=seed.transitions();
            if(seed.result()!=null) { available.add(new Candidate(Source.SEED,seed.result()));seedStatus="FEASIBLE_TERMINAL"; }
            else seedStatus="TRANSITION_CAP";
        } catch(SearchControl.Stopped stopped) {
            if(stopped.reason()!=SearchDiagnostics.StopReason.TIME_LIMIT)throw stopped;
            seedStatus="TIME_LIMIT:"+stopped.checkpoint();
        } finally {seedNs=System.nanoTime()-before;seedSteps=builder.transitions();work.seedSteps=seedSteps;}
        before=System.nanoTime();
        work.phase("SUFFIX");
        if(variant==Variant.SEED_AND_RETENTION && previous!=null) {
            retainedStatus=anchorMismatch(previous.predictedChild(),root);
            if(retainedStatus==null) {
                boolean extending=false;
                try(var scope=SearchControl.open(Duration.ofMillis(350),SearchDiagnostics.StopReason.TIME_LIMIT)) {
                    var branch=engine.create(root);
                    for(var action:previous.remaining()) {
                        SearchControl.checkpoint("portfolio.retainedReplay");
                        if(action.at().isBefore(branch.snapshot().at()))throw new IllegalArgumentException("STALE_ACTION_TIME");
                        suffixSteps++;work.suffixSteps++;branch=engine.execute(branch,action);
                    }
                    if(!branch.snapshot().at().equals(previous.previousEnd()))
                        throw new IllegalArgumentException("OLD_ENDPOINT_NOT_REACHED");
                    extending=true;
                    var extended=builder.complete(branch,tieBreak);
                    if(extended.result()!=null) {
                        available.add(new Candidate(Source.RETAINED,extended.result()));retainedStatus="FEASIBLE_RETAINED";
                    } else retainedStatus="REJECTED_EXTENSION_TRANSITION_CAP";
                } catch(IllegalArgumentException rejected) {retainedStatus="REJECTED_REPLAY:"+rejected.getMessage();}
                catch(SearchControl.Stopped stopped) {
                    if(stopped.reason()!=SearchDiagnostics.StopReason.TIME_LIMIT)throw stopped;
                    retainedStatus="REJECTED_TIME_LIMIT:"+stopped.checkpoint();
                } finally { if(extending) { suffixSteps+=builder.transitions();work.suffixSteps+=builder.transitions(); } }
            }
        }
        suffixNs=System.nanoTime()-before;
        PolicySelection searched=null; HorizonPlanResult search=null;
        before=System.nanoTime();
        work.phase("SEARCH");
        if(variant!=Variant.SEED_WITHOUT_SEARCH) {
            searched=planner.selectUnrestricted(root,"PC2 bounded search; feasible candidates are not optimality proofs");
            search=searched.search();
            work.searchSteps=search.diagnostics().executedTransitions();
            if(search.status()==HorizonPlanResult.Status.INCOMPLETE
                    && search.diagnostics().stopReason()!=SearchDiagnostics.StopReason.NODE_LIMIT
                    && search.diagnostics().stopReason()!=SearchDiagnostics.StopReason.TIME_LIMIT)
                return new Decision(searched,search,null); // Global stop cannot become fallback permission.
            SearchControl.checkpoint("portfolio.afterSearch");
            if(search.score()!=null) available.add(new Candidate(Source.SEARCH,new PlanningResult(search.score(),search.bestPlan())));
        }
        searchNs=System.nanoTime()-before;
        work.phase("COMPARISON_AND_PREDICTION");
        // A terminal WAIT incumbent never bypasses comparison with seed or the replayed suffix.
        Candidate best=null;
        for(var candidate:available) {
            if(best==null || comparator.compare(candidate.result(),best.result())<0
                    || comparator.compare(candidate.result(),best.result())==0 && candidate.source()==Source.SEARCH)best=candidate;
        }
        var action=best==null ? BudgetedHorizonPolicy.fallback(root,firstCandidates,tieBreak)
                : best.result().plan().isEmpty()?null:best.result().plan().getFirst();
        if(action!=null && !firstCandidates.contains(action))throw new IllegalStateException("Portfolio selected inadmissible first action");
        var source=best==null?Source.SAFE_FALLBACK:best.source();
        // Predict only the committed first transition. It consumes no real IDs, counters or costs.
        // An actual mismatch on the next call invalidates retention; timestamps are never shifted.
        if(variant==Variant.SEED_AND_RETENTION && best!=null && action!=null) {
            SearchControl.checkpoint("portfolio.predictFirst");
            predictionSteps++;work.predictionSteps++;
            var child=engine.execute(engine.create(root),action);
            retained=new Retained(child.observation(),List.copyOf(best.result().plan().subList(1,best.result().plan().size())),root.evaluationEnd());
        }
        SearchControl.checkpoint("portfolio.selection.complete");
        var audit=new Audit(root.at(),root.horizon(),root.evaluationEnd(),source,best==null?null:best.result(),available,retainedStatus,
                seedNs,suffixNs,searchNs,System.nanoTime()-started,seedSteps,suffixSteps,
                search==null?0:search.diagnostics().executedTransitions(),predictionSteps,seedStatus,
                runtime.snapshot(System.nanoTime()-started,null));
        audits.add(audit);
        // A certificate is exposed only from an actual COMPLETE unrestricted search with this full winner.
        var selection=source==Source.SEARCH && search.status()==HorizonPlanResult.Status.COMPLETE ? searched
                : PolicySelection.rule(action,"PC2_"+source+(best==null
                    ? ": no fully evaluated candidate; admissible local fallback; no optimality certificate"
                    : ": best available fully evaluated continuation; no optimality certificate"));
        return new Decision(selection,search,audit);
    }

    /** Conservative receipt check after actual execution, ignoring only permitted horizon growth.
     * All old still-visible contacts must be an identical prefix; replay sees only the new root.
     * It neither reads the old hidden schedule nor carries an old score/cost origin. */
    static String anchorMismatch(PlanningObservation expected,PlanningObservation actual) {
        if(!expected.at().equals(actual.at()))return "REJECTED_STALE_TIME";
        if(expected.horizon()!=actual.horizon())return "REJECTED_HORIZON";
        if(!expected.costs().equals(actual.costs()) || !expected.constraints().equals(actual.constraints()))return "REJECTED_MODEL";
        if(!Objects.equals(expected.currentContact(),actual.currentContact()))return "REJECTED_CONTACT_OR_OUTAGE";
        if(actual.evaluationEnd().isBefore(expected.evaluationEnd()))return "REJECTED_ENDPOINT_SHRINK";
        if(actual.visibleContacts().size()<expected.visibleContacts().size()
                || !actual.visibleContacts().subList(0,expected.visibleContacts().size()).equals(expected.visibleContacts()))return "REJECTED_FORECAST";
        if(!expected.securityState().equals(actual.securityState()))return "REJECTED_SECURITY_COUNTERS_OR_NOTICE";
        if(!expected.ongoingOperations().equals(actual.ongoingOperations())
                || !expected.resourceReservations().equals(actual.resourceReservations())
                || !expected.uncertainContexts().equals(actual.uncertainContexts())
                || !Objects.equals(expected.inFlightFrame(),actual.inFlightFrame()))return "REJECTED_COMMITTED_WORK_OR_UNCERTAINTY";
        if(!expected.payloadProgress().equals(actual.payloadProgress()) || !expected.usefulSecureTime().equals(actual.usefulSecureTime()))return "REJECTED_PROGRESS";
        if(!expected.accruedCosts().equals(actual.accruedCosts()) || !expected.recordedCosts().equals(actual.recordedCosts())
                || expected.createdKeys()!=actual.createdKeys())return "REJECTED_COSTS";
        if(!expected.issuedKeyIds().equals(actual.issuedKeyIds()) || !expected.issuedOperationIds().equals(actual.issuedOperationIds()))return "REJECTED_ISSUED_IDS";
        return null;
    }

    /** Diagnostic injection of an already known continuation. Every action is still replayed on select. */
    void offerForReplay(PlanningObservation root,List<PlanningAction> plan) {
        retained=new Retained(root,List.copyOf(plan),root.evaluationEnd());
    }
}
