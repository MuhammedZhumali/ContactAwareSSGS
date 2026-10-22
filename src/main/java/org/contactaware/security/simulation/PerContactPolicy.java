package org.contactaware.security.simulation;

import org.contactaware.security.*;
import java.util.*;
import static org.contactaware.security.simulation.PlanningAction.Kind.*;

/**
 * Each contact uses material activated in that exact contact, never a prior contact's pair.
 * Zero nonce progress is insufficient: an activated pair with no successful traffic is still
 * historical at the next AOS. Successful activation timestamps recover contact ownership
 * without mutable hidden policy memory because online activation requires a matching,
 * nonoverlapping contact. Preparation/installation alone do not commit material to a contact;
 * such still-unused PRE_ACTIVE inventory can be tried in a later contact. Failed SWITCH/OPEN
 * continues to obey the executor's uncertainty gate; this policy invents no reconciliation.
 *
 * The rule progresses one admissible setup primitive at a time, not an optimized chain.
 * JUST_IN_TIME permits paid ground preparation for the next visible contact at AOS minus
 * PREPARE duration; CONTACT_ONLY begins preparation inside the target contact. Expired
 * current-contact material requires fresh setup again before further payload is allowed.
 */
public final class PerContactPolicy implements SecurityPolicy {
    public enum AdvancePreparation { CONTACT_ONLY, JUST_IN_TIME }
    private final PerContactConfiguration configuration;

    public PerContactPolicy(AdvancePreparation preparation) { this(new PerContactConfiguration(preparation)); }
    public PerContactPolicy(PerContactConfiguration configuration) { this.configuration = Objects.requireNonNull(configuration); }
    @Override public PerContactConfiguration configuration() { return configuration; }

    @Override public PolicySelection select(PlanningObservation observation, List<PlanningAction> candidates) {
        Objects.requireNonNull(observation); Objects.requireNonNull(candidates);
        if (observation.visibleContacts().isEmpty()) return PolicySelection.rule(null, "No visible contact; await actual event");
        var current = observation.currentContact();
        var currentId = current == null ? null : contextId(current);
        boolean owned = current != null && ownsCurrentPair(observation, current);
        if (current != null && !owned) {
            var setup = first(candidates, currentId, SWITCH, INSTALL, PREPARE);
            if (setup != null) return PolicySelection.rule(setup, "Current contact requires its own newly activated pair");
        }
        if (configuration.advancePreparation() == AdvancePreparation.JUST_IN_TIME) {
            var future = observation.visibleContacts().stream().filter(c -> c.window().start().isAfter(observation.at())).findFirst();
            if (future.isPresent()) {
                var next = future.get();
                var start = next.window().start().minus(observation.costs().cost(SecurityAction.PREPARE).duration());
                if (!observation.at().isBefore(start)) {
                    var preparation = first(candidates, contextId(next), PREPARE);
                    if (preparation != null) return PolicySelection.rule(preparation, "Paid just-in-time preparation for next visible contact");
                }
            }
        }
        if (owned) {
            var service = first(candidates, currentId, CONTINUE, OPEN, REUSE);
            if (service != null) return PolicySelection.rule(service, "Serve only with this contact's activated pair");
        }
        return PolicySelection.rule(candidates.stream().filter(a -> a.kind() == WAIT)
                        .min(Comparator.comparing(PlanningAction::until))
                        .orElseThrow(() -> new IllegalArgumentException("Nonempty horizon must offer WAIT")),
                "Await fresh material or a contact event; historical active material is not reused");
    }

    boolean ownsCurrentPair(PlanningObservation observation, SimulationContact contact) {
        var context = observation.securityState().contexts().get(contextId(contact));
        if (context == null || context.uplink().keyId() == null || context.downlink().keyId() == null) return false;
        for (var id : List.of(context.uplink().keyId(), context.downlink().keyId())) {
            var key = observation.securityState().keys().get(id);
            if (key == null || key.status() != KeyStatus.ACTIVE || key.activatedAt() == null
                    || key.activatedAt().isBefore(contact.window().start()) || !key.activatedAt().isBefore(contact.window().end())) return false;
        }
        return true;
    }

    private static ContextId contextId(SimulationContact contact) {
        return new ContextId(contact.window().satelliteId(), contact.window().groundStationId());
    }

    private static PlanningAction first(List<PlanningAction> actions, ContextId context, PlanningAction.Kind... kinds) {
        var comparator = new PlanningResultComparator();
        for (var kind : kinds) {
            var found = actions.stream().filter(a -> a.kind() == kind && context.equals(a.contextId()))
                    .min(comparator::compareActions);
            if (found.isPresent()) return found.get();
        }
        return null;
    }
}
