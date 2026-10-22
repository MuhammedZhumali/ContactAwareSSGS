package org.contactaware.security.simulation;

import org.contactaware.security.*;
import java.time.*;
import java.util.*;
import static org.contactaware.security.simulation.PlanningAction.Kind.*;

/** Finite, stateless candidate generation using only the explicitly supplied observation. */
public final class SecurityActionGenerator {
    private static final List<PlanningAction.Kind> PRIMITIVES = List.of(PREPARE, INSTALL, SWITCH, OPEN, REUSE);

    public List<PlanningAction> candidates(PlanningObservation observation) {
        Objects.requireNonNull(observation);
        if (!observation.at().isBefore(observation.evaluationEnd())) return List.of();
        for (var kind : PRIMITIVES)
            if (observation.costs().cost(SecurityAction.valueOf(kind.name())).duration().isZero())
                throw new IllegalArgumentException("Finite planning requires positive duration for " + kind);
        var result = new ArrayList<PlanningAction>();
        for (var context : observation.securityState().contexts().values()) {
            var id = context.id();
            if (!observation.constraints().authorizedContexts().contains(id) || context.status() == AssociationStatus.ABSENT) continue;
            var prepared = observation.securityState().keys().values().stream()
                    .filter(k -> k.contextId().equals(id) && k.status() == KeyStatus.PRE_ACTIVE).toList();
            boolean preparationPending = observation.ongoingOperations().stream().anyMatch(o -> o.contextId().equals(id)
                    && o.steps().subList(o.stepIndex(), o.steps().size()).contains(SecurityAction.PREPARE));
            if (prepared.isEmpty() && !preparationPending) {
                var issued = new TreeSet<>(observation.issuedKeyIds());
                issued.addAll(observation.securityState().keys().keySet());
                addPrimitive(result, observation, PREPARE, id,
                        KeyGenerationIds.next(id, issued, observation.constraints().blockedKeyIds()));
            }
            if (observation.currentContact() == null || !SecurityContext.matches(observation.currentContact().window(), id)) continue;
            var up = prepared.stream().filter(k -> k.direction() == Direction.UPLINK).toList();
            var down = prepared.stream().filter(k -> k.direction() == Direction.DOWNLINK).toList();
            if (up.size() == 1 && down.size() == 1) {
                var pair = new KeyPairIds(up.getFirst().id(), down.getFirst().id());
                if (!up.getFirst().installedOnBothPeers() || !down.getFirst().installedOnBothPeers())
                    addPrimitive(result, observation, INSTALL, id, pair);
                else addPrimitive(result, observation, SWITCH, id, pair);
            }
            addPrimitive(result, observation, OPEN, id, null);
            addPrimitive(result, observation, REUSE, id, null);
            addContinue(result, observation, context);
        }
        for (var wake : new TreeSet<>(observation.wakeUps()))
            if (wake.isAfter(observation.at()) && !wake.isAfter(observation.evaluationEnd()))
                result.add(new PlanningAction(WAIT, observation.at(), wake, null, null, null));
        // Every nonempty horizon has its terminal boundary, even for a manually constructed observation.
        var terminalWait = new PlanningAction(WAIT, observation.at(), observation.evaluationEnd(), null, null, null);
        if (!result.contains(terminalWait)) result.add(terminalWait);
        result.sort(new PlanningResultComparator()::compareActions);
        return List.copyOf(result);
    }

    private void addPrimitive(List<PlanningAction> output, PlanningObservation observation,
                              PlanningAction.Kind kind, ContextId context, KeyPairIds keys) {
        var primitive = SecurityAction.valueOf(kind.name());
        var now = observation.at();
        var end = now.plus(observation.costs().cost(primitive).duration());
        if (end.isAfter(observation.evaluationEnd())) return;
        var contact = observation.currentContact() == null ? null : observation.currentContact().window();
        if (primitive.requiresContact()) {
            if (observation.uncertainContexts().contains(context) || contact == null || !end.isBefore(contact.end())) return;
            if (observation.inFlightFrame() != null) return;
            if (observation.ongoingOperations().stream().anyMatch(o -> o.contextId().equals(context) && o.action().requiresContact())) return;
        }
        var intent = new ScheduledSecurityAction(primitive, now, context, keys, "Nominal branch candidate");
        try {
            var executor = new SecurityActionExecutor(observation.constraints());
            executor.admit(observation.securityState(), intent, primitive, primitive.requiresContact() ? contact : null, now);
            // Ground preparation may legally straddle another context's LOS: a hypothetical immutable
            // state at completion without processing that LOS would falsely reject that preparation.
            if (primitive.requiresContact()) executor.admit(observation.securityState(), intent, primitive, contact, end);
        } catch (IllegalArgumentException rejected) { return; }
        Instant cursor = now;
        for (var phase : observation.costs().cost(primitive).phases()) {
            Instant phaseEnd = cursor.plus(phase.duration());
            String resource = switch (phase.resource()) {
                case GROUND_CPU -> "ground:" + context.groundStationId();
                case SPACECRAFT_CPU -> "spacecraft";
                case UPLINK, DOWNLINK -> "radio";
                case WAIT -> null;
            };
            if (resource != null) for (var reservation : observation.resourceReservations())
                if (resource.equals(reservation.resource()) && cursor.isBefore(reservation.until()) && reservation.from().isBefore(phaseEnd)) return;
            cursor = phaseEnd;
        }
        output.add(new PlanningAction(kind, now, end, context, keys, primitive.requiresContact() ? contact.id() : null));
    }

    private void addContinue(List<PlanningAction> output, PlanningObservation observation, SecurityContext context) {
        if (context.status() != AssociationStatus.OPERATIONAL || observation.inFlightFrame() != null
                || observation.uncertainContexts().contains(context.id())) return;
        var contact = observation.currentContact();
        if (contact == null || !contact.window().equals(context.activeContact())) return;
        if (observation.ongoingOperations().stream().anyMatch(o -> o.action().requiresContact()
                && contact.window().id().equals(o.contactId()))) return;
        var progress = observation.payloadProgress().getOrDefault(contact.window().id(), new ExecutionSnapshot.PayloadProgress(0, 0, true));
        var payload = contact.payload();
        boolean uplink = progress.nextUplink();
        if (!hasBudget(payload, progress, uplink)) uplink = !uplink;
        if (!hasBudget(payload, progress, uplink)) return;
        var end = observation.at().plus(payload.frameDuration());
        if (end.isAfter(observation.evaluationEnd())) return;
        if (!new SecurityConstraintChecker(observation.constraints()).canProtect(observation.securityState(), context.id(),
                observation.at(), end, uplink ? 1 : 0, uplink ? 0 : 1).allowed()) return;
        output.add(new PlanningAction(CONTINUE, observation.at(), end, context.id(), null, contact.window().id()));
    }

    private boolean hasBudget(SimulationContact.PayloadProfile profile, ExecutionSnapshot.PayloadProgress progress, boolean uplink) {
        return (uplink ? profile.offeredUplinkBytes() - progress.uplinkBytes() : profile.offeredDownlinkBytes() - progress.downlinkBytes())
                >= profile.frameBytes();
    }
}
