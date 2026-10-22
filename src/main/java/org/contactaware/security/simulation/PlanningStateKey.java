package org.contactaware.security.simulation;

import org.contactaware.security.*;
import java.time.*;
import java.util.*;
import static org.contactaware.security.simulation.ExecutionSnapshot.*;

/**
 * Conservative sufficient equivalence for continuation search within one fixed root.
 * All execution data is retained, including event ordering, issued identifiers and
 * historical key/SA metadata. Only the presentation of the past journal is replaced
 * with the aggregates and rejection flag actually consumed by planning/evaluation.
 * Journal sequence numbers and narrative text cannot affect future transitions.
 *
 * A matching key permits reuse of a fully solved canonical suffix, never the old
 * prefix or an incomplete search result. This is intentionally stronger than
 * minimal behavioral equivalence; no identifiers, timers or counters are renamed.
 */
record PlanningStateKey(SimulationScenario scenario, Instant at, SecurityState securityState,
                        List<PendingOperation> pendingOperations, List<QueuedEvent> queuedEvents,
                        long nextInsertion, Set<String> registeredExpiryEvents,
                        Set<ContextId> uncertainContexts, Map<String, PayloadProgress> payloadProgress,
                        InFlightFrame inFlightFrame, ResourceUsage carriedRecordedCosts,
                        Duration carriedUsefulTime, long carriedCreatedKeys,
                        Set<String> issuedKeyIds, Set<String> issuedOperationIds, ExecutionMode executionMode,
                        ResourceUsage recordedCosts, Duration usefulSecureTime, long createdKeys,
                        boolean rejected) {
    PlanningStateKey {
        pendingOperations = List.copyOf(pendingOperations);
        queuedEvents = List.copyOf(queuedEvents);
        registeredExpiryEvents = Set.copyOf(registeredExpiryEvents);
        uncertainContexts = Set.copyOf(uncertainContexts);
        payloadProgress = Map.copyOf(payloadProgress);
        issuedKeyIds = Set.copyOf(issuedKeyIds);
        issuedOperationIds = Set.copyOf(issuedOperationIds);
    }

    static PlanningStateKey from(ExecutionSnapshot snapshot) {
        SearchControl.checkpoint("stateKey.snapshot");
        Objects.requireNonNull(snapshot);
        if (snapshot.executionMode() != ExecutionMode.PLANNING_BRANCH)
            throw new IllegalArgumentException("Planning equivalence requires a planning branch snapshot");
        return new PlanningStateKey(snapshot.scenario(), snapshot.at(), snapshot.securityState(),
                snapshot.pendingOperations(), snapshot.queuedEvents(), snapshot.nextInsertion(),
                snapshot.registeredExpiryEvents(), snapshot.uncertainContexts(), snapshot.payloadProgress(),
                snapshot.inFlightFrame(), snapshot.carriedRecordedCosts(), snapshot.carriedUsefulTime(),
                snapshot.carriedCreatedKeys(), snapshot.issuedKeyIds(), snapshot.issuedOperationIds(), snapshot.executionMode(),
                snapshot.recordedCosts(), snapshot.usefulSecureTime(), snapshot.createdKeys(),
                snapshot.journal().stream().peek(ignored -> SearchControl.checkpoint("stateKey.stream")).anyMatch(event -> event.type() == SimulationEvent.Type.ACTION_FAILED
                        || event.type() == SimulationEvent.Type.PAYLOAD_REJECTED));
    }
}
