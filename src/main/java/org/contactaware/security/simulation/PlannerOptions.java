package org.contactaware.security.simulation;

import java.util.Objects;

/** Exact accelerations; cache capacity affects speed, never the explored feasible space. */
public record PlannerOptions(boolean collapseEquivalentWaits, boolean memoize,
                             TraversalOrder traversalOrder, int maxMemoEntries, boolean optimisticBounds,
                             boolean preExecutionWaitReduction, boolean canonicalBoundPruning) {
    public enum TraversalOrder { CANONICAL, SERVICE_FIRST, RENEWAL_FIRST }
    public PlannerOptions {
        Objects.requireNonNull(traversalOrder);
        if (maxMemoEntries < 0) throw new IllegalArgumentException("Negative memo capacity");
    }
    public PlannerOptions(boolean collapseEquivalentWaits, boolean memoize, TraversalOrder traversalOrder, int maxMemoEntries) {
        this(collapseEquivalentWaits, memoize, traversalOrder, maxMemoEntries, false);
    }
    public PlannerOptions(boolean collapseEquivalentWaits, boolean memoize, TraversalOrder traversalOrder,
                          int maxMemoEntries, boolean optimisticBounds) {
        this(collapseEquivalentWaits, memoize, traversalOrder, maxMemoEntries, optimisticBounds, false, false);
    }
    public static PlannerOptions reference() {
        return new PlannerOptions(false, false, TraversalOrder.CANONICAL, 0);
    }
    public static PlannerOptions accelerated() {
        return new PlannerOptions(true, true, TraversalOrder.RENEWAL_FIRST, 50_000, true, true, true);
    }
    /**
     * SE2.2.1 opt-in: visit payload/service continuations before new preparation.
     * Only traversal changes; the selected full-plan comparator and exact reductions do not.
     * Existing constructors/defaults deliberately retain the frozen renewal-first order.
     */
    public static PlannerOptions acceleratedServiceFirst() {
        return new PlannerOptions(true, true, TraversalOrder.SERVICE_FIRST, 50_000, true, true, true);
    }
    public static PlannerOptions reuseOnly() {
        return new PlannerOptions(true, true, TraversalOrder.SERVICE_FIRST, 50_000, false);
    }
}
