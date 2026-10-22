package org.contactaware.security.simulation;

import org.contactaware.security.*;
import java.time.*;
import java.util.*;
import static org.contactaware.security.simulation.PlanningAction.Kind.*;

/**
 * Deterministic activation-relative renewal, without horizon optimization.
 * A confirmed SWITCH resets the period; PREPARE, INSTALL, OPEN and failed work do not.
 * Retained retired-key history preserves the anchor after expiry. Multiple missed periods
 * coalesce into one due request. Ground preparation starts only once due (or for initial
 * bootstrap), and online progress uses the next individually admissible primitive. It may
 * stage installation even when switching must wait for a later contact. Valid old material
 * can serve while the next renewal step is unavailable; expiry never extends a deadline.
 * If historical material expires before the period is due, this strict timer baseline waits
 * for the due time rather than introducing an additional adaptive emergency-renewal rule.
 */
public final class FixedPeriodPolicy implements SecurityPolicy {
    private final FixedPeriodConfiguration configuration;

    public FixedPeriodPolicy(Duration period) { this(new FixedPeriodConfiguration(period)); }
    public FixedPeriodPolicy(FixedPeriodConfiguration configuration) { this.configuration = Objects.requireNonNull(configuration); }
    @Override public FixedPeriodConfiguration configuration() { return configuration; }

    /** Both directions must have a recorded activation at the same instant. */
    Optional<Instant> lastSuccessfulActivationAt(PlanningObservation observation, ContextId context) {
        var pairs = new TreeMap<Instant, EnumSet<Direction>>();
        for (var key : observation.securityState().keys().values())
            if (key.contextId().equals(context) && key.activatedAt() != null)
                pairs.computeIfAbsent(key.activatedAt(), ignored -> EnumSet.noneOf(Direction.class)).add(key.direction());
        return pairs.descendingMap().entrySet().stream().filter(e -> e.getValue().size() == Direction.values().length)
                .map(Map.Entry::getKey).findFirst();
    }

    boolean renewalDue(PlanningObservation observation, ContextId context) {
        return lastSuccessfulActivationAt(observation, context)
                .map(at -> !observation.at().isBefore(at.plus(configuration.period()))).orElse(true);
    }

    @Override public List<Instant> additionalWakeUps(PlanningObservation observation) {
        var wakeUps = new TreeSet<Instant>();
        for (var context : eligibleContexts(observation))
            lastSuccessfulActivationAt(observation, context).map(at -> at.plus(configuration.period()))
                    .filter(at -> at.isAfter(observation.at()) && !at.isAfter(observation.evaluationEnd()))
                    .ifPresent(wakeUps::add);
        return List.copyOf(wakeUps);
    }

    @Override public PolicySelection select(PlanningObservation observation, List<PlanningAction> candidates) {
        Objects.requireNonNull(observation); Objects.requireNonNull(candidates);
        if (observation.visibleContacts().isEmpty()) return PolicySelection.rule(null, "No visible contact; await actual event");
        var current = observation.currentContact() == null ? null : new ContextId(
                observation.currentContact().window().satelliteId(), observation.currentContact().window().groundStationId());
        if (current != null && renewalDue(observation, current)) {
            var renewal = first(candidates, current, SWITCH, INSTALL, PREPARE);
            if (renewal != null) return PolicySelection.rule(renewal, "Period due or initial bootstrap: advance one renewal step");
        }
        // Due ground-only work for a disconnected station may coexist with another link.
        for (var context : eligibleContexts(observation)) {
            if (!renewalDue(observation, context)) continue;
            var preparation = first(candidates, context, PREPARE);
            if (preparation != null) return PolicySelection.rule(preparation, "Period due or initial bootstrap: prepare while awaiting station contact");
        }
        var service = first(candidates, current, CONTINUE, OPEN, REUSE);
        if (service != null) return PolicySelection.rule(service, "Serve with admissible material while no renewal step is available");
        return PolicySelection.rule(waitAction(candidates), "Await an event, period timer or admissible renewal step");
    }

    private List<ContextId> eligibleContexts(PlanningObservation observation) {
        return observation.securityState().contexts().values().stream()
                .filter(c -> c.status() != AssociationStatus.ABSENT && observation.constraints().authorizedContexts().contains(c.id()))
                .map(SecurityContext::id).sorted().toList();
    }

    private static PlanningAction first(List<PlanningAction> actions, ContextId context, PlanningAction.Kind... kinds) {
        if (context == null) return null;
        var comparator = new PlanningResultComparator();
        for (var kind : kinds) {
            var found = actions.stream().filter(a -> a.kind() == kind && context.equals(a.contextId()))
                    .min(comparator::compareActions);
            if (found.isPresent()) return found.get();
        }
        return null;
    }

    private static PlanningAction waitAction(List<PlanningAction> actions) {
        return actions.stream().filter(a -> a.kind() == WAIT).min(Comparator.comparing(PlanningAction::until))
                .orElseThrow(() -> new IllegalArgumentException("Nonempty horizon must offer WAIT"));
    }
}
