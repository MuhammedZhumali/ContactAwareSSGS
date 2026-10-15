package org.contactaware.security;

import org.contactaware.contacts.Model.ContactWindow;
import java.time.*;
import java.util.*;

final class SecurityFixtures {
    static final String SATELLITE = "NORAD-25544";
    static final Instant EPOCH = Instant.parse("2026-10-10T00:00:00Z");
    static final ContextId A = new ContextId(SATELLITE, "GS-A");
    static final ContextId B = new ContextId(SATELLITE, "GS-B");
    static final KeyPairIds A_KEYS = new KeyPairIds("A-up-1", "A-down-1");
    static final KeyPairIds B_KEYS = new KeyPairIds("B-up-1", "B-down-1");
    static final SecurityConstraints LIMITS = limits(1000, 2000, 100);
    static final ContactWindow FIRST_A = contact("A-1", A, 10, 40);

    static Instant at(long seconds) { return EPOCH.plusSeconds(seconds); }

    static SecurityConstraints limits(long cryptoperiod, long materialAge, long reuseGap) {
        return new SecurityConstraints(Duration.ofSeconds(cryptoperiod), Duration.ofSeconds(materialAge),
                Duration.ofSeconds(reuseGap), Set.of(A, B), Set.of());
    }

    static ContactWindow contact(String id, ContextId context, long start, long end) {
        return new ContactWindow(id, context.satelliteId(), context.groundStationId(), at(start), at(end), null, null, List.of());
    }

    static SecurityState initial(LifecycleTransitions transitions) {
        var empty = SecurityState.empty(SATELLITE, EPOCH);
        var a = transitions.createContext(empty, A, "SA-A-up", "SA-A-down", EPOCH);
        return transitions.createContext(a, B, "SA-B-up", "SA-B-down", EPOCH);
    }

    static SecurityState keyed(LifecycleTransitions transitions, SecurityState state, ContextId id,
                                KeyPairIds keys, ContactWindow contact) {
        var prepared = transitions.prepareKeys(state, id, keys, state.asOf());
        var installed = transitions.confirmInstallation(prepared, id, keys, contact, contact.start());
        var activated = transitions.activateKeys(installed, id, keys, contact, contact.start());
        return transitions.bindKeys(activated, id, keys, contact, contact.start());
    }

    static SecurityState firstACompleted(LifecycleTransitions transitions) {
        var bound = keyed(transitions, initial(transitions), A, A_KEYS, FIRST_A);
        var open = transitions.startContext(bound, A, FIRST_A, at(10));
        var used = transitions.recordProtectedTraffic(open, A, at(11), at(39), 2, 3);
        return transitions.stopContext(used, A, at(40));
    }

    private SecurityFixtures() {}
}
