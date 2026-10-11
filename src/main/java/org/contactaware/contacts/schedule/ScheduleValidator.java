package org.contactaware.contacts.schedule;

import org.contactaware.contacts.Model.ContactWindow;
import org.contactaware.contacts.Model.Interval;
import java.time.Duration;
import java.util.*;
import static org.contactaware.contacts.Model.requireText;

public final class ScheduleValidator {
    private ScheduleValidator() {}

    public record Validation(boolean valid, Map<String, Boolean> checks) {
        public Validation { checks = Collections.unmodifiableMap(new TreeMap<>(checks)); }
        public void requireValid() {
            if (!valid) throw new IllegalArgumentException("Invalid selected schedule: " + checks);
        }
    }

    public static void validateCandidates(List<ContactWindow> contacts, String satelliteId, Interval period) {
        requireText(satelliteId, "satelliteId"); Objects.requireNonNull(period, "analysis period");
        Set<String> ids = new HashSet<>();
        for (var c : contacts) {
            if (!ids.add(c.id())) throw new IllegalArgumentException("Duplicate contact " + c.id());
            if (!c.satelliteId().equals(satelliteId)) throw new IllegalArgumentException("Mixed satellite IDs");
            if (c.start().isBefore(period.start()) || c.end().isAfter(period.end()))
                throw new IllegalArgumentException("Contact outside analysis period: " + c.id());
        }
    }

    public static boolean compatible(ContactWindow previous, ContactWindow next, ScheduleSelectionConfig config) {
        var gap = Duration.between(previous.end(), next.start());
        return !gap.isNegative() && (previous.groundStationId().equals(next.groundStationId())
                || gap.compareTo(config.guard()) >= 0);
    }

    public static Validation validate(List<ContactWindow> selected, List<ContactWindow> candidates,
                                      String satelliteId, Interval period, ScheduleSelectionConfig config) {
        validateCandidates(candidates, satelliteId, period);
        Map<String, ContactWindow> byId = new HashMap<>(); candidates.forEach(c -> byId.put(c.id(), c));
        Map<String, Boolean> checks = new TreeMap<>();
        checks.put("originalWholeWindows", selected.stream().allMatch(c -> c.equals(byId.get(c.id()))));
        checks.put("uniqueIds", selected.stream().map(ContactWindow::id).distinct().count() == selected.size());
        checks.put("singleSatellite", selected.stream().allMatch(c -> c.satelliteId().equals(satelliteId)));
        checks.put("withinAnalysisPeriod", selected.stream().allMatch(c -> !c.start().isBefore(period.start()) && !c.end().isAfter(period.end())));
        boolean chronological = true, nonoverlap = true, guard = true;
        for (int i = 1; i < selected.size(); i++) {
            var a = selected.get(i - 1); var b = selected.get(i);
            chronological &= ContactScheduleSelector.ORDER.compare(a, b) <= 0;
            nonoverlap &= !a.end().isAfter(b.start());
            guard &= compatible(a, b, config);
        }
        checks.put("chronologicalOrder", chronological); checks.put("nonOverlapping", nonoverlap);
        checks.put("stationSwitchGuardSatisfied", guard);
        return new Validation(checks.values().stream().allMatch(Boolean::booleanValue), checks);
    }
}
