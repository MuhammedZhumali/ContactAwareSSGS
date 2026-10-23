package org.contactaware.security.simulation;

import org.contactaware.security.SecurityAction;
import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.*;
import static org.assertj.core.api.Assertions.*;
import static org.contactaware.security.simulation.HorizonWitness.*;
import static org.contactaware.security.simulation.ControlledSimulationResult.DecisionSource.*;
import static org.contactaware.security.simulation.PlanningAction.Kind.*;

class ContinuationReuseControllerTest {
    private ExactHorizonPlanner exact(PlanTieBreak rule) {
        return new ExactHorizonPlanner(new SearchLimits(250_000, Duration.ofSeconds(30)), PlannerOptions.reference(),
                DecisionTimeModel.V2_EVENT_BOUNDARIES, rule);
    }
    private SecurityPolicy policy(HorizonPlanner planner, AtomicInteger horizon) {
        return new SecurityPolicy() {
            public PolicyConfiguration configuration() { return horizon.get() == 1 ? HorizonPolicy.next() : HorizonPolicy.multi(horizon.get()); }
            public ContinuationReuseGuard.Rules continuationRules() { return planner.continuationRules(); }
            public PolicySelection select(PlanningObservation o, List<PlanningAction> candidates) {
                return planner.selectUnrestricted(o, "test unrestricted selection");
            }
        };
    }

    @Test void bothRulesAndHorizonsPreserveCompletePlansAndConsecutiveProofLinks() {
        var initial = warm(new RecedingHorizonControllerTest().keyed(6, List.of()), List.of(OPEN, WAIT));
        for (var rule : PlanTieBreak.values()) for (var policy : List.of(HorizonPolicy.next(), HorizonPolicy.multi(2))) {
            var off = new RecedingHorizonController(policy, exact(rule)).run(initial);
            var on = new RecedingHorizonController(policy, exact(rule), true).run(initial);
            equivalent(off, on);
            var reused = on.decisions().stream().filter(d -> d.source() == SUFFIX_REUSE).toList();
            assertThat(reused).hasSizeGreaterThanOrEqualTo(3);
            assertThat(reused).allMatch(d -> d.search() == null && d.continuation().rootProofReference().equals("decision:0"));
            assertThat(reused.stream().map(d -> d.continuation().suffixOffset()).toList()).startsWith(1, 2, 3);
            for (int i = 0; i < on.decisions().size(); i++) {
                var d = on.decisions().get(i);
                if (d.source() != SUFFIX_REUSE) continue;
                var root = on.decisions().get(Integer.parseInt(d.continuation().rootProofReference().split(":")[1])).search();
                assertThat(root.status()).isEqualTo(HorizonPlanResult.Status.COMPLETE);
                assertThat(off.decisions().get(i).search().bestPlan())
                        .containsExactlyElementsOf(root.bestPlan().subList(d.continuation().suffixOffset(), root.bestPlan().size()));
                assertThat(d.continuation().checkedOffset()).isEqualTo(d.continuation().suffixOffset() - 1);
            }
            assertThat(on.computation().searches()).isLessThan(off.computation().searches());
            assertThat(on.computation().preparationNanos()).isEqualTo(on.decisions().stream()
                    .map(ControlledSimulationResult.Decision::continuation).filter(Objects::nonNull)
                    .mapToLong(ControlledSimulationResult.ContinuationAudit::preparationNanos).sum());
        }
    }

    @Test void preparedAndGeneratedKeyFixturesPreserveEventsCostsCountersAndIds() {
        var fixtures = List.of(
                warm(new ContinueSuffixOptimalityTest().fixture(), List.of(OPEN, WAIT, CONTINUE)),
                warm(new ContinueSuffixPrepareOptimalityTest().fixture(), List.of(OPEN, WAIT)));
        for (var initial : fixtures) for (var rule : PlanTieBreak.values()) {
            var off = new RecedingHorizonController(HorizonPolicy.multi(2), exact(rule)).run(initial);
            var on = new RecedingHorizonController(HorizonPolicy.multi(2), exact(rule), true).run(initial);
            equivalent(off, on);
        }
    }

    @Test void horizonChangeFallsBackAndIncompleteIncumbentIsNeverExecuted() {
        var initial = warm(new RecedingHorizonControllerTest().keyed(6, List.of()), List.of(OPEN, WAIT));
        var solver = exact(PlanTieBreak.CONTINUE_WAIT);
        var horizon = new AtomicInteger(1);
        var policy = new SecurityPolicy() {
            public PolicyConfiguration configuration() { return horizon.get() == 1 ? HorizonPolicy.next() : HorizonPolicy.multi(horizon.get()); }
            public ContinuationReuseGuard.Rules continuationRules() { return solver.continuationRules(); }
            public PolicySelection select(PlanningObservation o, List<PlanningAction> candidates) {
                var selection = solver.selectUnrestricted(o, "test");
                if (o.at().equals(at(2))) {
                    var r = selection.search();
                    return new PolicySelection(null, new HorizonPlanResult(r.bestPlan(), r.score(), r.firstAction(),
                            HorizonPlanResult.Status.INCOMPLETE, r.exploredNodes(), r.elapsedNanos(), r.diagnostics()), "injected incomplete");
                }
                return selection;
            }
        };
        var stopped = new SecurityPolicyController(policy, Duration.ZERO,
                (s, n) -> { if (s.at().equals(at(2))) horizon.set(2); }, true).run(initial);
        assertThat(stopped.status()).isEqualTo(ControlledSimulationResult.Status.SEARCH_INCOMPLETE);
        var last = stopped.decisions().getLast();
        assertThat(last.source()).isEqualTo(SEARCH);
        assertThat(last.executedAction()).isNull();
        assertThat(last.continuation().guard().reason()).contains("HORIZON_CHANGED");
        assertThat(stopped.snapshot().at()).isEqualTo(at(2));
        assertThat(stopped.snapshot().journal().stream().filter(e -> e.type() == SimulationEvent.Type.PAYLOAD_TRANSFERRED)).hasSize(1);
        var resumed = new RecedingHorizonController(HorizonPolicy.multi(2), solver, true).run(stopped.snapshot());
        assertThat(resumed.decisions().getFirst().source()).isEqualTo(SEARCH);
        assertThat(resumed.simulation()).isEqualTo(new RecedingHorizonController(HorizonPolicy.multi(2), solver).run(initial).simulation());
    }

    @Test void unexpectedNotificationTakesPrecedenceAndHiddenDisclosureDoesNotInvalidateReuse() {
        var base = new RecedingHorizonControllerTest().keyed(6, List.of());
        var incident = new TrafficKeyDisclosure("small-notice", A, at(2).plusMillis(250), at(3));
        var scenario = new SimulationScenario(base.name(), base.start(), base.end(), base.initialState(), base.constraints(),
                base.costs(), base.contacts(), base.plan(), List.of(incident));
        var initial = warm(scenario, List.of(OPEN, WAIT));
        var off = new RecedingHorizonController(HorizonPolicy.multi(2), exact(PlanTieBreak.CONTINUE_WAIT)).run(initial);
        var on = new RecedingHorizonController(HorizonPolicy.multi(2), exact(PlanTieBreak.CONTINUE_WAIT), true).run(initial);
        equivalent(off, on);
        var notice = on.decisions().stream().filter(d -> d.at().equals(at(3))).findFirst().orElseThrow();
        assertThat(notice.source()).isEqualTo(RULE);
        assertThat(notice.continuation().guard().reason()).contains("EMERGENCY_RESPONSE");
        assertThat(on.decisions().stream().filter(d -> d.at().isBefore(at(3))))
                .anyMatch(d -> d.source() == SUFFIX_REUSE);
        var nominal = new RecedingHorizonController(HorizonPolicy.multi(2), exact(PlanTieBreak.CONTINUE_WAIT), true)
                .run(warm(base, List.of(OPEN, WAIT)));
        assertThat(on.decisions().stream().filter(d -> d.at().isBefore(at(3))).map(d -> List.of(d.at(), d.source(), d.executedAction())).toList())
                .isEqualTo(nominal.decisions().stream().filter(d -> d.at().isBefore(at(3))).map(d -> List.of(d.at(), d.source(), d.executedAction())).toList());
    }

    @Test void missingAcknowledgementAndCommittedWorkCannotReuseAnOldSuffix() {
        var initial = warm(new ContinueSuffixPrepareOptimalityTest().fixture(), List.of(OPEN, WAIT));
        OperationOutcomes missingAck = op -> new OperationOutcomes.Outcome(null,
                op.intent().action() == SecurityAction.INSTALL ? Set.of(SecurityAction.INSTALL) : Set.of());
        var off = new RecedingHorizonController(HorizonPolicy.multi(2), exact(PlanTieBreak.CONTINUE_WAIT)).run(initial, missingAck);
        var on = new RecedingHorizonController(HorizonPolicy.multi(2), exact(PlanTieBreak.CONTINUE_WAIT), true).run(initial, missingAck);
        equivalent(off, on);
        assertThat(on.snapshot().journal()).anyMatch(e -> e.type() == SimulationEvent.Type.STEP_FAILED);
        assertThat(on.decisions().stream().filter(d -> !d.observation().ongoingOperations().isEmpty()))
                .allMatch(d -> d.source() != SUFFIX_REUSE);
        assertThat(on.decisions().stream().filter(d -> d.at().equals(at(4))))
                .allMatch(d -> d.source() != SUFFIX_REUSE);
    }

    @Test void constrainedAdapterCannotMintAnUnrestrictedProofByForwardingRules() {
        var exact = exact(PlanTieBreak.CONTINUE_WAIT);
        HorizonPlanner adapter = new HorizonPlanner() {
            public HorizonPlanResult plan(PlanningObservation o) {
                var first = new SecurityActionGenerator().candidates(o).stream().filter(a -> a.kind() == WAIT).findFirst().orElseThrow();
                return exact.planWithFirstAction(o, first);
            }
            public ContinuationReuseGuard.Rules continuationRules() { return exact.continuationRules(); }
        };
        var result = new RecedingHorizonController(HorizonPolicy.multi(2), adapter, true)
                .run(warm(new RecedingHorizonControllerTest().keyed(6, List.of()), List.of(OPEN, WAIT)));
        assertThat(result.decisions()).noneMatch(d -> d.source() == SUFFIX_REUSE);
        assertThat(result.decisions()).allMatch(d -> d.continuation().rootProofReference() == null);
    }

    @Test void controllerBudgetCoversGuardAndPreparationAndPreservesLastCommittedSnapshot() {
        var initial = warm(new RecedingHorizonControllerTest().keyed(6, List.of()), List.of(OPEN, WAIT));
        for (var phase : List.of("controller.reuseGuard", "controller.reusePreparation")) {
            var clock = new AtomicLong(); var seen = new AtomicInteger();
            var saved = new AtomicReference<ExecutionSnapshot>();
            ControlledSimulationResult stopped;
            try (var outer = SearchControl.open(Duration.ofNanos(1), SearchDiagnostics.StopReason.CONTROLLER_TIME_LIMIT,
                    clock::get, p -> { if (p.equals(phase) && seen.incrementAndGet() == 2) clock.set(1); })) {
                stopped = new SecurityPolicyController(policy(exact(PlanTieBreak.CONTINUE_WAIT), new AtomicInteger(2)), Duration.ZERO,
                        (s, n) -> saved.set(s), true).run(initial);
            }
            assertThat(stopped.status()).isEqualTo(ControlledSimulationResult.Status.CONTROLLER_TIME_LIMIT);
            assertThat(stopped.snapshot()).isEqualTo(saved.get());
            assertThat(stopped.snapshot().at()).isEqualTo(at(2));
            assertThat(stopped.decisions()).hasSize(1);
            assertThat(stopped.computation().guardNanos()).isPositive();
            var resumed = new RecedingHorizonController(HorizonPolicy.multi(2), exact(PlanTieBreak.CONTINUE_WAIT), true).run(stopped.snapshot());
            assertThat(resumed.decisions().getFirst().source()).isEqualTo(SEARCH);
            assertThat(resumed.simulation()).isEqualTo(new RecedingHorizonController(HorizonPolicy.multi(2), exact(PlanTieBreak.CONTINUE_WAIT)).run(initial).simulation());
        }
    }

    private ExecutionSnapshot warm(SimulationScenario scenario, List<PlanningAction.Kind> actions) {
        var engine = new SimulationEngine(); var generator = new SecurityActionGenerator();
        var snapshot = engine.controlledSnapshot(engine.start(scenario));
        for (var kind : actions) {
            var o = PlanningObservation.from(snapshot, 2);
            var action = generator.candidates(o).stream().filter(a -> a.kind() == kind)
                    .min(Comparator.comparing(PlanningAction::until)).orElseThrow();
            snapshot = SecurityPolicyController.executeAction(snapshot, o, action, OperationOutcomes.nominal());
        }
        return snapshot;
    }

    private void equivalent(ControlledSimulationResult off, ControlledSimulationResult on) {
        assertThat(off.status()).isEqualTo(ControlledSimulationResult.Status.FINISHED);
        assertThat(on.status()).isEqualTo(off.status());
        assertThat(on.simulation()).isEqualTo(off.simulation());
        assertThat(on.snapshot()).isEqualTo(off.snapshot());
        assertThat(on.decisions()).extracting(ControlledSimulationResult.Decision::executedAction)
                .containsExactlyElementsOf(off.decisions().stream().map(ControlledSimulationResult.Decision::executedAction).toList());
        assertThat(on.decisions()).extracting(ControlledSimulationResult.Decision::resumedAt)
                .containsExactlyElementsOf(off.decisions().stream().map(ControlledSimulationResult.Decision::resumedAt).toList());
        assertThat(off.decisions().stream().filter(d -> d.search() != null))
                .allMatch(d -> d.search().status() == HorizonPlanResult.Status.COMPLETE);
    }
}
