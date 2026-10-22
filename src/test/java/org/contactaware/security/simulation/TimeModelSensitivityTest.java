package org.contactaware.security.simulation;

import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.contactaware.security.simulation.HorizonWitness.*;

class TimeModelSensitivityTest {
    @Test void costFreeWaitRemovesTheCounterexampleNeedForUnrelatedPreparation() {
        var root = TimeModelSensitivityMain.counterexample();
        var oldFull = TimeModelSensitivityMain.enumerate(root, DecisionTimeModel.V2_EVENT_BOUNDARIES, false);
        var oldFiltered = TimeModelSensitivityMain.enumerate(root, DecisionTimeModel.V2_EVENT_BOUNDARIES, true);
        var newFull = TimeModelSensitivityMain.enumerate(root, DecisionTimeModel.PREPARE_DURATION_WAIT, false);
        var newFiltered = TimeModelSensitivityMain.enumerate(root, DecisionTimeModel.PREPARE_DURATION_WAIT, true);
        assertThat(oldFull.result().evaluation()).isEqualTo(new BranchEvaluation(2, Duration.ofSeconds(4), Duration.ofSeconds(7), 0, 2));
        assertThat(oldFiltered.result().evaluation()).isEqualTo(new BranchEvaluation(1, Duration.ofSeconds(7), Duration.ofSeconds(2), 0, 0));
        assertThat(newFull.result().evaluation()).isEqualTo(new BranchEvaluation(2, Duration.ofSeconds(4), Duration.ofSeconds(3), 0, 0));
        assertThat(newFull.result()).isEqualTo(newFiltered.result());
        assertThat(oldFull.result().plan()).anyMatch(a -> a.kind() == PlanningAction.Kind.PREPARE && B.equals(a.contextId()));
        assertThat(newFull.result().plan()).noneMatch(a -> a.kind() == PlanningAction.Kind.PREPARE);
        assertThat(newFull.result().plan().getFirst()).isEqualTo(new PlanningAction(PlanningAction.Kind.WAIT, at(0), at(4), null, null, null));
        assertThat(newFull.result().plan()).anyMatch(a -> a.kind() == PlanningAction.Kind.SWITCH && a.at().equals(at(4)));
        // Accelerated search over the enlarged timing space agrees with an independent complete enumeration.
        var accelerated = new ExactHorizonPlanner(SearchLimits.unlimited(), PlannerOptions.accelerated(), DecisionTimeModel.PREPARE_DURATION_WAIT).plan(root);
        assertThat(accelerated.status()).isEqualTo(HorizonPlanResult.Status.COMPLETE);
        assertThat(new PlanningResult(accelerated.score(), accelerated.bestPlan())).isEqualTo(newFull.result());
    }

    @Test void experimentalTimersUseOnlyVisibleObservationAndDefaultRemainsIdentity() {
        var x = HorizonPlannerMain.observation(Sequence.X, 1);
        var y = HorizonPlannerMain.observation(Sequence.Y, 1);
        assertThat(x).isEqualTo(y);
        assertThat(DecisionTimeModel.V2_EVENT_BOUNDARIES.apply(x)).isSameAs(x);
        assertThat(DecisionTimeModel.PREPARE_DURATION_WAIT.apply(x)).isEqualTo(DecisionTimeModel.PREPARE_DURATION_WAIT.apply(y));
        assertThat(DecisionTimeModel.PREPARE_DURATION_WAIT.additionalWakeUps(x)).containsExactly(at(2));
        var original = new SecurityActionGenerator().candidates(x);
        var changed = new SecurityActionGenerator().candidates(DecisionTimeModel.PREPARE_DURATION_WAIT.apply(x));
        assertThat(changed).containsAll(original);
        var regular = new ExactHorizonPlanner().plan(x);
        var explicitV2 = new ExactHorizonPlanner(SearchLimits.unlimited(), PlannerOptions.accelerated(), DecisionTimeModel.V2_EVENT_BOUNDARIES).plan(x);
        assertThat(regular.bestPlan()).isEqualTo(explicitV2.bestPlan());
        assertThat(regular.score()).isEqualTo(explicitV2.score());
        assertThat(regular.exploredNodes()).isEqualTo(explicitV2.exploredNodes());
    }

    @Test void counterexampleForecastReplaysThroughActualControllerWithIdenticalTimerRule() {
        var root = TimeModelSensitivityMain.counterexample();
        var scenario = new SimulationScenario("counterexample-controller", root.at(), root.evaluationEnd(),
                root.securityState(), root.constraints(), root.costs(), root.visibleContacts(), List.of());
        var actual = TimeModelSensitivityMain.controller(HorizonPolicy.next(), DecisionTimeModel.PREPARE_DURATION_WAIT,
                new SearchLimits(100_000, Duration.ofSeconds(10))).run(scenario);
        assertThat(actual.status()).isEqualTo(ControlledSimulationResult.Status.FINISHED);
        assertThat(actual.simulation().metrics().servedContacts()).isEqualTo(2);
        assertThat(actual.simulation().metrics().controlCosts().totalCpu()).isEqualTo(Duration.ofSeconds(3));
        assertThat(actual.simulation().metrics().createdKeys()).isZero();
        assertThat(actual.decisions().getFirst().executedAction()).isEqualTo(
                new PlanningAction(PlanningAction.Kind.WAIT, at(0), at(4), null, null, null));
        assertThat(actual.decisions()).allMatch(d -> d.search().status() == HorizonPlanResult.Status.COMPLETE);
    }
}
