package org.contactaware.contacts.analysis;

import org.contactaware.contacts.Model.ContactWindow;
import org.contactaware.contacts.Model.Interval;
import org.contactaware.contacts.schedule.*;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import static org.contactaware.contacts.analysis.ContactStatistics.*;
import static org.contactaware.contacts.schedule.ScheduleSelectionMetrics.seconds;

public final class ContactGapAnalyzer {
    public static final String VERSION = "1.0";
    public record AdjacentPair(String previousContactId, String nextContactId, BigDecimal gapSeconds, boolean sameGroundStation) {}
    public record Result(int contactCount, int activeGroundStations, Map<String, Integer> contactsByGroundStation,
                         Distribution durationSeconds, Distribution adjacentGapSeconds,
                         int overlappingAdjacentPairs, int chronologicalGroundStationChanges,
                         int adjacentSameGroundStationPairs, double shortContactThresholdSeconds,
                         int shortContacts, Double shortContactFraction,
                         BigDecimal sumContactDurationSeconds, BigDecimal scheduledContactDurationSeconds,
                         BigDecimal unionAvailableSeconds, BigDecimal timeWithoutAnyContactSeconds,
                         BigDecimal timeWithoutSelectedContactSeconds,
                         List<Interval> uncoveredIntervalsIncludingPeriodBoundaries,
                         GroundStationRecurrenceAnalyzer.Result sameGroundStationRecurrence,
                         List<AdjacentPair> adjacentPairs) {}

    public Result analyze(List<ContactWindow> input, String satelliteId, Interval period,
                          Collection<String> stationIds, boolean selectedSchedule, double shortSeconds) {
        ScheduleValidator.validateCandidates(input, satelliteId, period);
        if (!Double.isFinite(shortSeconds) || shortSeconds <= 0) throw new IllegalArgumentException("Short threshold must be positive");
        var contacts = input.stream().sorted(ContactScheduleSelector.ORDER).toList();
        if (selectedSchedule) ScheduleValidator.validate(contacts, contacts, satelliteId, period, ScheduleSelectionConfig.DEFAULT).requireValid();
        Map<String, Integer> counts = new TreeMap<>(); stationIds.forEach(s -> counts.put(s, 0));
        List<AdjacentPair> pairs = new ArrayList<>(); List<Interval> uncovered = new ArrayList<>();
        Instant coveredUntil = period.start(); int changes = 0, shorts = 0, overlaps = 0;
        for (int i = 0; i < contacts.size(); i++) {
            var c = contacts.get(i); counts.merge(c.groundStationId(), 1, Integer::sum);
            if (c.durationSeconds() < shortSeconds) shorts++;
            if (i > 0) {
                var p = contacts.get(i - 1); boolean same = p.groundStationId().equals(c.groundStationId());
                var gap = seconds(Duration.between(p.end(), c.start()));
                pairs.add(new AdjacentPair(p.id(), c.id(), gap, same));
                if (!same) changes++; if (gap.signum() < 0) overlaps++;
            }
            if (c.start().isAfter(coveredUntil)) uncovered.add(new Interval(coveredUntil, c.start()));
            if (c.end().isAfter(coveredUntil)) coveredUntil = c.end();
        }
        if (coveredUntil.isBefore(period.end())) uncovered.add(new Interval(coveredUntil, period.end()));
        Duration unavailable = Duration.ZERO;
        for (var gap : uncovered) unavailable = unavailable.plus(Duration.between(gap.start(), gap.end()));
        var sum = ScheduleSelectionMetrics.totalSeconds(contacts); var missing = seconds(unavailable);
        var union = seconds(Duration.between(period.start(), period.end())).subtract(missing);
        return new Result(contacts.size(), (int) counts.values().stream().filter(n -> n > 0).count(),
                Collections.unmodifiableMap(counts), distribution(contacts.stream().map(ContactWindow::durationSeconds).toList()),
                distribution(pairs.stream().map(p -> p.gapSeconds.doubleValue()).toList()), overlaps, changes,
                pairs.size() - changes, shortSeconds, shorts, fraction(shorts, contacts.size()), sum,
                selectedSchedule ? sum : null, union, missing, selectedSchedule ? missing : null,
                List.copyOf(uncovered), new GroundStationRecurrenceAnalyzer().analyze(contacts), List.copyOf(pairs));
    }
}
