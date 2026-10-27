package org.contactaware.security.simulation;

import org.contactaware.security.*;
import java.time.*;
import java.util.*;

/**
 * Immutable, privileged checkpoint of an execution, including its future supplied plan.
 * This is an in-memory restoration format, not the planner's input or a persistence protocol.
 * Every queued event is data: no callback can retain the mutable runtime of another branch.
 */
public record ExecutionSnapshot(SimulationScenario scenario, Instant at, SecurityState securityState,
                                List<PendingOperation> pendingOperations,
                                List<QueuedEvent> queuedEvents, long nextInsertion,
                                Set<String> registeredExpiryEvents,
                                List<SimulationEvent> journal, Set<ContextId> uncertainContexts,
                                Map<String, PayloadProgress> payloadProgress, InFlightFrame inFlightFrame,
                                ResourceUsage carriedRecordedCosts, Duration carriedUsefulTime, long carriedCreatedKeys,
                                Set<String> issuedKeyIds, Set<String> issuedOperationIds, ExecutionMode executionMode) {
    public ExecutionSnapshot {
        Objects.requireNonNull(scenario); Objects.requireNonNull(at); Objects.requireNonNull(securityState);
        Objects.requireNonNull(carriedRecordedCosts); Objects.requireNonNull(carriedUsefulTime);
        Objects.requireNonNull(executionMode);
        if (carriedUsefulTime.isNegative() || carriedCreatedKeys < 0) throw new IllegalArgumentException("Negative carried totals");
        if (at.isBefore(scenario.start()) || at.isAfter(scenario.end()))
            throw new IllegalArgumentException("Snapshot outside the simulation period");
        if (securityState.asOf().isAfter(at)) throw new IllegalArgumentException("State is from the future");
        if (nextInsertion < 0) throw new IllegalArgumentException("Negative event sequence");
        pendingOperations = pendingOperations.stream()
                .sorted(Comparator.comparing(p -> p.planned().id())).toList();
        queuedEvents = queuedEvents.stream().sorted(EVENT_ORDER).toList();
        registeredExpiryEvents = Collections.unmodifiableSet(new TreeSet<>(registeredExpiryEvents));
        journal = List.copyOf(journal);
        uncertainContexts = Collections.unmodifiableSet(new TreeSet<>(uncertainContexts));
        payloadProgress = Collections.unmodifiableMap(new TreeMap<>(payloadProgress));
        issuedKeyIds = Collections.unmodifiableSet(new TreeSet<>(issuedKeyIds));
        issuedOperationIds = Collections.unmodifiableSet(new TreeSet<>(issuedOperationIds));
        var ids = new HashSet<String>();
        for (var pending : pendingOperations) {
            if (!ids.add(pending.planned().id())) throw new IllegalArgumentException("Duplicate running operation");
            if (pending.stepStartedAt().isAfter(at)) throw new IllegalArgumentException("Step has not started");
        }
        if (queuedEvents.stream().anyMatch(e -> !e.at().isAfter(at) || e.insertion() >= nextInsertion))
            throw new IllegalArgumentException("Snapshot must follow a complete event timestamp");
        if (inFlightFrame != null && (inFlightFrame.from().isAfter(at) || !inFlightFrame.until().isAfter(at)))
            throw new IllegalArgumentException("Invalid in-flight frame interval");
    }

    public enum ExecutionMode { PREDEFINED_PLAN, PLANNING_BRANCH, CONTROLLED_REAL }

    /** Only factual past events reveal availability; restoration time remains privileged queue data. */
    public boolean channelUnavailable(String contactId) {
        for (int i = journal.size() - 1; i >= 0; i--) {
            var event = journal.get(i);
            if (event.at().isAfter(at) || !Objects.equals(contactId, event.contactId())) continue;
            if (event.type() == SimulationEvent.Type.CHANNEL_DOWN) return true;
            if (event.type() == SimulationEvent.Type.CHANNEL_UP) return false;
        }
        return false;
    }

    /** Charges already committed to the journal by completed, failed or interrupted steps. */
    public ResourceUsage recordedCosts() {
        return journal.stream().map(SimulationEvent::usage).reduce(carriedRecordedCosts, ResourceUsage::plus);
    }

    public Duration usefulSecureTime() {
        return journal.stream().map(SimulationEvent::usefulTime).reduce(carriedUsefulTime, Duration::plus);
    }

    public long createdKeys() {
        long total = carriedCreatedKeys;
        for (var event : journal) total = Math.addExact(total, event.createdKeys());
        return total;
    }

    /**
     * Work consumed by this instant, including pending steps. This is a view, not a new charge.
     * Always integrate from the original step start so repeated pauses cannot round bytes twice.
     */
    public ResourceUsage accruedCosts() {
        var total = recordedCosts();
        for (var pending : pendingOperations)
            total = total.plus(scenario.costs().cost(pending.step())
                    .consumedUntil(Duration.between(pending.stepStartedAt(), at)));
        return total;
    }

    public record PendingOperation(PlannedOperation planned, List<SecurityAction> steps,
                                   List<ResourceReservation> reservations, int stepIndex,
                                   Instant stepStartedAt, KeyPairIds stepKeys) {
        public PendingOperation {
            Objects.requireNonNull(planned); Objects.requireNonNull(stepStartedAt);
            steps = List.copyOf(steps); reservations = List.copyOf(reservations);
            if (stepIndex < 0 || stepIndex >= steps.size()) throw new IllegalArgumentException("Invalid step index");
        }
        public SecurityAction step() { return steps.get(stepIndex); }
    }

    public record ResourceReservation(String resource, Instant from, Instant until) {
        public ResourceReservation {
            Objects.requireNonNull(resource); Objects.requireNonNull(from); Objects.requireNonNull(until);
            if (!from.isBefore(until)) throw new IllegalArgumentException("Empty resource reservation");
        }
        boolean conflicts(ResourceReservation other) {
            return resource.equals(other.resource) && from.isBefore(other.until) && other.from.isBefore(until);
        }
    }

    public record PayloadProgress(long uplinkBytes, long downlinkBytes, boolean nextUplink) {
        public PayloadProgress {
            if (uplinkBytes < 0 || downlinkBytes < 0) throw new IllegalArgumentException("Negative payload count");
        }
    }

    public record InFlightFrame(SimulationContact contact, ContextId context, KeyPairIds keys,
                                Instant from, Instant until, boolean uplink, boolean deliveryRejectedAtGround) {
        public InFlightFrame(SimulationContact contact, ContextId context, KeyPairIds keys,
                             Instant from, Instant until, boolean uplink) {
            this(contact, context, keys, from, until, uplink, false);
        }
        public InFlightFrame {
            Objects.requireNonNull(contact); Objects.requireNonNull(context); Objects.requireNonNull(keys);
            Objects.requireNonNull(from); Objects.requireNonNull(until);
            if (!from.isBefore(until)) throw new IllegalArgumentException("Empty frame interval");
            if (uplink && deliveryRejectedAtGround)
                throw new IllegalArgumentException("Ground can immediately stop its own uplink transmission");
        }
    }

    /**
     * A frame ending at an event boundary completes first. Expiry and contact closure precede
     * channel events; channel loss precedes ACK/action admission. UP precedes DOWN so adjacent
     * half-open outages remain unavailable after their shared timestamp has settled.
     */
    public enum EventKind {
        FRAME_COMPLETE(0), DISCLOSURE(5), KEY_DEADLINE(10), REVOCATION_NOTICE(15), CONTACT_END(20), INTERRUPT(25),
        CHANNEL_UP(21), CHANNEL_DOWN(22), CONTACT_START(30), STEP_COMPLETE(40), ACTION_START(50);
        private final int priority;
        EventKind(int priority) { this.priority = priority; }
        public int priority() { return priority; }
    }

    public record QueuedEvent(Instant at, EventKind kind, String stableId, long insertion,
                              String referenceId, int stepIndex, Instant expectedDeadline) {
        public QueuedEvent {
            Objects.requireNonNull(at); Objects.requireNonNull(kind);
            Objects.requireNonNull(stableId); Objects.requireNonNull(referenceId);
            if (insertion < 0) throw new IllegalArgumentException("Negative event sequence");
            if (kind == EventKind.KEY_DEADLINE) Objects.requireNonNull(expectedDeadline);
        }
    }

    static final Comparator<QueuedEvent> EVENT_ORDER = Comparator.comparing(QueuedEvent::at)
            .thenComparingInt(e -> e.kind().priority()).thenComparing(QueuedEvent::stableId)
            .thenComparingLong(QueuedEvent::insertion);
}
