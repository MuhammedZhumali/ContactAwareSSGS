package org.contactaware.security.simulation;

import org.contactaware.security.*;
import java.time.*;
import java.util.*;

/**
 * Immutable planner input. The full execution snapshot is privileged runtime data;
 * only this projection may be supplied to a horizon-limited planner.
 * No future supplied plan, queue, interruption or acknowledgement outcome is exposed.
 */
public record PlanningObservation(
        Instant at, int horizon, SimulationContact currentContact,
        List<SimulationContact> visibleContacts, Instant evaluationEnd,
        SecurityState securityState, SecurityConstraints constraints, SecurityCostProfile costs,
        List<OngoingOperation> ongoingOperations, List<ResourceReservation> resourceReservations,
        Set<ContextId> uncertainContexts,
        Map<String, ExecutionSnapshot.PayloadProgress> payloadProgress,
        ExecutionSnapshot.InFlightFrame inFlightFrame,
        ResourceUsage recordedCosts, ResourceUsage accruedCosts,
        Duration usefulSecureTime, long createdKeys, List<Instant> wakeUps,
        Set<String> issuedKeyIds, Set<String> issuedOperationIds) {

    /** A committed action's nominal remaining work, without future outcome controls. */
    public record OngoingOperation(String id, SecurityAction action, ContextId contextId,
                                   KeyPairIds candidateKeys, String contactId,
                                   List<SecurityAction> steps, int stepIndex,
                                   Instant stepStartedAt, KeyPairIds stepKeys,
                                   Instant expectedStepCompletion) {
        public OngoingOperation { steps = List.copyOf(steps); }
    }

    /** Remaining occupied intervals, derived from known costs rather than the runtime queue. */
    public record ResourceReservation(String operationId, String resource, Instant from, Instant until) {
        public ResourceReservation {
            Objects.requireNonNull(operationId); Objects.requireNonNull(resource);
            if (!from.isBefore(until)) throw new IllegalArgumentException("Empty resource interval");
        }
    }

    public PlanningObservation {
        Objects.requireNonNull(at); Objects.requireNonNull(evaluationEnd);
        Objects.requireNonNull(securityState); Objects.requireNonNull(constraints); Objects.requireNonNull(costs);
        Objects.requireNonNull(recordedCosts); Objects.requireNonNull(accruedCosts);
        Objects.requireNonNull(usefulSecureTime);
        if (horizon < 0) throw new IllegalArgumentException("H cannot be negative");
        visibleContacts = List.copyOf(visibleContacts);
        ongoingOperations = List.copyOf(ongoingOperations);
        resourceReservations = List.copyOf(resourceReservations);
        uncertainContexts = Collections.unmodifiableSet(new TreeSet<>(uncertainContexts));
        payloadProgress = Collections.unmodifiableMap(new TreeMap<>(payloadProgress));
        wakeUps = List.copyOf(wakeUps);
        issuedKeyIds = Collections.unmodifiableSet(new TreeSet<>(issuedKeyIds));
        issuedOperationIds = Collections.unmodifiableSet(new TreeSet<>(issuedOperationIds));
        if (evaluationEnd.isBefore(at) || usefulSecureTime.isNegative() || createdKeys < 0)
            throw new IllegalArgumentException("Invalid observation progress or horizon");
        var expectedEnd = visibleContacts.isEmpty() ? at : visibleContacts.getLast().window().end();
        if (!expectedEnd.equals(evaluationEnd)) throw new IllegalArgumentException("Evaluation must end at the last visible contact");
        for (var wake : wakeUps)
            if (!wake.isAfter(at) || wake.isAfter(evaluationEnd))
                throw new IllegalArgumentException("Wakeup outside the visible future");
    }

    /**
     * H counts contacts after the current half-open contact [start,end).
     * In a gap there is no current contact, so exactly H nearest contacts are visible.
     */
    public static PlanningObservation from(ExecutionSnapshot snapshot, int horizon) {
        Objects.requireNonNull(snapshot);
        if (horizon < 0) throw new IllegalArgumentException("H cannot be negative");
        var now = snapshot.at();
        var scenario = snapshot.scenario();
        var forecastCurrent = scenario.contacts().stream()
                .filter(c -> !now.isBefore(c.window().start()) && now.isBefore(c.window().end()))
                .findFirst().orElse(null);
        boolean unavailable = forecastCurrent != null && snapshot.channelUnavailable(forecastCurrent.window().id());
        var current = unavailable ? null : forecastCurrent;
        // Select the original permitted prefix first. Removing a factually unavailable current
        // contact must not expose an extra distant contact or its private restoration event.
        var visible = scenario.contacts().stream().filter(c -> c.window().end().isAfter(now))
                .limit((long) horizon + (forecastCurrent == null ? 0 : 1))
                .filter(c -> !unavailable || !c.window().id().equals(forecastCurrent.window().id())).toList();
        var end = visible.isEmpty() ? now : visible.getLast().window().end();
        var operations = new ArrayList<OngoingOperation>();
        var reservations = new ArrayList<ResourceReservation>();
        for (var pending : snapshot.pendingOperations()) {
            var planned = pending.planned();
            var intent = planned.intent();
            var completion = pending.stepStartedAt().plus(scenario.costs().cost(pending.steps().get(pending.stepIndex())).duration());
            operations.add(new OngoingOperation(planned.id(), intent.action(), intent.contextId(), intent.newKeys(),
                    planned.contactId(), pending.steps(), pending.stepIndex(), pending.stepStartedAt(), pending.stepKeys(), completion));
            // Runtime reservations can contain private interruptAt / simulation-end clipping.
            // Reconstruct the remaining committed nominal phases using only permitted facts.
            Instant limit = end;
            if (intent.action().requiresContact()) {
                if (current == null || !current.window().id().equals(planned.contactId()))
                    throw new IllegalArgumentException("Ongoing online action must belong to the current contact");
                limit = earlier(limit, current.window().end());
            }
            Instant cursor = pending.stepStartedAt();
            for (int i = pending.stepIndex(); i < pending.steps().size(); i++) {
                for (var phase : scenario.costs().cost(pending.steps().get(i)).phases()) {
                    var phaseEnd = cursor.plus(phase.duration());
                    String resource = switch (phase.resource()) {
                        case GROUND_CPU -> "ground:" + intent.contextId().groundStationId();
                        case SPACECRAFT_CPU -> "spacecraft";
                        case UPLINK, DOWNLINK -> "radio";
                        case WAIT -> null;
                    };
                    var remainingFrom = later(cursor, now);
                    var remainingUntil = earlier(phaseEnd, limit);
                    if (resource != null && remainingFrom.isBefore(remainingUntil))
                        reservations.add(new ResourceReservation(planned.id(), resource, remainingFrom, remainingUntil));
                    cursor = phaseEnd;
                }
            }
        }
        operations.sort(Comparator.comparing(OngoingOperation::id));
        reservations.sort(Comparator.comparing(ResourceReservation::from).thenComparing(ResourceReservation::resource)
                .thenComparing(ResourceReservation::operationId).thenComparing(ResourceReservation::until));
        var wakeUps = new TreeSet<Instant>();
        for (var contact : visible) {
            addWakeup(wakeUps, contact.window().start(), now, end);
            addWakeup(wakeUps, contact.window().end(), now, end);
            addWakeup(wakeUps, contact.window().start().minus(scenario.costs().cost(SecurityAction.PREPARE).duration()), now, end);
        }
        for (var key : snapshot.securityState().keys().values())
            if (key.status() == KeyStatus.ACTIVE || key.status() == KeyStatus.PRE_ACTIVE)
                addWakeup(wakeUps, key.hardDeadline(scenario.constraints()), now, end);
        for (var operation : operations) addWakeup(wakeUps, operation.expectedStepCompletion(), now, end);
        if (snapshot.inFlightFrame() != null) addWakeup(wakeUps, snapshot.inFlightFrame().until(), now, end);
        // Only realized journal totals cross the observation boundary; identifiers,
        // narrative reasons and unexecuted intents do not.
        return new PlanningObservation(now, horizon, current, visible, end,
                snapshot.securityState(), scenario.constraints(), scenario.costs(), operations, reservations,
                snapshot.uncertainContexts(), snapshot.payloadProgress(), snapshot.inFlightFrame(),
                snapshot.recordedCosts(), snapshot.accruedCosts(), snapshot.usefulSecureTime(), snapshot.createdKeys(),
                List.copyOf(wakeUps), snapshot.issuedKeyIds(), snapshot.issuedOperationIds());
    }

    private static void addWakeup(Set<Instant> wakeUps, Instant candidate, Instant now, Instant end) {
        if (candidate.isAfter(now) && !candidate.isAfter(end)) wakeUps.add(candidate);
    }

    /** Add rule-derived timers without changing visibility, history, or the fixed evaluation boundary. */
    public PlanningObservation withAdditionalWakeUps(Collection<Instant> additional) {
        var combined = new TreeSet<>(wakeUps);
        for (var candidate : additional) addWakeup(combined, Objects.requireNonNull(candidate), at, evaluationEnd);
        return new PlanningObservation(at, horizon, currentContact, visibleContacts, evaluationEnd,
                securityState, constraints, costs, ongoingOperations, resourceReservations, uncertainContexts,
                payloadProgress, inFlightFrame, recordedCosts, accruedCosts, usefulSecureTime, createdKeys,
                List.copyOf(combined), issuedKeyIds, issuedOperationIds);
    }

    private static Instant earlier(Instant a, Instant b) { return a.isBefore(b) ? a : b; }
    private static Instant later(Instant a, Instant b) { return a.isAfter(b) ? a : b; }
}
