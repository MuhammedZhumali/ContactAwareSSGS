package org.contactaware.security.simulation;

import java.util.Comparator;
import java.util.List;

/**
 * Shared deterministic order: a negative result means that the first continuation is preferred.
 * Duration/Instant comparisons retain exact nanoseconds without converting to overflowing long totals.
 */
public final class PlanningResultComparator implements Comparator<PlanningResult> {
    private final PlanTieBreak tieBreak;
    public PlanningResultComparator() { this(PlanTieBreak.CANONICAL); }
    public PlanningResultComparator(PlanTieBreak tieBreak) { this.tieBreak = java.util.Objects.requireNonNull(tieBreak); }
    public enum Criterion {
        SERVED_CONTACTS, USEFUL_TIME, CONTROL_CPU, MANAGEMENT_BYTES, NEW_KEYS, CANONICAL_PLAN, EQUAL
    }

    @Override public int compare(PlanningResult first, PlanningResult second) {
        int metrics = compareMetrics(first.evaluation(), second.evaluation());
        return metrics != 0 ? metrics : comparePlans(first.plan(), second.plan());
    }

    public int compareMetrics(BranchEvaluation first, BranchEvaluation second) {
        int order = Long.compare(second.servedContacts(), first.servedContacts());
        if (order != 0) return order;
        order = second.usefulSecureTime().compareTo(first.usefulSecureTime());
        if (order != 0) return order;
        order = first.controlCpu().compareTo(second.controlCpu());
        if (order != 0) return order;
        order = Long.compare(first.managementBytes(), second.managementBytes());
        return order != 0 ? order : Long.compare(first.newKeys(), second.newKeys());
    }

    /**
     * Canonical action tuple: kind, start, station, context, uplink/downlink IDs, until, contact ID.
     * Null sorts before a value; a proper prefix sorts before its extension. The final two fields
     * distinguish otherwise identical WAIT choices with different targets or contact bindings.
     */
    public int comparePlans(List<PlanningAction> first, List<PlanningAction> second) {
        for (int i = 0; i < Math.min(first.size(), second.size()); i++) {
            int order = compareActions(first.get(i), second.get(i));
            if (order != 0) return order;
        }
        return Integer.compare(first.size(), second.size());
    }

    public int compareActions(PlanningAction first, PlanningAction second) {
        if (tieBreak == PlanTieBreak.CONTINUE_WAIT) {
            int group = Integer.compare(group(first.kind()), group(second.kind()));
            if (group != 0) return group;
            int until = first.until().compareTo(second.until());
            if (until != 0) return until;
        }
        int order = Integer.compare(rank(first.kind()), rank(second.kind()));
        if (order != 0) return order;
        order = first.at().compareTo(second.at());
        if (order != 0) return order;
        order = nullable(first.contextId() == null ? null : first.contextId().groundStationId(),
                second.contextId() == null ? null : second.contextId().groundStationId());
        if (order != 0) return order;
        order = nullable(first.contextId(), second.contextId());
        if (order != 0) return order;
        order = nullable(first.keys() == null ? null : first.keys().uplink(), second.keys() == null ? null : second.keys().uplink());
        if (order != 0) return order;
        order = nullable(first.keys() == null ? null : first.keys().downlink(), second.keys() == null ? null : second.keys().downlink());
        if (order != 0) return order;
        order = first.until().compareTo(second.until());
        return order != 0 ? order : nullable(first.contactId(), second.contactId());
    }

    public Criterion decisiveCriterion(PlanningResult first, PlanningResult second) {
        var a = first.evaluation();
        var b = second.evaluation();
        if (a.servedContacts() != b.servedContacts()) return Criterion.SERVED_CONTACTS;
        if (!a.usefulSecureTime().equals(b.usefulSecureTime())) return Criterion.USEFUL_TIME;
        if (!a.controlCpu().equals(b.controlCpu())) return Criterion.CONTROL_CPU;
        if (a.managementBytes() != b.managementBytes()) return Criterion.MANAGEMENT_BYTES;
        if (a.newKeys() != b.newKeys()) return Criterion.NEW_KEYS;
        return comparePlans(first.plan(), second.plan()) == 0 ? Criterion.EQUAL : Criterion.CANONICAL_PLAN;
    }

    private static int rank(PlanningAction.Kind kind) {
        return switch (kind) {
            case PREPARE -> 0;
            case INSTALL -> 1;
            case SWITCH -> 2;
            case OPEN -> 3;
            case REUSE -> 4;
            case CONTINUE -> 5;
            case WAIT -> 6;
        };
    }

    private static int group(PlanningAction.Kind kind) {
        return switch (kind) { case CONTINUE -> 0; case WAIT -> 1; default -> 2; };
    }

    private static <T extends Comparable<? super T>> int nullable(T first, T second) {
        if (first == null) return second == null ? 0 : -1;
        return second == null ? 1 : first.compareTo(second);
    }
}
