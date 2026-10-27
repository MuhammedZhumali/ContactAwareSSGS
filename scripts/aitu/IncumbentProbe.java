package org.contactaware.security.simulation;

import java.util.*;

/** Observational instrumentation, used only by the separately hashed diagnostic overlay. */
public final class IncumbentProbe {
    public record Improvement(long nodes, long elapsedNanos, PlanningResult result) { }
    private static final ThreadLocal<List<Improvement>> EVENTS = ThreadLocal.withInitial(ArrayList::new);
    public static void clear() { EVENTS.get().clear(); }
    public static void improved(long nodes, long elapsedNanos, PlanningResult result) {
        EVENTS.get().add(new Improvement(nodes, elapsedNanos, result));
    }
    public static List<Improvement> events() { return List.copyOf(EVENTS.get()); }
}
