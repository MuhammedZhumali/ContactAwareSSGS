package org.contactaware.security.simulation;

import java.util.*;

/** One immutable continuation. The original visible forecast never rolls forward inside a branch. */
public record PlanningBranch(PlanningObservation origin, ExecutionSnapshot snapshot, List<PlanningAction> plan) {
    public PlanningBranch {
        Objects.requireNonNull(origin); Objects.requireNonNull(snapshot);
        plan = List.copyOf(plan);
        if (snapshot.executionMode() != ExecutionSnapshot.ExecutionMode.PLANNING_BRANCH
                || snapshot.at().isBefore(origin.at()) || snapshot.at().isAfter(origin.evaluationEnd())
                || !snapshot.scenario().contacts().equals(origin.visibleContacts())
                || !snapshot.scenario().plan().isEmpty()
                || !snapshot.scenario().end().equals(origin.evaluationEnd())
                || !snapshot.scenario().constraints().equals(origin.constraints())
                || !snapshot.scenario().costs().equals(origin.costs())
                || !snapshot.carriedRecordedCosts().equals(origin.recordedCosts())
                || !snapshot.carriedUsefulTime().equals(origin.usefulSecureTime())
                || snapshot.carriedCreatedKeys() != origin.createdKeys())
            throw new IllegalArgumentException("Branch must stay inside its original observation");
    }

    public PlanningObservation observation() {
        // This snapshot's scenario contains only the original visible contacts, so taking
        // all remaining contacts cannot refill the horizon with a hidden contact.
        var current = PlanningObservation.from(snapshot, Integer.MAX_VALUE);
        return new PlanningObservation(current.at(), origin.horizon(), current.currentContact(),
                current.visibleContacts(), origin.evaluationEnd(), current.securityState(), current.constraints(),
                current.costs(), current.ongoingOperations(), current.resourceReservations(), current.uncertainContexts(),
                current.payloadProgress(), current.inFlightFrame(), current.recordedCosts(), current.accruedCosts(),
                current.usefulSecureTime(), current.createdKeys(), current.wakeUps(), current.issuedKeyIds(),
                current.issuedOperationIds());
    }
}
