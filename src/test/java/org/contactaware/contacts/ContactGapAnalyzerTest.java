package org.contactaware.contacts;

import org.contactaware.contacts.analysis.*;
import org.contactaware.contacts.Model.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.contactaware.contacts.ContactScheduleSelectorTest.*;
import static org.contactaware.contacts.ContactScheduleSelectorTest.PERIOD;

class ContactGapAnalyzerTest {
    private ContactGapAnalyzer.Result analyze(List<ContactWindow> contacts, boolean selected) {
        return new ContactGapAnalyzer().analyze(contacts, "NORAD-25544", PERIOD, List.of("A", "B", "unused"), selected, 300);
    }

    @Test void recurrenceAfterInterveningStationUsesPreviousSameGsEnd() {
        var stats = analyze(List.of(c("a1", "A", 36000, 36480), c("b", "B", 37200, 37620), c("a2", "A", 39600, 40080)), true);
        assertThat(stats.sameGroundStationRecurrence().pairs().getFirst().gapSeconds()).isEqualByComparingTo("3120");
        assertThat(stats.adjacentGapSeconds().mean()).isEqualTo(1350);
        assertThat(stats.chronologicalGroundStationChanges()).isEqualTo(2);
        assertThat(stats.adjacentSameGroundStationPairs()).isZero();
        assertThat(stats.sameGroundStationRecurrence().temporalReuseEligibility().get("3600").count()).isEqualTo(1);
        assertThat(stats.contactsByGroundStation()).containsEntry("unused", 0);
    }

    @Test void exactExclusiveBucketBoundariesAndTemporalDenominator() {
        int[] gaps = {0, 1800, 1801, 3600, 3601, 7200, 7201, 21600, 21601};
        List<ContactWindow> contacts = new ArrayList<>();
        for (int i = 0; i < gaps.length; i++) { contacts.add(c("p" + i, "G" + i, 0, 1)); contacts.add(c("n" + i, "G" + i, 1 + gaps[i], 2 + gaps[i])); }
        var r = new GroundStationRecurrenceAnalyzer().analyze(contacts);
        assertThat(r.buckets().values().stream().map(GroundStationRecurrenceAnalyzer.CountFraction::count)).containsExactly(2, 2, 2, 2, 1);
        assertThat(r.temporalReuseEligibility().get("1800").count()).isEqualTo(2);
        assertThat(r.temporalReuseEligibility().get("3600").count()).isEqualTo(4);
        assertThat(r.temporalReuseEligibility().get("7200").count()).isEqualTo(6);
        assertThat(r.temporalReuseEligibility().get("21600").fraction()).isEqualTo(8.0 / 9);
    }

    @Test void overlapsNeverBecomeReuseCandidatesAndUnionIncludesPeriodEdges() {
        var stats = analyze(List.of(c("outer", "A", 100, 1000), c("inner", "A", 200, 300), c("other", "B", 400, 500)), false);
        assertThat(stats.sumContactDurationSeconds()).isEqualByComparingTo("1100");
        assertThat(stats.unionAvailableSeconds()).isEqualByComparingTo("900");
        assertThat(stats.timeWithoutAnyContactSeconds()).isEqualByComparingTo("85500");
        assertThat(stats.scheduledContactDurationSeconds()).isNull(); assertThat(stats.timeWithoutSelectedContactSeconds()).isNull();
        assertThat(stats.uncoveredIntervalsIncludingPeriodBoundaries()).containsExactly(new Interval(START, START.plusSeconds(100)), new Interval(START.plusSeconds(1000), PERIOD.end()));
        assertThat(stats.sameGroundStationRecurrence().overlappingPairCount()).isEqualTo(1);
        assertThat(stats.sameGroundStationRecurrence().temporalReuseEligibility().values()).allMatch(v -> v.count() == 0 && v.fraction() == 0);
        assertThatThrownBy(() -> analyze(List.of(c("a", "A", 0, 10), c("b", "B", 5, 20)), true)).hasMessageContaining("Invalid selected");
    }

    @Test void emptySingletonAndStrictShortThreshold() {
        var empty = analyze(List.of(), true);
        assertThat(empty.adjacentGapSeconds().mean()).isNull(); assertThat(empty.shortContactFraction()).isNull();
        assertThat(empty.timeWithoutSelectedContactSeconds()).isEqualByComparingTo("86400");
        assertThat(empty.sameGroundStationRecurrence().temporalReuseEligibility().get("1800").fraction()).isNull();
        var one = analyze(List.of(c("a", "A", 100, 400)), true);
        assertThat(one.adjacentGapSeconds().count()).isZero(); assertThat(one.shortContacts()).isZero();
        assertThat(one.scheduledContactDurationSeconds()).isEqualByComparingTo(one.unionAvailableSeconds());
        assertThat(analyze(List.of(c("a", "A", 0, 299), c("b", "A", 299, 599)), true).adjacentSameGroundStationPairs()).isEqualTo(1);
    }
}
