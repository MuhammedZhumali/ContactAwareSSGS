package org.contactaware.security.simulation;

import org.contactaware.security.ContextId;
import org.contactaware.security.SecurityAction;

import java.time.Duration;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/** Exact derived journal values for one immutable snapshot, never an equivalence relaxation. */
record PlanningJournalSummary(ExecutionSnapshot source, ResourceUsage recordedCosts,
                              Duration usefulSecureTime, long createdKeys, boolean rejected,
                              Map<ContextId, ResourceUsage> onlineByContext,
                              Map<ContextId, ResourceUsage> preparationByContext,
                              Map<ContextId, Long> createdByContext) {
    PlanningJournalSummary {
        Objects.requireNonNull(source);
        // HashMap copies retain the original folds' null-context behavior for diagnostic events.
        onlineByContext = Collections.unmodifiableMap(new HashMap<>(onlineByContext));
        preparationByContext = Collections.unmodifiableMap(new HashMap<>(preparationByContext));
        createdByContext = Collections.unmodifiableMap(new HashMap<>(createdByContext));
    }

    static PlanningJournalSummary from(ExecutionSnapshot snapshot) {
        Objects.requireNonNull(snapshot);
        var recorded = snapshot.carriedRecordedCosts();
        var useful = snapshot.carriedUsefulTime();
        long created = snapshot.carriedCreatedKeys();
        boolean rejected = false;
        var online = new HashMap<ContextId, ResourceUsage>();
        var preparation = new HashMap<ContextId, ResourceUsage>();
        var createdByContext = new HashMap<ContextId, Long>();
        for (var event : snapshot.journal()) {
            SearchControl.checkpoint("journalSummary.event");
            recorded = recorded.plus(event.usage());
            useful = useful.plus(event.usefulTime());
            created = Math.addExact(created, event.createdKeys());
            rejected |= event.type() == SimulationEvent.Type.ACTION_FAILED
                    || event.type() == SimulationEvent.Type.PAYLOAD_REJECTED;
            if (event.action() != null && event.action().requiresContact())
                online.merge(event.contextId(), event.usage(), ResourceUsage::plus);
            if (event.action() == SecurityAction.PREPARE)
                preparation.merge(event.contextId(), event.usage(), ResourceUsage::plus);
            if (event.createdKeys() > 0)
                createdByContext.merge(event.contextId(), event.createdKeys(), Math::addExact);
        }
        return new PlanningJournalSummary(snapshot, recorded, useful, created, rejected,
                online, preparation, createdByContext);
    }

    void requireSource(ExecutionSnapshot snapshot) {
        if (source != snapshot) throw new IllegalArgumentException("Journal summary belongs to another snapshot");
    }

    /** Pending costs retain the original step start and are never folded into recorded work. */
    ResourceUsage accruedCosts() {
        var total = recordedCosts;
        for (var pending : source.pendingOperations()) {
            SearchControl.checkpoint("journalSummary.pending");
            total = total.plus(source.scenario().costs().cost(pending.step())
                    .consumedUntil(Duration.between(pending.stepStartedAt(), source.at())));
        }
        return total;
    }
}
