package org.contactaware.security.simulation;

import org.contactaware.security.*;
import java.time.Instant;
import java.util.Objects;

/** One immediate branch decision. Deferred starts are WAIT followed by a new decision. */
public record PlanningAction(Kind kind, Instant at, Instant until, ContextId contextId,
                             KeyPairIds keys, String contactId) {
    /** This declaration order is the shared canonical order, not a preference objective. */
    public enum Kind { PREPARE, INSTALL, SWITCH, OPEN, REUSE, CONTINUE, WAIT }

    public PlanningAction {
        Objects.requireNonNull(kind); Objects.requireNonNull(at); Objects.requireNonNull(until);
        if (!at.isBefore(until)) throw new IllegalArgumentException("Decision must have a positive interval");
        if (kind == Kind.WAIT) {
            if (contextId != null || keys != null || contactId != null)
                throw new IllegalArgumentException("WAIT has no security target");
        } else {
            Objects.requireNonNull(contextId);
            boolean needsKeys = kind == Kind.PREPARE || kind == Kind.INSTALL || kind == Kind.SWITCH;
            if (needsKeys != (keys != null)) throw new IllegalArgumentException("Key IDs do not match the action");
            if (kind == Kind.PREPARE ? contactId != null : contactId == null || contactId.isBlank())
                throw new IllegalArgumentException("Contact does not match the action");
        }
    }

    public boolean isSecurityOperation() { return kind != Kind.CONTINUE && kind != Kind.WAIT; }

    public SecurityAction securityAction() {
        if (!isSecurityOperation()) throw new IllegalStateException("Control decision has no security primitive");
        return SecurityAction.valueOf(kind.name());
    }
}
