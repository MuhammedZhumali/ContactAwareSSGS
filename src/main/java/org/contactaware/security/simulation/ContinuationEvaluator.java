package org.contactaware.security.simulation;

import java.util.Map;
import java.util.Objects;

/** Score a branch using only realized progress through its current cutoff, including pending work. */
public final class ContinuationEvaluator {
    public PlanningResult evaluate(PlanningBranch branch) {
        Objects.requireNonNull(branch);
        return new PlanningResult(evaluate(branch.origin(), branch.snapshot()), branch.plan());
    }

    /**
     * The snapshot must belong to a nominal branch with a continuation-only journal.
     * Do not pass a privileged full-run snapshot whose journal includes earlier failed actions.
     */
    public BranchEvaluation evaluate(PlanningObservation origin, ExecutionSnapshot snapshot) {
        Objects.requireNonNull(origin); Objects.requireNonNull(snapshot);
        // The public convenience overload enforces the same forecast/cost provenance boundary
        // as the branch wrapper; an arbitrary full-run checkpoint is not an evaluation branch.
        new PlanningBranch(origin, snapshot, java.util.List.of());
        if (snapshot.at().isBefore(origin.at()) || snapshot.at().isAfter(origin.evaluationEnd()))
            throw new IllegalArgumentException("Continuation cutoff outside its fixed visible horizon");
        if (!snapshot.securityState().satelliteId().equals(origin.securityState().satelliteId()))
            throw new IllegalArgumentException("Continuation belongs to a different spacecraft");
        if (snapshot.journal().stream().anyMatch(event -> event.type() == SimulationEvent.Type.ACTION_FAILED
                || event.type() == SimulationEvent.Type.PAYLOAD_REJECTED))
            throw new IllegalArgumentException("Inadmissible continuation cannot compete on objective values");

        long initialServed = 0, finalServed = 0;
        for (var contact : origin.visibleContacts()) {
            if (served(contact, origin.payloadProgress())) initialServed = Math.addExact(initialServed, 1);
            if (served(contact, snapshot.payloadProgress())) finalServed = Math.addExact(finalServed, 1);
        }
        // accruedCosts already includes recordedCosts: adding both would charge old work twice.
        var previousCosts = origin.accruedCosts();
        var currentCosts = snapshot.accruedCosts();
        return new BranchEvaluation(Math.subtractExact(finalServed, initialServed),
                snapshot.usefulSecureTime().minus(origin.usefulSecureTime()),
                currentCosts.totalCpu().minus(previousCosts.totalCpu()),
                Math.subtractExact(currentCosts.managementBytes(), previousCosts.managementBytes()),
                Math.subtractExact(snapshot.createdKeys(), origin.createdKeys()));
    }

    private boolean served(SimulationContact contact, Map<String, ExecutionSnapshot.PayloadProgress> progress) {
        var counts = progress.get(contact.window().id());
        long up = counts == null ? 0 : counts.uplinkBytes();
        long down = counts == null ? 0 : counts.downlinkBytes();
        return up >= contact.payload().minimumUplinkBytes() && down >= contact.payload().minimumDownlinkBytes();
    }
}
