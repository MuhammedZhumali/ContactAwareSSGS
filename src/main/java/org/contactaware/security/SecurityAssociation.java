package org.contactaware.security;

import java.util.Objects;

/** One directional SA. Its sequence number mirrors the bound key's persistent nonce ledger. */
public record SecurityAssociation(String id, ContextId contextId, Direction direction,
                                  AssociationStatus status, String keyId, long generation,
                                  long nextSequenceNumber, boolean peerSynchronized) {
    public SecurityAssociation {
        SecurityChecks.text(id, "SA id");
        Objects.requireNonNull(contextId, "contextId");
        Objects.requireNonNull(direction, "direction");
        Objects.requireNonNull(status, "status");
        SecurityChecks.require(generation >= 0 && nextSequenceNumber >= 0, "SA counters must be nonnegative");
        boolean bound = status == AssociationStatus.KEYED || status == AssociationStatus.OPERATIONAL;
        SecurityChecks.require(bound == (keyId != null), "Key binding must match SA status");
        if (bound) SecurityChecks.text(keyId, "bound key id");
        SecurityChecks.require(bound || !peerSynchronized, "Unbound SA cannot be synchronized");
        SecurityChecks.require(status != AssociationStatus.OPERATIONAL || peerSynchronized, "Operational SA must be synchronized");
    }

    public static SecurityAssociation absent(String id, ContextId contextId, Direction direction) {
        return new SecurityAssociation(id, contextId, direction, AssociationStatus.ABSENT, null, 0, 0, false);
    }

    SecurityAssociation unbound(AssociationStatus newStatus) {
        return new SecurityAssociation(id, contextId, direction, newStatus, null, generation, nextSequenceNumber, false);
    }

    SecurityAssociation boundTo(KeyState key) {
        return new SecurityAssociation(id, contextId, direction, AssociationStatus.KEYED, key.id(),
                Math.incrementExact(generation), key.nextNonceCounter(), true);
    }

    SecurityAssociation withStatus(AssociationStatus newStatus) {
        return new SecurityAssociation(id, contextId, direction, newStatus, keyId, generation, nextSequenceNumber, peerSynchronized);
    }

    SecurityAssociation withCounter(long counter) {
        return new SecurityAssociation(id, contextId, direction, status, keyId, generation, counter, peerSynchronized);
    }
}
