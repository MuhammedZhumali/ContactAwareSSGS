package org.contactaware.contacts.schedule;

import org.contactaware.contacts.Model.ContactWindow;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.*;

public record ScheduleSelectionMetrics(int candidateWindows, int selectedWindows, int excludedWindows,
        BigDecimal totalCandidateDurationSeconds, BigDecimal totalSelectedDurationSeconds,
        int groundStationTransitions, long overlappingCandidatePairs,
        ScheduleValidator.Validation verification, List<String> selectedContactIds,
        List<String> excludedContactIds, String excludedInterpretation) {

    public static BigDecimal totalSeconds(List<ContactWindow> contacts) {
        Duration total = Duration.ZERO;
        for (var c : contacts) total = total.plus(Duration.between(c.start(), c.end()));
        return seconds(total);
    }

    public static BigDecimal seconds(Duration duration) {
        return BigDecimal.valueOf(duration.getSeconds()).add(BigDecimal.valueOf(duration.getNano(), 9));
    }

    static ScheduleSelectionMetrics of(List<ContactWindow> candidates, List<ContactWindow> selected,
                                        ScheduleValidator.Validation validation) {
        Set<String> ids = new HashSet<>(); selected.forEach(c -> ids.add(c.id()));
        long overlaps = 0; int changes = 0;
        for (int i = 0; i < candidates.size(); i++) for (int j = i + 1; j < candidates.size(); j++) {
            var a = candidates.get(i); var b = candidates.get(j);
            if (a.start().isBefore(b.end()) && b.start().isBefore(a.end())) overlaps++;
        }
        for (int i = 1; i < selected.size(); i++)
            if (!selected.get(i - 1).groundStationId().equals(selected.get(i).groundStationId())) changes++;
        return new ScheduleSelectionMetrics(candidates.size(), selected.size(), candidates.size() - selected.size(),
                totalSeconds(candidates), totalSeconds(selected), changes, overlaps, validation,
                selected.stream().map(ContactWindow::id).toList(),
                candidates.stream().filter(c -> !ids.contains(c.id())).map(ContactWindow::id).toList(),
                "Unused alternative windows; not failed communications");
    }
}
