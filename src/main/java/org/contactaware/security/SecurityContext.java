package org.contactaware.security;

import org.contactaware.contacts.Model.ContactWindow;
import java.time.Instant;
import java.util.Objects;

/** Two directional SAs are managed together in v1; a radio session is separate from key lifetime. */
public record SecurityContext(ContextId id, SecurityAssociation uplink, SecurityAssociation downlink,
                              ContactWindow activeContact, Instant openedAt,
                              long uplinkFramesInSession, long downlinkFramesInSession) {
    public SecurityContext {
        Objects.requireNonNull(id, "context id");
        Objects.requireNonNull(uplink, "uplink SA");
        Objects.requireNonNull(downlink, "downlink SA");
        SecurityChecks.require(id.equals(uplink.contextId()) && id.equals(downlink.contextId()), "SA context mismatch");
        SecurityChecks.require(uplink.direction() == Direction.UPLINK && downlink.direction() == Direction.DOWNLINK, "SA direction mismatch");
        SecurityChecks.require(!uplink.id().equals(downlink.id()), "Directional SA IDs must be distinct");
        SecurityChecks.require(uplink.status() == downlink.status(), "Directional SAs must transition together in v1");
        SecurityChecks.require(uplink.keyId() == null || !uplink.keyId().equals(downlink.keyId()), "Directional keys must be distinct");
        SecurityChecks.require(uplinkFramesInSession >= 0 && downlinkFramesInSession >= 0, "Frame counts must be nonnegative");
        SecurityChecks.require(uplinkFramesInSession <= uplink.nextSequenceNumber() && downlinkFramesInSession <= downlink.nextSequenceNumber(),
                "Session frame counts cannot exceed sequence progress");
        boolean operational = uplink.status() == AssociationStatus.OPERATIONAL;
        SecurityChecks.require(operational == (activeContact != null) && operational == (openedAt != null), "Operational SAs need a contact session");
        if (operational) {
            SecurityChecks.require(matches(activeContact, id), "Contact context mismatch");
            SecurityChecks.require(!openedAt.isBefore(activeContact.start()) && openedAt.isBefore(activeContact.end()), "Session must open inside contact");
        } else {
            SecurityChecks.require(uplinkFramesInSession == 0 && downlinkFramesInSession == 0, "Closed session cannot contain frame counts");
        }
    }

    public AssociationStatus status() { return uplink.status(); }
    public SecurityAssociation association(Direction direction) {
        return switch (direction) { case UPLINK -> uplink; case DOWNLINK -> downlink; };
    }

    public KeyPairIds boundKeys() {
        SecurityChecks.require(uplink.keyId() != null, "Context has no bound keys");
        return new KeyPairIds(uplink.keyId(), downlink.keyId());
    }

    public static boolean matches(ContactWindow contact, ContextId id) {
        return contact.satelliteId().equals(id.satelliteId()) && contact.groundStationId().equals(id.groundStationId());
    }

    SecurityContext closed(SecurityAssociation up, SecurityAssociation down) {
        return new SecurityContext(id, up, down, null, null, 0, 0);
    }
}
