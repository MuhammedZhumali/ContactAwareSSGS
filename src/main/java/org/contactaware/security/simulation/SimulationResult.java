package org.contactaware.security.simulation;

import org.contactaware.security.*;
import java.time.*;
import java.util.*;

public record SimulationResult(String scenario, Instant endedAt, SecurityState finalState,
                               List<SimulationEvent> events, Metrics metrics, Set<ContextId> uncertainContexts,
                               String costProfile, SecurityCostProfile.Provenance provenance) {
    public SimulationResult {
        events = List.copyOf(events);
        uncertainContexts = Collections.unmodifiableSet(new TreeSet<>(uncertainContexts));
    }

    public record ContactOutcome(String contactId, boolean served, Duration usefulTime, long uplinkBytes, long downlinkBytes,
                                 long uplinkFrames, long downlinkFrames, List<String> failureReasons) {
        public ContactOutcome { failureReasons = List.copyOf(failureReasons); }
    }

    public record Metrics(long servedContacts, Duration usefulSecureTime, ResourceUsage controlCosts, long createdKeys,
                          Map<SecurityAction, Long> startedOperations, Map<SecurityAction, Long> completedOperations,
                          Map<SecurityAction, Long> completedActions, Map<String, Long> operationFailures,
                          List<ContactOutcome> contacts) {
        public Metrics {
            startedOperations = Collections.unmodifiableMap(new TreeMap<>(startedOperations));
            completedOperations = Collections.unmodifiableMap(new TreeMap<>(completedOperations));
            completedActions = Collections.unmodifiableMap(new TreeMap<>(completedActions));
            operationFailures = Collections.unmodifiableMap(new TreeMap<>(operationFailures));
            contacts = List.copyOf(contacts);
        }
    }
}
