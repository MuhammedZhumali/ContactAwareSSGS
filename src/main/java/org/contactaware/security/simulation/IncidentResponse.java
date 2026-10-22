package org.contactaware.security.simulation;

import org.contactaware.security.*;
import java.time.*;
import java.util.*;
import static org.contactaware.security.simulation.SimulationEvent.Type.*;

/** Common authenticated-notice response. Reads only notices that have actually arrived. */
final class IncidentResponse {
    record Response(PlannedOperation notice, PlanningAction action, String reason) { }

    static Response next(ExecutionSnapshot snapshot, PlanningObservation observation, List<PlanningAction> candidates) {
        var known = snapshot.journal().stream().filter(e -> e.type() == REVOCATION_RECEIVED_GROUND).toList();
        if (known.isEmpty()) return null;
        var current = observation.currentContact();
        for (var notice : known) {
            boolean received = snapshot.journal().stream().anyMatch(e -> e.type() == REVOCATION_RECEIVED_SPACECRAFT
                    && e.operationId().equals(notice.operationId()));
            if (!received && current != null && SecurityContext.matches(current.window(), notice.contextId())) {
                var planned = new PlannedOperation(BranchEngine.nextOperationId(snapshot.issuedOperationIds()),
                        new ScheduledSecurityAction(SecurityAction.NOTIFY_REVOCATION, snapshot.at(), notice.contextId(),
                                notice.keyIds(), "Common incident response: authenticated revocation " + notice.operationId()),
                        current.window().id());
                if (admissible(snapshot, observation, planned))
                    return new Response(planned, null, "Common response: deliver revocation to spacecraft before further online work");
                // No imaginary contactless delivery or completion beyond LOS. A committed independent
                // operation/frame remains intact; notification resumes at a later real event.
                return new Response(null, earliestWait(candidates), "Common response: wait for a contact/resource interval to deliver revocation");
            }
        }
        var compromised = new HashSet<String>();
        known.forEach(e -> { compromised.add(e.keyIds().uplink()); compromised.add(e.keyIds().downlink()); });
        for (var context : known.stream().map(SimulationEvent::contextId).distinct().sorted().toList()) {
            var bound = observation.securityState().context(context);
            boolean independent = bound.uplink().keyId() != null && bound.downlink().keyId() != null
                    && !compromised.contains(bound.uplink().keyId()) && !compromised.contains(bound.downlink().keyId());
            if (independent) continue; // Delayed notice for an old generation must not rotate its replacement.
            var latestAffectedActivation = known.stream().filter(e -> e.contextId().equals(context))
                    .map(e -> observation.securityState().key(e.keyIds().uplink()).activatedAt()).max(Comparator.naturalOrder()).orElseThrow();
            boolean previouslyRecovered = observation.securityState().keys().values().stream()
                    .anyMatch(k -> k.contextId().equals(context) && !compromised.contains(k.id())
                            && k.activatedAt() != null && k.activatedAt().isAfter(latestAffectedActivation));
            if (previouslyRecovered) continue; // Emergency override ends at recovery; later ordinary expiry follows the policy.
            for (var kind : List.of(PlanningAction.Kind.SWITCH, PlanningAction.Kind.INSTALL, PlanningAction.Kind.PREPARE)) {
                var next = candidates.stream().filter(a -> a.kind() == kind && context.equals(a.contextId()))
                        .min(new PlanningResultComparator()::compareActions);
                if (next.isPresent()) return new Response(null, next.get(),
                        "Common emergency recovery: prepare/install/activate independent material without waiting for policy period");
            }
        }
        return null;
    }

    private static PlanningAction earliestWait(List<PlanningAction> candidates) {
        return candidates.stream().filter(a -> a.kind() == PlanningAction.Kind.WAIT)
                .min(Comparator.comparing(PlanningAction::until)).orElse(null);
    }

    private static boolean admissible(ExecutionSnapshot snapshot, PlanningObservation observation, PlannedOperation planned) {
        if (snapshot.inFlightFrame() != null) return false;
        if (observation.uncertainContexts().contains(planned.intent().contextId())) return false;
        var duration = observation.costs().cost(SecurityAction.NOTIFY_REVOCATION).duration();
        var until = observation.at().plus(duration);
        if (!until.isBefore(observation.currentContact().window().end()) || until.isAfter(snapshot.scenario().end())) return false;
        if (snapshot.pendingOperations().stream().anyMatch(p -> p.planned().intent().contextId().equals(planned.intent().contextId())
                && p.planned().intent().action().requiresContact())) return false;
        try {
            new SecurityActionExecutor(observation.constraints()).admit(observation.securityState(), planned.intent(),
                    SecurityAction.NOTIFY_REVOCATION, observation.currentContact().window(), until);
        } catch (IllegalArgumentException rejected) { return false; }
        var cursor = observation.at();
        for (var phase : observation.costs().cost(SecurityAction.NOTIFY_REVOCATION).phases()) {
            var end = cursor.plus(phase.duration());
            var resource = switch (phase.resource()) {
                case GROUND_CPU -> "ground:" + planned.intent().contextId().groundStationId();
                case SPACECRAFT_CPU -> "spacecraft";
                case UPLINK, DOWNLINK -> "radio";
                case WAIT -> null;
            };
            if (resource != null) {
                var reservation = new ExecutionSnapshot.ResourceReservation(resource, cursor, end);
                if (snapshot.pendingOperations().stream().flatMap(p -> p.reservations().stream())
                        .anyMatch(reservation::conflicts)) return false;
            }
            cursor = end;
        }
        return true;
    }
}
