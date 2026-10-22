package org.contactaware.security.simulation;

import org.contactaware.security.*;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.contactaware.security.simulation.HorizonWitness.*;
import static org.contactaware.security.simulation.PlanningAction.Kind.*;
import static org.contactaware.security.simulation.PlanningResultComparator.Criterion.*;

class PlanningResultComparatorTest {
    private final PlanningResultComparator comparator = new PlanningResultComparator();

    @Test void aServedContactOutweighsUsefulTimeAndAllCostsAsInTheXWitness() {
        var next = result(2, Duration.ofSeconds(38), Duration.ofMillis(320), 160, 2);
        var multi = result(3, Duration.ofSeconds(37), Duration.ofMillis(830), 1240, 2);
        assertThat(comparator.compare(multi, next)).isNegative();
        assertThat(comparator.decisiveCriterion(multi, next)).isEqualTo(SERVED_CONTACTS);
    }

    @Test void allFivePrioritiesAreStrictAndMeasuredAtNanosecondPrecision() {
        var base = result(2, Duration.ofSeconds(10), Duration.ofSeconds(1), 100, 2);
        var moreUseful = result(2, Duration.ofSeconds(10).plusNanos(1), Duration.ofSeconds(10), 1000, 20);
        var lessCpu = result(2, Duration.ofSeconds(10), Duration.ofSeconds(1).minusNanos(1), 1000, 20);
        var lessBytes = result(2, Duration.ofSeconds(10), Duration.ofSeconds(1), 99, 20);
        var lessKeys = result(2, Duration.ofSeconds(10), Duration.ofSeconds(1), 100, 1);
        var choices = List.of(moreUseful, lessCpu, lessBytes, lessKeys);
        var reasons = List.of(USEFUL_TIME, CONTROL_CPU, MANAGEMENT_BYTES, NEW_KEYS);
        for (int i = 0; i < choices.size(); i++) {
            assertThat(comparator.compare(choices.get(i), base)).isNegative();
            assertThat(comparator.compare(base, choices.get(i))).isPositive();
            assertThat(comparator.decisiveCriterion(choices.get(i), base)).isEqualTo(reasons.get(i));
        }
        assertThat(comparator.compare(base, base)).isZero();
        assertThat(comparator.decisiveCriterion(base, base)).isEqualTo(EQUAL);
    }

    @Test void metricsNeverOverflowBySubtractionNegationOrNanosecondConversion() {
        var huge = result(Long.MAX_VALUE, Duration.ofSeconds(Long.MAX_VALUE), Duration.ofSeconds(Long.MAX_VALUE),
                Long.MAX_VALUE, Long.MAX_VALUE);
        var small = result(0, Duration.ZERO, Duration.ZERO, 0, 0);
        assertThat(comparator.compare(huge, small)).isNegative();
        var justSmallerCpu = result(Long.MAX_VALUE, huge.evaluation().usefulSecureTime(),
                Duration.ofSeconds(Long.MAX_VALUE).minusNanos(1), Long.MAX_VALUE, Long.MAX_VALUE);
        assertThat(comparator.compare(justSmallerCpu, huge)).isNegative();
        assertThat(comparator.decisiveCriterion(justSmallerCpu, huge)).isEqualTo(CONTROL_CPU);
    }

    @Test void canonicalActionOrderIsSharedAndIndependentOfEnumerationOrder() {
        var values = new ArrayList<PlanningResult>();
        for (var kind : PlanningAction.Kind.values()) values.add(withPlan(action(kind, at(0), at(1), A, "A-candidate")));
        var expected = List.copyOf(values);
        for (int seed = 0; seed < 25; seed++) {
            Collections.shuffle(values, new Random(seed));
            values.sort(comparator);
            assertThat(values).containsExactlyElementsOf(expected);
        }
        assertThat(comparator.decisiveCriterion(values.get(0), values.get(1))).isEqualTo(CANONICAL_PLAN);
    }

    @Test void canonicalTupleOrdersStartStationContextKeyAndWaitTargetWithoutGlobalState() {
        var first = action(PREPARE, at(0), at(2), A, "key-1");
        assertThat(comparator.compareActions(first, action(PREPARE, at(1), at(3), A, "key-1"))).isNegative();
        assertThat(comparator.compareActions(first, action(PREPARE, at(0), at(2), B, "key-1"))).isNegative();
        assertThat(comparator.compareActions(first,
                action(PREPARE, at(0), at(2), new ContextId("ZZ-SC", "A"), "key-1"))).isNegative();
        assertThat(comparator.compareActions(first, action(PREPARE, at(0), at(2), A, "key-2"))).isNegative();
        assertThat(comparator.compareActions(action(WAIT, at(0), at(1), A, "ignored"),
                action(WAIT, at(0), at(2), A, "ignored"))).isNegative();
        var sameUntil = new PlanningAction(INSTALL, at(0), at(2), A, NEW_A, "C1");
        var otherContact = new PlanningAction(INSTALL, at(0), at(2), A, NEW_A, "C2");
        assertThat(comparator.compareActions(sameUntil, otherContact)).isNegative();
        assertThat(comparator.comparePlans(List.of(first), List.of(first, action(WAIT, at(2), at(3), A, "ignored")))).isNegative();
    }

    @Test void resultPlansAreDefensivelyCopiedAndNegativeProgressIsRejected() {
        var plan = new ArrayList<>(List.of(action(WAIT, at(0), at(1), A, "ignored")));
        var result = new PlanningResult(new BranchEvaluation(0, Duration.ZERO, Duration.ZERO, 0, 0), plan);
        plan.clear();
        assertThat(result.plan()).hasSize(1);
        assertThatThrownBy(() -> result.plan().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> new BranchEvaluation(-1, Duration.ZERO, Duration.ZERO, 0, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new BranchEvaluation(0, Duration.ZERO, Duration.ofNanos(-1), 0, 0)).isInstanceOf(IllegalArgumentException.class);
    }

    private PlanningResult result(long served, Duration useful, Duration cpu, long bytes, long keys) {
        return new PlanningResult(new BranchEvaluation(served, useful, cpu, bytes, keys), List.of());
    }

    private PlanningResult withPlan(PlanningAction action) {
        return new PlanningResult(new BranchEvaluation(0, Duration.ZERO, Duration.ZERO, 0, 0), List.of(action));
    }

    private PlanningAction action(PlanningAction.Kind kind, Instant at, Instant until, ContextId context, String key) {
        var keys = kind == PREPARE || kind == INSTALL || kind == SWITCH ? new KeyPairIds(key + "-up", key + "-down") : null;
        return new PlanningAction(kind, at, until, kind == WAIT ? null : context, keys,
                kind == PREPARE || kind == WAIT ? null : "C1");
    }
}
