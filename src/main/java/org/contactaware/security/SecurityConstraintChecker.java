package org.contactaware.security;

import org.contactaware.contacts.Model.ContactWindow;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

/** Read-only admission checks. Call again at actual completion/use time, not just at planning time. */
public final class SecurityConstraintChecker {
    public enum Code {
        TIME_BEFORE_STATE, UNKNOWN_CONTEXT, UNKNOWN_KEY, UNAUTHORIZED_CONTEXT, KEY_SCOPE_MISMATCH,
        INVALID_KEY_STATUS, KEY_NOT_INSTALLED, KEY_BLOCKED, KEY_EXPIRED, INVALID_SA_STATUS,
        PEER_NOT_SYNCHRONIZED, CONTACT_MISMATCH, OUTSIDE_CONTACT, LINK_BUSY,
        REUSE_GAP_EXCEEDED, INVALID_REUSE_ORDER, NO_PREVIOUS_SUCCESS, INVALID_INTERVAL, COUNTER_EXHAUSTED
    }

    public record Violation(Code code, String message) {}

    public record Result(List<Violation> violations) {
        public Result { violations = List.copyOf(violations); }
        public boolean allowed() { return violations.isEmpty(); }
        public void requireAllowed() {
            if (!allowed()) throw new IllegalArgumentException("Security constraints rejected operation: " + violations);
        }
    }

    private final SecurityConstraints constraints;

    public SecurityConstraintChecker(SecurityConstraints constraints) {
        this.constraints = Objects.requireNonNull(constraints, "constraints");
    }

    public Result canInstallKeys(SecurityState state, ContextId id, KeyPairIds keys, ContactWindow contact, Instant at) {
        return checkPairOperation(state, id, keys, contact, at, KeyStatus.PRE_ACTIVE, false, false);
    }

    public Result canActivateKeys(SecurityState state, ContextId id, KeyPairIds keys, ContactWindow contact, Instant at) {
        return checkPairOperation(state, id, keys, contact, at, KeyStatus.PRE_ACTIVE, true, false);
    }

    public Result canBindKeys(SecurityState state, ContextId id, KeyPairIds keys, ContactWindow contact, Instant at) {
        return checkPairOperation(state, id, keys, contact, at, KeyStatus.ACTIVE, true, true);
    }

    public Result canStartContext(SecurityState state, ContextId id, ContactWindow contact, Instant at) {
        return checkStart(state, id, contact, at, false);
    }

    public Result canReuseContext(SecurityState state, ContextId id, ContactWindow contact, Instant at) {
        return checkStart(state, id, contact, at, true);
    }

    public Result canProtect(SecurityState state, ContextId id, Instant from, Instant until,
                             long uplinkFrames, long downlinkFrames) {
        var errors = new ArrayList<Violation>();
        var context = base(state, id, from, errors);
        add(errors, !from.isBefore(until), Code.INVALID_INTERVAL, "Protected interval must have positive duration");
        add(errors, uplinkFrames < 0 || downlinkFrames < 0 || uplinkFrames == 0 && downlinkFrames == 0,
                Code.INVALID_INTERVAL, "At least one protected frame is required; counts must be nonnegative");
        if (context == null) return new Result(errors);
        add(errors, context.status() != AssociationStatus.OPERATIONAL, Code.INVALID_SA_STATUS, "Context is not operational");
        if (context.activeContact() != null) {
            add(errors, from.isBefore(context.openedAt()) || until.isAfter(context.activeContact().end()),
                    Code.OUTSIDE_CONTACT, "Protected interval is outside the open session");
        }
        for (Direction direction : Direction.values()) {
            var sa = context.association(direction);
            if (sa.keyId() == null) continue;
            var key = state.key(sa.keyId());
            checkKey(key, id, direction, from, until, KeyStatus.ACTIVE, true, errors);
            add(errors, !sa.peerSynchronized(), Code.PEER_NOT_SYNCHRONIZED, "SA " + sa.id());
            long frames = direction == Direction.UPLINK ? uplinkFrames : downlinkFrames;
            add(errors, frames > Long.MAX_VALUE - key.nextNonceCounter(), Code.COUNTER_EXHAUSTED, "Key " + key.id());
        }
        return new Result(errors);
    }

    private Result checkPairOperation(SecurityState state, ContextId id, KeyPairIds keys, ContactWindow contact,
                                       Instant at, KeyStatus expected, boolean installed, boolean binding) {
        var errors = new ArrayList<Violation>();
        var context = base(state, id, at, errors);
        checkRadio(state, id, contact, at, errors);
        if (context != null)
            add(errors, context.status() == AssociationStatus.ABSENT, Code.INVALID_SA_STATUS, "Create context before online key management");
        if (binding && context != null)
            add(errors, context.status() != AssociationStatus.UNKEYED, Code.INVALID_SA_STATUS, "Binding requires UNKEYED SAs");
        for (Direction direction : Direction.values()) {
            var key = state.keys().get(keys.forDirection(direction));
            if (key == null) errors.add(new Violation(Code.UNKNOWN_KEY, keys.forDirection(direction)));
            else checkKey(key, id, direction, at, at, expected, installed, errors);
        }
        return new Result(errors);
    }

    private Result checkStart(SecurityState state, ContextId id, ContactWindow contact, Instant at, boolean reuseOnly) {
        var errors = new ArrayList<Violation>();
        var context = base(state, id, at, errors);
        checkRadio(state, id, contact, at, errors);
        if (context == null) return new Result(errors);
        add(errors, context.status() != AssociationStatus.KEYED, Code.INVALID_SA_STATUS, "Starting requires KEYED SAs");
        boolean previousSuccess = false;
        for (Direction direction : Direction.values()) {
            var sa = context.association(direction);
            add(errors, !sa.peerSynchronized(), Code.PEER_NOT_SYNCHRONIZED, "SA " + sa.id());
            if (sa.keyId() == null) continue;
            var key = state.key(sa.keyId());
            checkKey(key, id, direction, at, at, KeyStatus.ACTIVE, true, errors);
            if (key.lastSuccessfulContactEnd() != null) {
                previousSuccess = true;
                var gap = Duration.between(key.lastSuccessfulContactEnd(), contact.start());
                add(errors, gap.isNegative(), Code.INVALID_REUSE_ORDER, "Contact precedes previous successful use of " + key.id());
                add(errors, gap.compareTo(constraints.maxReuseGap()) > 0, Code.REUSE_GAP_EXCEEDED, "Key " + key.id());
            }
            add(errors, key.nextNonceCounter() == Long.MAX_VALUE, Code.COUNTER_EXHAUSTED, "Key " + key.id());
        }
        add(errors, reuseOnly && !previousSuccess, Code.NO_PREVIOUS_SUCCESS, "This is a first start, not reuse after a successful contact");
        return new Result(errors);
    }

    private SecurityContext base(SecurityState state, ContextId id, Instant at, List<Violation> errors) {
        Objects.requireNonNull(at, "time");
        add(errors, at.isBefore(state.asOf()), Code.TIME_BEFORE_STATE, "Cannot evaluate before snapshot time");
        add(errors, !constraints.authorizedContexts().contains(id), Code.UNAUTHORIZED_CONTEXT, "Context " + id);
        var context = state.contexts().get(id);
        add(errors, context == null, Code.UNKNOWN_CONTEXT, "Context " + id);
        return context;
    }

    private void checkKey(KeyState key, ContextId id, Direction direction, Instant from, Instant until,
                          KeyStatus expected, boolean installed, List<Violation> errors) {
        add(errors, !key.contextId().equals(id) || key.direction() != direction, Code.KEY_SCOPE_MISMATCH, "Key " + key.id());
        add(errors, key.status() != expected, Code.INVALID_KEY_STATUS, "Key " + key.id() + " must be " + expected);
        add(errors, installed && !key.installedOnBothPeers(), Code.KEY_NOT_INSTALLED, "Key " + key.id());
        add(errors, constraints.blockedKeyIds().contains(key.id()), Code.KEY_BLOCKED, "Received block for " + key.id());
        Instant deadline = key.hardDeadline(constraints);
        // [from, until) may end at the deadline, but an instantaneous start/commit at it is forbidden.
        add(errors, !from.isBefore(deadline) || until.isAfter(deadline), Code.KEY_EXPIRED, "Key " + key.id() + " deadline " + deadline);
    }

    private static void checkRadio(SecurityState state, ContextId id, ContactWindow contact, Instant at, List<Violation> errors) {
        Objects.requireNonNull(contact, "contact");
        add(errors, !SecurityContext.matches(contact, id), Code.CONTACT_MISMATCH, "Contact " + contact.id());
        add(errors, at.isBefore(contact.start()) || !at.isBefore(contact.end()), Code.OUTSIDE_CONTACT, "Commit must occur inside contact");
        for (var other : state.contexts().values()) {
            if (other.status() == AssociationStatus.OPERATIONAL)
                add(errors, !other.id().equals(id) || !other.activeContact().equals(contact), Code.LINK_BUSY, "Another contact is open");
        }
    }

    private static void add(List<Violation> errors, boolean failed, Code code, String message) {
        if (failed) errors.add(new Violation(code, message));
    }
}
