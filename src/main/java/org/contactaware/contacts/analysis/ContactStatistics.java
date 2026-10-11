package org.contactaware.contacts.analysis;

import java.util.List;

public final class ContactStatistics {
    private ContactStatistics() {}
    public record Distribution(int count, Double min, Double max, Double mean, Double median) {}
    public static Distribution distribution(List<Double> values) {
        if (values.isEmpty()) return new Distribution(0, null, null, null, null);
        var sorted = values.stream().sorted().toList(); int n = sorted.size();
        double median = n % 2 == 1 ? sorted.get(n / 2) : (sorted.get(n / 2 - 1) + sorted.get(n / 2)) / 2;
        return new Distribution(n, sorted.getFirst(), sorted.getLast(),
                sorted.stream().mapToDouble(Double::doubleValue).average().orElseThrow(), median);
    }
    public static Double fraction(int count, int denominator) { return denominator == 0 ? null : (double) count / denominator; }
}
