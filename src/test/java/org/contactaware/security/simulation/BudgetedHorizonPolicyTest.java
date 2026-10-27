package org.contactaware.security.simulation;

import org.contactaware.security.SecurityState;
import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;
import static org.contactaware.security.simulation.HorizonWitness.*;
import static org.contactaware.security.simulation.PlanningAction.Kind.*;

class BudgetedHorizonPolicyTest {
    // Frozen PC1 behavior is retained explicitly for the controlled ablation.
    private static BudgetedHorizonPolicy legacy(HorizonPolicy h, HorizonPlanner p, PlanTieBreak t) {
        return new BudgetedHorizonPolicy(h, p, t, PracticalPlanPortfolio.Variant.PC1);
    }
    @Test void completedReferenceRetainsFullPlanAndScoreForBothRules() {
        var scenario=new ContinueSuffixPrepareOptimalityTest().fixture();
        var o=PlanningObservation.from(new SimulationEngine().start(scenario),2);
        for(var rule:PlanTieBreak.values()) {
            var reference=new ExactHorizonPlanner(new SearchLimits(250000,Duration.ofSeconds(30)),PlannerOptions.reference(),DecisionTimeModel.V2_EVENT_BOUNDARIES,rule);
            var expected=reference.plan(o);
            assertThat(expected.status()).isEqualTo(HorizonPlanResult.Status.COMPLETE);
            var policy=legacy(HorizonPolicy.multi(2),reference,rule);
            var actual=policy.select(o,new SecurityActionGenerator().candidates(o));
            assertThat(actual.search().status()).isEqualTo(HorizonPlanResult.Status.COMPLETE);
            assertThat(actual.search().bestPlan()).isEqualTo(expected.bestPlan());
            assertThat(actual.search().score()).isEqualTo(expected.score());
            assertThat(actual.certificate()).isNotNull();
        }
    }
    @Test void oneNodeBudgetFinishesSafelyAndRetainsIncompleteSearchDiagnostics() {
        var scenario=new ContinueSuffixPrepareOptimalityTest().fixture();
        for(var rule:PlanTieBreak.values()) {
            var policy=legacy(HorizonPolicy.multi(2),new ExactHorizonPlanner(new SearchLimits(1,Duration.ofSeconds(30))),rule);
            var run=new SecurityPolicyController(policy).run(scenario);
            assertThat(run.status()).isEqualTo(ControlledSimulationResult.Status.FINISHED);
            assertThat(policy.attempts()).anyMatch(a->a.disposition().equals("SAFE_FALLBACK"));
            assertThat(policy.attempts()).allMatch(a->a.search().status()!=HorizonPlanResult.Status.COMPLETE);
            assertThat(run.decisions()).noneMatch(d->d.source()==ControlledSimulationResult.DecisionSource.SUFFIX_REUSE);
            assertThat(run.snapshot().journal()).noneMatch(e->e.type()==SimulationEvent.Type.ACTION_FAILED);
        }
    }
    @Test void incompleteIncumbentIsExplicitRuleWithoutCertificateOrFalseComplete() {
        var scenario=new ContinueSuffixPrepareOptimalityTest().fixture();
        var o=PlanningObservation.from(new SimulationEngine().start(scenario),2);
        var full=new ExactHorizonPlanner(new SearchLimits(250000,Duration.ofSeconds(30)),PlannerOptions.reference()).plan(o);
        assertThat(full.status()).isEqualTo(HorizonPlanResult.Status.COMPLETE);
        var counter=new SearchDiagnostics.Counter(); counter.stopReason=SearchDiagnostics.StopReason.NODE_LIMIT;
        var stopped=new HorizonPlanResult(full.bestPlan(),full.score(),full.firstAction(),HorizonPlanResult.Status.INCOMPLETE,1,1,counter.snapshot());
        HorizonPlanner stub=obs->stopped;
        var policy=legacy(HorizonPolicy.multi(2),stub,PlanTieBreak.CANONICAL);
        var result=policy.select(o,new SecurityActionGenerator().candidates(o));
        assertThat(result.action()).isEqualTo(full.firstAction());
        assertThat(result.search()).isNull(); assertThat(result.certificate()).isNull();
        assertThat(policy.attempts().getFirst().search().status()).isEqualTo(HorizonPlanResult.Status.INCOMPLETE);
        assertThat(policy.attempts().getFirst().disposition()).isEqualTo("FEASIBLE_INCUMBENT");
    }

    @Test void completedActualFramesReuseFeasibleTerminalSuffixWithoutAnotherSearchOrCertificate() {
        var actual = warm(new RecedingHorizonControllerTest().keyed(6, List.of()));
        var calls = new AtomicInteger();
        HorizonPlanner stub = o -> { calls.incrementAndGet(); return terminalIncumbent(o); };
        var policy = legacy(HorizonPolicy.multi(2), stub, PlanTieBreak.CANONICAL);
        for (int offset = 0; offset < 3; offset++) {
            var observation = PlanningObservation.from(actual, 2);
            var selected = policy.select(observation, new SecurityActionGenerator().candidates(observation));
            assertThat(selected.action().kind()).isEqualTo(CONTINUE);
            assertThat(selected.search()).isNull();
            assertThat(selected.certificate()).isNull();
            if (offset > 0) assertThat(selected.reason()).contains("PRACTICAL_FEASIBLE_SUFFIX", "no optimality certificate");
            actual = SecurityPolicyController.executeAction(actual, observation, selected.action(), OperationOutcomes.nominal());
        }
        assertThat(calls.get()).isEqualTo(1);
        assertThat(policy.feasibleReuses()).isEqualTo(2);
        assertThat(policy.attempts()).hasSize(1);
        assertThat(policy.attempts().getFirst().search().status()).isEqualTo(HorizonPlanResult.Status.INCOMPLETE);
        assertThat(policy.feasibleReuseAudit()).extracting(BudgetedHorizonPolicy.FeasibleReuseAudit::offset).containsExactly(1, 2);
        assertThat(policy.feasibleReuseAudit()).allMatch(a -> a.sourceSearchIndex() == 0
                && a.reason().equals("FEASIBLE_FULL_OBSERVATION_MATCH"));
    }

    @Test void changedHorizonOrConsistentNonceCountersDiscardFeasibleSuffix() throws Exception {
        for (boolean changeHorizon : List.of(false, true)) {
            var actual = warm(new RecedingHorizonControllerTest().keyed(6, List.of()));
            var calls = new AtomicInteger();
            HorizonPlanner stub = o -> { calls.incrementAndGet(); return terminalIncumbent(o); };
            var policy = legacy(HorizonPolicy.multi(2), stub, PlanTieBreak.CANONICAL);
            var before = PlanningObservation.from(actual, 2);
            var first = policy.select(before, new SecurityActionGenerator().candidates(before));
            actual = SecurityPolicyController.executeAction(actual, before, first.action(), OperationOutcomes.nominal());
            var after = PlanningObservation.from(actual, 2);
            if (changeHorizon) after = changed(after, "horizon", 1);
            else {
                var state = after.securityState();
                var context = state.context(A);
                var key = state.key(context.uplink().keyId());
                var keys = new TreeMap<>(state.keys());
                keys.put(key.id(), changed(key, "nextNonceCounter", key.nextNonceCounter() + 1));
                var contexts = new TreeMap<>(state.contexts());
                contexts.put(A, changed(context, "uplink", changed(context.uplink(), "nextSequenceNumber", key.nextNonceCounter() + 1)));
                after = changed(after, "securityState", new SecurityState(state.satelliteId(), state.asOf(), contexts, keys));
            }
            policy.select(after, new SecurityActionGenerator().candidates(after));
            assertThat(calls.get()).isEqualTo(2);
            assertThat(policy.feasibleReuses()).isZero();
            assertThat(policy.feasibleReuseAudit()).extracting(BudgetedHorizonPolicy.FeasibleReuseAudit::reason)
                    .containsExactly("REJECTED_OBSERVATION_MISMATCH");
        }
    }

    @Test void matchedServiceActionAfterCompletedFrameEndsTheFeasibleReuseChain() {
        var actual = warm(new ContinueSuffixPrepareOptimalityTest().fixture());
        var calls = new AtomicInteger();
        HorizonPlanner stub = o -> { calls.incrementAndGet(); return terminalIncumbent(o); };
        var policy = legacy(HorizonPolicy.multi(2), stub, PlanTieBreak.CANONICAL);
        var before = PlanningObservation.from(actual, 2);
        var first = policy.select(before, new SecurityActionGenerator().candidates(before));
        assertThat(first.action().kind()).isEqualTo(CONTINUE);
        actual = SecurityPolicyController.executeAction(actual, before, first.action(), OperationOutcomes.nominal());
        var after = PlanningObservation.from(actual, 2);
        var next = policy.select(after, new SecurityActionGenerator().candidates(after));
        assertThat(next.action().kind()).isEqualTo(PREPARE);
        assertThat(calls.get()).isEqualTo(1);
        assertThat(policy.feasibleReuses()).isEqualTo(1);
        actual = SecurityPolicyController.executeAction(actual, after, next.action(), OperationOutcomes.nominal());
        var pending = PlanningObservation.from(actual, 2);
        policy.select(pending, new SecurityActionGenerator().candidates(pending));
        assertThat(calls.get()).isEqualTo(2);
        assertThat(policy.feasibleReuses()).isEqualTo(1);
    }

    @Test void cancellationCannotBecomeFallbackOrBypassTheFeasibleReuseCheckpoint() {
        var actual = warm(new RecedingHorizonControllerTest().keyed(6, List.of()));
        var before = PlanningObservation.from(actual, 2);
        var counter = new SearchDiagnostics.Counter(); counter.stopReason = SearchDiagnostics.StopReason.CANCELLED;
        var cancelled = new HorizonPlanResult(List.of(), null, null, HorizonPlanResult.Status.INCOMPLETE, 0, 0, counter.snapshot());
        var stoppedPolicy = legacy(HorizonPolicy.multi(2), o -> cancelled, PlanTieBreak.CANONICAL);
        assertThat(stoppedPolicy.select(before, new SecurityActionGenerator().candidates(before)).search()).isSameAs(cancelled);
        assertThat(stoppedPolicy.attempts().getFirst().disposition()).isEqualTo("STOP");

        var calls = new AtomicInteger();
        HorizonPlanner stub = o -> { calls.incrementAndGet(); return terminalIncumbent(o); };
        var policy = legacy(HorizonPolicy.multi(2), stub, PlanTieBreak.CANONICAL);
        var first = policy.select(before, new SecurityActionGenerator().candidates(before));
        actual = SecurityPolicyController.executeAction(actual, before, first.action(), OperationOutcomes.nominal());
        var after = PlanningObservation.from(actual, 2);
        var candidates = new SecurityActionGenerator().candidates(after);
        try (var scope = SearchControl.open(Duration.ZERO, SearchDiagnostics.StopReason.CONTROLLER_TIME_LIMIT)) {
            scope.cancel();
            assertThatThrownBy(() -> policy.select(after, candidates)).isInstanceOf(SearchControl.Stopped.class)
                    .hasMessageContaining("CANCELLED");
        }
        assertThat(calls.get()).isEqualTo(1);
        assertThat(policy.feasibleReuses()).isZero();
    }

    private ExecutionSnapshot warm(SimulationScenario scenario) {
        var actual = new SimulationEngine().controlledSnapshot(new SimulationEngine().start(scenario));
        for (var kind : List.of(OPEN, WAIT)) {
            var observation = PlanningObservation.from(actual, 2);
            var action = new SecurityActionGenerator().candidates(observation).stream().filter(a -> a.kind() == kind)
                    .min(Comparator.comparing(PlanningAction::until)).orElseThrow();
            actual = SecurityPolicyController.executeAction(actual, observation, action, OperationOutcomes.nominal());
        }
        return actual;
    }

    /** Construct one feasible terminal plan by common execution, without invoking a search. */
    private HorizonPlanResult terminalIncumbent(PlanningObservation observation) {
        var branches = new BranchEngine();
        var branch = branches.create(observation);
        int steps = 0;
        var kinds = List.of(CONTINUE, INSTALL, SWITCH, OPEN, REUSE, PREPARE, WAIT);
        while (branch.snapshot().at().isBefore(observation.evaluationEnd())) {
            assertThat(++steps).isLessThan(40);
            var action = new SecurityActionGenerator().candidates(branch.observation()).stream()
                    .min(Comparator.comparingInt((PlanningAction a) -> kinds.indexOf(a.kind()))
                            .thenComparing(PlanningAction::until)).orElseThrow();
            branch = branches.execute(branch, action);
        }
        var counter = new SearchDiagnostics.Counter(); counter.stopReason = SearchDiagnostics.StopReason.NODE_LIMIT;
        return new HorizonPlanResult(branch.plan(), new ContinuationEvaluator().evaluate(branch).evaluation(),
                branch.plan().isEmpty() ? null : branch.plan().getFirst(), HorizonPlanResult.Status.INCOMPLETE,
                1, 1, counter.snapshot());
    }

    @SuppressWarnings("unchecked")
    private static <T extends Record> T changed(T value, String field, Object replacement) throws Exception {
        var components = value.getClass().getRecordComponents();
        var types = new Class<?>[components.length];
        var arguments = new Object[components.length];
        for (int i = 0; i < components.length; i++) {
            types[i] = components[i].getType();
            arguments[i] = components[i].getName().equals(field) ? replacement : components[i].getAccessor().invoke(value);
        }
        return (T) value.getClass().getDeclaredConstructor(types).newInstance(arguments);
    }
}
