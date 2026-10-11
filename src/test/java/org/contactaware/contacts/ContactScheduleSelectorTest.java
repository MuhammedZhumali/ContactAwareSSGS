package org.contactaware.contacts;

import org.contactaware.contacts.Model.*;
import org.contactaware.contacts.schedule.*;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class ContactScheduleSelectorTest {
    static final Instant START = Instant.parse("2026-10-10T00:00:00Z");
    static final Interval PERIOD = new Interval(START, START.plusSeconds(86400));
    static ContactWindow c(String id, String gs, long start, long end) {
        return new ContactWindow(id, "NORAD-25544", gs, START.plusSeconds(start), START.plusSeconds(end), null, null, List.of());
    }
    static ScheduleSelectionResult select(List<ContactWindow> contacts, String guard) {
        return new ContactScheduleSelector().select(contacts, "NORAD-25544", PERIOD, new ScheduleSelectionConfig(new BigDecimal(guard)));
    }
    static List<String> ids(ScheduleSelectionResult result) { return result.contacts().stream().map(ContactWindow::id).toList(); }

    @Test void emptySingletonAndNonoverlappingWindows() {
        assertThat(select(List.of(), "0").metrics().totalSelectedDurationSeconds()).isEqualByComparingTo("0");
        assertThat(ids(select(List.of(c("1", "A", 1, 4)), "0"))).containsExactly("1");
        var result = select(List.of(c("2", "B", 6, 8), c("1", "A", 1, 4)), "0");
        assertThat(ids(result)).containsExactly("1", "2"); assertThat(result.metrics().verification().valid()).isTrue();
    }

    @Test void globalOptimumBeatsLocallyLongestAndEarliestFinishing() {
        var result = select(List.of(c("middle", "B", 3, 13), c("first", "A", 0, 8), c("last", "A", 8, 16), c("tiny", "A", 0, 1)), "0");
        assertThat(ids(result)).containsExactly("first", "last");
        assertThat(result.metrics().totalSelectedDurationSeconds()).isEqualByComparingTo("16");
    }

    @Test void nestingAndAllOverlapPairsIncludingNonadjacent() {
        var result = select(List.of(c("outer", "A", 0, 100), c("inner1", "B", 10, 20), c("inner2", "B", 30, 40)), "0");
        assertThat(ids(result)).containsExactly("outer"); assertThat(result.metrics().overlappingCandidatePairs()).isEqualTo(2);
        assertThat(result.metrics().excludedContactIds()).containsExactly("inner1", "inner2");
    }

    @Test void touchingWindowsAndStationDependentGuard() {
        var contacts = List.of(c("a", "A", 0, 10), c("b", "B", 10, 20), c("c", "A", 10, 20), c("d", "B", 25, 35));
        assertThat(ids(select(contacts, "0"))).containsExactly("a", "b", "d");
        assertThat(ids(select(contacts, "5"))).containsExactly("a", "c", "d");
        assertThat(ids(select(contacts, "6"))).containsExactly("a", "c");
        assertThat(ids(select(List.of(c("a", "A", 0, 10), c("b", "A", 10, 20)), "60"))).containsExactly("a", "b");
        assertThat(select(List.of(c("a", "A", 0, 10), c("b", "B", 40, 50)), "30").contacts()).hasSize(2);
        assertThat(select(List.of(c("a", "A", 0, 10), c("b", "B", 40, 50)), "30.000000001").contacts()).hasSize(1);
    }

    @Test void fewerSwitchesThenTimeAndIdDetermineEqualOptima() {
        var contacts = List.of(c("early-id", "A", 0, 10), c("later-id", "B", 0, 10), c("tail", "B", 10, 20));
        assertThat(ids(select(contacts, "0"))).containsExactly("later-id", "tail");
        assertThat(ids(select(List.of(c("z", "B", 0, 10), c("a", "A", 0, 10)), "0"))).containsExactly("a");
        assertThat(ids(select(List.of(c("a", "A", 1, 11), c("z", "A", 0, 10)), "0"))).containsExactly("z");
    }

    @Test void exactNanosecondDurationWinsBeforeTieBreaks() {
        var shorter = c("a", "A", 0, 10);
        var longer = new ContactWindow("z", "NORAD-25544", "B", START, START.plusSeconds(10).plusNanos(1), null, null, List.of());
        assertThat(ids(select(List.of(shorter, longer), "0"))).containsExactly("z");
    }

    @Test void rejectsMalformedInputsAndInvalidSchedules() {
        assertThatThrownBy(() -> c("a", "A", 2, 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> c("", "A", 1, 2)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> c("a", " ", 1, 2)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> select(List.of(c("same", "A", 0, 1), c("same", "B", 2, 3)), "0")).hasMessageContaining("Duplicate");
        assertThatThrownBy(() -> select(List.of(c("a", "A", -1, 1)), "0")).hasMessageContaining("outside");
        var wrongSat = new ContactWindow("a", "NORAD-1", "A", START, START.plusSeconds(1), null, null, List.of());
        assertThatThrownBy(() -> select(List.of(wrongSat), "0")).hasMessageContaining("satellite");
        for (String guard : List.of("-1", "0.0000000001", "NaN")) assertThatThrownBy(() -> select(List.of(), guard)).isInstanceOf(RuntimeException.class);
        var a = c("a", "A", 0, 10); var b = c("b", "B", 5, 15);
        assertThat(ScheduleValidator.validate(List.of(a, b), List.of(a, b), "NORAD-25544", PERIOD, ScheduleSelectionConfig.DEFAULT).valid()).isFalse();
        assertThat(ScheduleValidator.validate(List.of(c("a", "A", 0, 8)), List.of(a), "NORAD-25544", PERIOD, ScheduleSelectionConfig.DEFAULT).valid()).isFalse();
    }

    @Test void fixedSeedExhaustiveOracleValidatesDurationSwitchesAndDeterministicTieOrder() {
        Random random = new Random(91731);
        for (int trial = 0; trial < 250; trial++) {
            List<ContactWindow> contacts = new ArrayList<>(); int n = random.nextInt(10); int guard = random.nextInt(5);
            for (int i = 0; i < n; i++) { int start = random.nextInt(25); contacts.add(c("c" + i, "GS" + random.nextInt(3), start, start + 1 + random.nextInt(10))); }
            var expected = exhaustive(contacts, guard); var result = select(contacts, "" + guard);
            assertThat(result.contacts()).as("trial %s guard %s", trial, guard).isEqualTo(expected);
            Collections.shuffle(contacts, random);
            assertThat(select(contacts, "" + guard)).isEqualTo(result);
        }
    }

    // Independent enumeration of every subset; does not call optimizer feasibility/scoring helpers.
    private static List<ContactWindow> exhaustive(List<ContactWindow> candidates, int guard) {
        List<ContactWindow> best = List.of(); long bestDuration = 0; int bestChanges = 0;
        var order = Comparator.comparing(ContactWindow::start).thenComparing(ContactWindow::end).thenComparing(ContactWindow::id);
        for (int mask = 0; mask < (1 << candidates.size()); mask++) {
            List<ContactWindow> subset = new ArrayList<>();
            for (int i = 0; i < candidates.size(); i++) if ((mask & (1 << i)) != 0) subset.add(candidates.get(i));
            subset.sort(order); boolean feasible = true; int changes = 0; long duration = 0;
            for (int i = 0; i < subset.size(); i++) {
                var c = subset.get(i); duration += Duration.between(c.start(), c.end()).toNanos();
                if (i == 0) continue; var prev = subset.get(i - 1); boolean changed = !prev.groundStationId().equals(c.groundStationId());
                if (changed) changes++;
                if (c.start().isBefore(prev.end().plusSeconds(changed ? guard : 0))) feasible = false;
            }
            int lex = 0;
            for (int i = 0; i < Math.min(best.size(), subset.size()) && lex == 0; i++) lex = order.compare(subset.get(i), best.get(i));
            if (lex == 0) lex = Integer.compare(subset.size(), best.size());
            if (feasible && (duration > bestDuration || duration == bestDuration && (changes < bestChanges || changes == bestChanges && lex < 0))) {
                best = List.copyOf(subset); bestDuration = duration; bestChanges = changes;
            }
        }
        return best;
    }
}
