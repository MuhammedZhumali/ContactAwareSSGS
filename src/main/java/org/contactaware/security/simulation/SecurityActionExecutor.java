package org.contactaware.security.simulation;

import org.contactaware.contacts.Model.ContactWindow;
import org.contactaware.security.*;
import java.time.Instant;
import java.util.*;
import static org.contactaware.security.SecurityAction.*;

/** Stateless adapter: admission previews are discarded; only confirmed completions publish snapshots. */
public final class SecurityActionExecutor {
    private final LifecycleTransitions transitions;
    private final SecurityConstraintChecker checker;

    public SecurityActionExecutor(SecurityConstraints constraints) {
        transitions = new LifecycleTransitions(constraints);
        checker = new SecurityConstraintChecker(constraints);
    }

    public static final class Rejected extends IllegalArgumentException {
        private final String code;
        public Rejected(String code, String detail) { super(detail); this.code = code; }
        public String code() { return code; }
    }

    public List<SecurityAction> resolve(SecurityState state, ScheduledSecurityAction intent) {
        if (intent.action() != ROTATE && intent.action() != ESTABLISH) return List.of(intent.action());
        var context = state.context(intent.contextId());
        if (intent.action() == ESTABLISH && context.status() != AssociationStatus.UNKEYED)
            throw new Rejected("INVALID_SA_STATUS", "Establishment requires UNKEYED SAs");
        var up = state.keys().get(intent.newKeys().uplink());
        var down = state.keys().get(intent.newKeys().downlink());
        if ((up == null) != (down == null)) throw new Rejected("INVALID_KEY_PAIR", "Candidate must contain both directions");
        var steps = new ArrayList<SecurityAction>();
        if (up == null) {
            steps.add(PREPARE);
            steps.add(INSTALL);
        } else {
            if (up.status() != KeyStatus.PRE_ACTIVE || down.status() != KeyStatus.PRE_ACTIVE)
                throw new Rejected("INVALID_KEY_STATUS", "Renewal requires fresh prepared material");
            if (!up.installedOnBothPeers() || !down.installedOnBothPeers()) steps.add(INSTALL);
        }
        steps.add(SWITCH);
        steps.add(OPEN);
        return List.copyOf(steps);
    }

    public void admit(SecurityState state, ScheduledSecurityAction intent, SecurityAction step, ContactWindow contact, Instant at) {
        complete(state, intent, step, contact, at); // Immutable preview, never assigned to the running state.
    }

    public SecurityState complete(SecurityState state, ScheduledSecurityAction intent, SecurityAction step,
                                  ContactWindow contact, Instant at) {
        try {
            var id = intent.contextId();
            var keys = intent.newKeys();
            if (step.requiresContact() && contact == null) throw new Rejected("OUTSIDE_CONTACT", "Online step needs a contact");
            return switch (step) {
                case PREPARE -> transitions.prepareKeys(state, id, keys, at);
                case INSTALL -> {
                    allowed(checker.canInstallKeys(state, id, keys, contact, at));
                    yield transitions.confirmInstallation(state, id, keys, contact, at);
                }
                case SWITCH -> {
                    allowed(checker.canActivateKeys(state, id, keys, contact, at));
                    var next = state;
                    if (next.context(id).status() == AssociationStatus.OPERATIONAL)
                        next = transitions.stopContext(next, id, at);
                    if (next.context(id).status() == AssociationStatus.KEYED) {
                        var oldKeys = next.context(id).boundKeys();
                        next = transitions.unbindKeys(next, id, at);
                        next = transitions.deactivateKeys(next, oldKeys, DeactivationReason.ROTATED, at);
                    }
                    next = transitions.activateKeys(next, id, keys, contact, at);
                    yield transitions.bindKeys(next, id, keys, contact, at);
                }
                case OPEN, REUSE -> {
                    allowed(step == REUSE ? checker.canReuseContext(state, id, contact, at)
                            : checker.canStartContext(state, id, contact, at));
                    yield transitions.startContext(state, id, contact, at);
                }
                case STOP -> transitions.stopContext(state, id, at);
                case EXPIRE_CONTEXT -> transitions.expireContext(state, id, DeactivationReason.POLICY_RETIRED, at);
                case NOTIFY_REVOCATION -> {
                    if (!SecurityContext.matches(contact, id) || at.isBefore(contact.start()) || !at.isBefore(contact.end()))
                        throw new Rejected("OUTSIDE_CONTACT", "Revocation delivery needs the matching open contact");
                    if (!state.contexts().containsKey(id) || !state.key(keys.uplink()).contextId().equals(id)
                            || !state.key(keys.downlink()).contextId().equals(id))
                        throw new Rejected("INVALID_KEY_PAIR", "Revocation names a known pair from this context");
                    // Independent authenticated management credential is an explicit model assumption.
                    // Ground admission already retired affected traffic keys; receipt does not rebind SAs.
                    yield state;
                }
                default -> throw new Rejected("INVALID_ACTION", "Execute resolved primitive steps, not " + step);
            };
        } catch (Rejected rejection) {
            throw rejection;
        } catch (IllegalArgumentException failure) {
            throw new Rejected("INVALID_TRANSITION", failure.getMessage());
        }
    }

    private static void allowed(SecurityConstraintChecker.Result result) {
        if (!result.allowed())
            throw new Rejected(result.violations().getFirst().code().name(), result.violations().toString());
    }
}
