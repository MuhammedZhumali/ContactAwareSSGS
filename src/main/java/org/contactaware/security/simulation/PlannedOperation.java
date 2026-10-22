package org.contactaware.security.simulation;

import org.contactaware.security.*;
import java.time.Instant;
import java.util.*;

/** Predetermined intent and deterministic execution controls; no policy or hidden future decision. */
public record PlannedOperation(String id, ScheduledSecurityAction intent, String contactId,
                               Instant interruptAt, Set<SecurityAction> missingAcknowledgements) {
    public PlannedOperation {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("Operation ID is required");
        Objects.requireNonNull(intent);
        missingAcknowledgements = Set.copyOf(missingAcknowledgements);
        if (intent.action().requiresContact() && (contactId == null || contactId.isBlank()))
            throw new IllegalArgumentException("Online action requires a contact ID");
        if (interruptAt != null && interruptAt.isBefore(intent.scheduledAt()))
            throw new IllegalArgumentException("Interruption precedes action");
        if (!Set.of(SecurityAction.INSTALL, SecurityAction.SWITCH, SecurityAction.OPEN, SecurityAction.REUSE, SecurityAction.NOTIFY_REVOCATION)
                .containsAll(missingAcknowledgements))
            throw new IllegalArgumentException("Only confirmed online steps have ACK controls");
        var applicable = switch (intent.action()) {
            case ROTATE, ESTABLISH -> Set.of(SecurityAction.INSTALL, SecurityAction.SWITCH, SecurityAction.OPEN);
            case INSTALL, SWITCH, OPEN, REUSE, NOTIFY_REVOCATION -> Set.of(intent.action());
            default -> Set.<SecurityAction>of();
        };
        if (!applicable.containsAll(missingAcknowledgements))
            throw new IllegalArgumentException("ACK control does not belong to this action");
    }

    public PlannedOperation(String id, ScheduledSecurityAction intent, String contactId) {
        this(id, intent, contactId, null, Set.of());
    }
}
