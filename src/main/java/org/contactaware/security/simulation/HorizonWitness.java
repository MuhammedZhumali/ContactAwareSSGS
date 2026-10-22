package org.contactaware.security.simulation;

import org.contactaware.contacts.Model.ContactWindow;
import org.contactaware.security.*;
import java.time.*;
import java.util.*;
import static org.contactaware.security.SecurityAction.*;

/** Synthetic input fixture and hand-written plans. No optimizer and no security policy implementation. */
public final class HorizonWitness {
    public enum Sequence { X, Y }
    public enum Plan { NEXT, MULTI, STAGED }
    public static final String SATELLITE = "WITNESS-SC";
    public static final Instant EPOCH = Instant.parse("2026-01-01T00:00:00Z");
    public static final ContextId A = new ContextId(SATELLITE, "A");
    public static final ContextId B = new ContextId(SATELLITE, "B");
    public static final KeyPairIds OLD_A = new KeyPairIds("A-old-up", "A-old-down");
    public static final KeyPairIds OLD_B = new KeyPairIds("B-old-up", "B-old-down");
    public static final KeyPairIds NEW_A = new KeyPairIds("A-new-up", "A-new-down");
    public static final SecurityConstraints CONSTRAINTS = new SecurityConstraints(Duration.ofSeconds(150),
            Duration.ofSeconds(400), Duration.ofSeconds(200), Set.of(A, B), Set.of());

    private HorizonWitness() {}
    public static Instant at(long seconds) { return EPOCH.plusSeconds(seconds); }
    public static ContactWindow window(String id, ContextId context, long start, long end) {
        return new ContactWindow(id, context.satelliteId(), context.groundStationId(), at(start), at(end), null, null, List.of());
    }

    public static SecurityState initialState() {
        var transitions = new LifecycleTransitions(CONSTRAINTS);
        var state = transitions.createContext(SecurityState.empty(SATELLITE, at(-90)), A, "A-SA-up", "A-SA-down", at(-90));
        state = transitions.createContext(state, B, "B-SA-up", "B-SA-down", at(-90));
        state = provisionHistory(transitions, state, A, OLD_A, -90, -80, -60);
        return provisionHistory(transitions, state, B, OLD_B, -40, -30, -20);
    }

    private static SecurityState provisionHistory(LifecycleTransitions transitions, SecurityState state, ContextId context,
                                                   KeyPairIds keys, long creation, long activation, long end) {
        var past = window("initial-" + context.groundStationId(), context, activation, end);
        state = transitions.prepareKeys(state, context, keys, at(creation));
        state = transitions.confirmInstallation(state, context, keys, past, at(activation));
        state = transitions.activateKeys(state, context, keys, past, at(activation));
        state = transitions.bindKeys(state, context, keys, past, at(activation));
        state = transitions.startContext(state, context, past, at(activation));
        state = transitions.recordProtectedTraffic(state, context, at(activation + 1), at(end), 1, 1);
        return transitions.stopContext(state, context, at(end));
    }

    public static PlannedOperation operation(String id, SecurityAction action, long time, ContextId context,
                                              KeyPairIds keys, String contact) {
        return new PlannedOperation(id, new ScheduledSecurityAction(action, at(time), context, keys, "Predetermined witness plan"), contact);
    }

    public static SimulationScenario scenario(Sequence sequence, Plan plan) {
        var payload = SimulationContact.PayloadProfile.syntheticWitness();
        var contacts = List.of(new SimulationContact(window("C1", A, 0, 20), payload),
                new SimulationContact(window("C2", B, 30, 50), payload),
                new SimulationContact(window("C3", sequence == Sequence.X ? A : B, 80, 85), payload));
        var actions = new ArrayList<PlannedOperation>();
        actions.add(operation("warmup", PREPARE, -2, A, NEW_A, null));
        if (sequence == Sequence.X && plan == Plan.MULTI)
            actions.add(operation("C1-rotate", ROTATE, 0, A, NEW_A, "C1"));
        else if (sequence == Sequence.X && plan == Plan.STAGED) {
            actions.add(operation("C1-install", INSTALL, 0, A, NEW_A, "C1"));
            actions.add(operation("C1-reuse", REUSE, 4, A, null, "C1"));
        } else actions.add(operation("C1-reuse", REUSE, 0, A, null, "C1"));
        actions.add(operation("C2-reuse", REUSE, 30, B, null, "C2"));
        if (sequence == Sequence.Y) actions.add(operation("C3-reuse", REUSE, 80, B, null, "C3"));
        else if (plan == Plan.MULTI) actions.add(operation("C3-reuse", REUSE, 80, A, null, "C3"));
        else if (plan == Plan.STAGED) actions.add(operation("C3-switch-open", ROTATE, 80, A, NEW_A, "C3"));
        return new SimulationScenario(sequence + "-" + plan, at(-2), at(85), initialState(), CONSTRAINTS,
                SecurityCostProfile.syntheticWitness(), contacts, actions);
    }
}
