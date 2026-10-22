package org.contactaware.security;

import java.time.Instant;
import java.util.Objects;

/** Policy intent only. scheduledAt is an intended start, not a successful operation commit. */
public record ScheduledSecurityAction(SecurityAction action, Instant scheduledAt, ContextId contextId,
                                      KeyPairIds newKeys, String reason) {
    public ScheduledSecurityAction {
        Objects.requireNonNull(action, "action");
        Objects.requireNonNull(scheduledAt, "scheduledAt");
        Objects.requireNonNull(contextId, "contextId");
        SecurityChecks.text(reason, "decision reason");
        SecurityChecks.require(action.needsKeyIds() == (newKeys != null), "New key IDs must match the action type");
    }
}
