package org.contactaware.contacts.analysis;

import org.contactaware.contacts.Model.ContactWindow;
import org.contactaware.contacts.schedule.ContactScheduleSelector;
import org.contactaware.contacts.schedule.ScheduleSelectionMetrics;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import static org.contactaware.contacts.analysis.ContactStatistics.*;

public final class GroundStationRecurrenceAnalyzer {
    public record Pair(String groundStationId, String previousContactId, String nextContactId,
                       Instant previousEnd, Instant nextStart, BigDecimal gapSeconds,
                       String bucket, boolean eligible30min, boolean eligible1h, boolean eligible2h, boolean eligible6h) {}
    public record CountFraction(int count, Double fraction) {}
    public record Result(int pairCount, int nonnegativePairCount, int overlappingPairCount,
                         Distribution gapSeconds, Map<String, CountFraction> buckets,
                         Map<String, CountFraction> temporalReuseEligibility, String fractionDenominator,
                         List<Pair> pairs) {}
    private static final List<String> BUCKETS = List.of("[0,1800]", "(1800,3600]", "(3600,7200]", "(7200,21600]", "(21600,+inf)");
    private static final List<Integer> THRESHOLDS = List.of(1800, 3600, 7200, 21600);

    public Result analyze(List<ContactWindow> contacts) {
        Map<String, ContactWindow> previous = new HashMap<>(); List<Pair> pairs = new ArrayList<>();
        Map<String, Integer> counts = new LinkedHashMap<>(); BUCKETS.forEach(b -> counts.put(b, 0));
        Map<String, Integer> eligible = new TreeMap<>(); THRESHOLDS.forEach(t -> eligible.put(t.toString(), 0));
        int overlaps = 0;
        for (var c : contacts.stream().sorted(ContactScheduleSelector.ORDER).toList()) {
            var p = previous.put(c.groundStationId(), c); if (p == null) continue;
            var gap = ScheduleSelectionMetrics.seconds(Duration.between(p.end(), c.start()));
            String bucket = "OVERLAP";
            if (gap.signum() < 0) overlaps++;
            else {
                int index = 0;
                while (index < THRESHOLDS.size() && gap.compareTo(BigDecimal.valueOf(THRESHOLDS.get(index))) > 0) index++;
                bucket = BUCKETS.get(index); counts.merge(bucket, 1, Integer::sum);
                for (int threshold : THRESHOLDS) if (eligible(gap, threshold)) eligible.merge("" + threshold, 1, Integer::sum);
            }
            pairs.add(new Pair(c.groundStationId(), p.id(), c.id(), p.end(), c.start(), gap, bucket,
                    eligible(gap, 1800), eligible(gap, 3600), eligible(gap, 7200), eligible(gap, 21600)));
        }
        Map<String, CountFraction> buckets = new LinkedHashMap<>(), reuse = new TreeMap<>();
        counts.forEach((key, count) -> buckets.put(key, new CountFraction(count, fraction(count, pairs.size()))));
        eligible.forEach((key, count) -> reuse.put(key, new CountFraction(count, fraction(count, pairs.size()))));
        return new Result(pairs.size(), pairs.size() - overlaps, overlaps,
                distribution(pairs.stream().map(p -> p.gapSeconds.doubleValue()).toList()),
                Collections.unmodifiableMap(buckets), Collections.unmodifiableMap(reuse),
                "All consecutive same-GS recurrence pairs, including overlaps; negative gaps are ineligible and outside buckets",
                List.copyOf(pairs));
    }

    private static boolean eligible(BigDecimal gap, int threshold) {
        return gap.signum() >= 0 && gap.compareTo(BigDecimal.valueOf(threshold)) <= 0;
    }
}
