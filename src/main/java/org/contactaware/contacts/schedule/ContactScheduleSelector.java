package org.contactaware.contacts.schedule;

import org.contactaware.contacts.Model.ContactWindow;
import org.contactaware.contacts.Model.Interval;
import java.time.Duration;
import java.util.*;

/** Longest path in the DAG of feasible whole-window transitions. No security inputs. */
public final class ContactScheduleSelector {
    public static final String ALGORITHM = "PREDECESSOR_DAG_DYNAMIC_PROGRAMMING";
    public static final String VERSION = "1.0";
    public static final List<String> OBJECTIVE = List.of("MAX_TOTAL_DURATION", "MIN_GS_TRANSITIONS",
            "LEXICOGRAPHIC_MIN_SEQUENCE_BY_START_END_ID");
    public static final Comparator<ContactWindow> ORDER = Comparator.comparing(ContactWindow::start)
            .thenComparing(ContactWindow::end).thenComparing(ContactWindow::id);

    private record State(Duration duration, int changes, List<ContactWindow> contacts) {
        State append(ContactWindow c) {
            var next = new ArrayList<>(contacts); next.add(c);
            int change = contacts.isEmpty() || contacts.getLast().groundStationId().equals(c.groundStationId()) ? 0 : 1;
            return new State(duration.plus(Duration.between(c.start(), c.end())), changes + change, List.copyOf(next));
        }
    }

    public ScheduleSelectionResult select(List<ContactWindow> candidates, String satelliteId,
                                           Interval period, ScheduleSelectionConfig config) {
        ScheduleValidator.validateCandidates(candidates, satelliteId, period); Objects.requireNonNull(config);
        var sorted = candidates.stream().sorted(ORDER).toList();
        var empty = new State(Duration.ZERO, 0, List.of()); var best = empty;
        List<State> endingAt = new ArrayList<>();
        for (int i = 0; i < sorted.size(); i++) {
            var current = sorted.get(i); var ending = empty.append(current);
            // Future feasibility and extra switch cost depend only on this last contact.
            for (int j = 0; j < i; j++) if (ScheduleValidator.compatible(sorted.get(j), current, config)) {
                var proposal = endingAt.get(j).append(current);
                if (better(proposal, ending)) ending = proposal;
            }
            endingAt.add(ending);
            if (better(ending, best)) best = ending;
        }
        var validation = ScheduleValidator.validate(best.contacts, candidates, satelliteId, period, config);
        validation.requireValid();
        return new ScheduleSelectionResult(best.contacts, ScheduleSelectionMetrics.of(sorted, best.contacts, validation));
    }

    private static boolean better(State a, State b) {
        int duration = a.duration.compareTo(b.duration);
        if (duration != 0) return duration > 0;
        if (a.changes != b.changes) return a.changes < b.changes;
        for (int i = 0; i < Math.min(a.contacts.size(), b.contacts.size()); i++) {
            int order = ORDER.compare(a.contacts.get(i), b.contacts.get(i));
            if (order != 0) return order < 0;
        }
        return a.contacts.size() < b.contacts.size();
    }
}
