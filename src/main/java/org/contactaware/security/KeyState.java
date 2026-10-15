package org.contactaware.security;

import java.time.Instant;
import java.util.Objects;

/** Traffic-key metadata only. Nonce progress survives SA unbinding and never resets on reuse. */
public record KeyState(String id, ContextId contextId, Direction direction, Instant createdAt, Instant notAfter,
                       Instant activatedAt, KeyStatus status, boolean installedOnSpacecraft,
                       Instant deactivatedAt, DeactivationReason deactivationReason, Instant destroyedAt,
                       long nextNonceCounter, Instant lastSuccessfulContactEnd) {
    public KeyState {
        SecurityChecks.text(id, "key id");
        Objects.requireNonNull(contextId, "contextId");
        Objects.requireNonNull(direction, "direction");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(notAfter, "notAfter");
        SecurityChecks.require(createdAt.isBefore(notAfter), "Key lifetime must have positive duration");
        Objects.requireNonNull(status, "status");
        SecurityChecks.require(nextNonceCounter >= 0, "Nonce counter must be nonnegative");
        if (activatedAt != null) {
            SecurityChecks.notBefore(activatedAt, createdAt, "activatedAt");
            SecurityChecks.require(installedOnSpacecraft, "Activation requires confirmed installation on both peers");
            SecurityChecks.require(activatedAt.isBefore(notAfter), "Activation must precede the frozen key deadline");
        }
        SecurityChecks.require(status != KeyStatus.ACTIVE || activatedAt != null, "Active key needs activatedAt");
        SecurityChecks.require(status != KeyStatus.PRE_ACTIVE || activatedAt == null, "Pre-active key cannot have activatedAt");
        boolean retired = status == KeyStatus.DEACTIVATED || status == KeyStatus.DESTROYED;
        SecurityChecks.require(retired == (deactivatedAt != null) && retired == (deactivationReason != null),
                "Deactivation metadata must match key status");
        if (deactivatedAt != null) SecurityChecks.notBefore(deactivatedAt, activatedAt == null ? createdAt : activatedAt, "deactivatedAt");
        SecurityChecks.require((status == KeyStatus.DESTROYED) == (destroyedAt != null), "Destruction metadata must match key status");
        if (destroyedAt != null) SecurityChecks.notBefore(destroyedAt, deactivatedAt, "destroyedAt");
        SecurityChecks.require(nextNonceCounter == 0 || activatedAt != null, "Unused material cannot have nonce progress");
        if (lastSuccessfulContactEnd != null) {
            SecurityChecks.require(activatedAt != null && nextNonceCounter > 0, "Successful use needs activated material and nonce progress");
            SecurityChecks.notBefore(lastSuccessfulContactEnd, activatedAt, "lastSuccessfulContactEnd");
            if (deactivatedAt != null) SecurityChecks.notBefore(deactivatedAt, lastSuccessfulContactEnd, "deactivatedAt");
        }
    }

    public static KeyState prepared(String id, ContextId contextId, Direction direction, Instant at, SecurityConstraints constraints) {
        return new KeyState(id, contextId, direction, at, at.plus(constraints.maxMaterialAge()), null, KeyStatus.PRE_ACTIVE, false,
                null, null, null, 0, null);
    }

    /** The ground copy exists from preparation; spacecraft installation requires a later acknowledgement. */
    public boolean installedOnBothPeers() { return installedOnSpacecraft; }

    public Instant hardDeadline(SecurityConstraints constraints) {
        Instant configuredDeadline = createdAt.plus(constraints.maxMaterialAge());
        Instant materialDeadline = notAfter.isBefore(configuredDeadline) ? notAfter : configuredDeadline;
        if (activatedAt == null) return materialDeadline;
        Instant usageDeadline = activatedAt.plus(constraints.cryptoperiod());
        return materialDeadline.isBefore(usageDeadline) ? materialDeadline : usageDeadline;
    }

    KeyState installed() {
        return new KeyState(id, contextId, direction, createdAt, notAfter, activatedAt, status, true,
                deactivatedAt, deactivationReason, destroyedAt, nextNonceCounter, lastSuccessfulContactEnd);
    }

    KeyState activated(Instant at, SecurityConstraints constraints) {
        Instant usageDeadline = at.plus(constraints.cryptoperiod());
        Instant materialDeadline = hardDeadline(constraints);
        Instant deadline = materialDeadline.isBefore(usageDeadline) ? materialDeadline : usageDeadline;
        return new KeyState(id, contextId, direction, createdAt, deadline, at, KeyStatus.ACTIVE, installedOnSpacecraft,
                null, null, null, nextNonceCounter, lastSuccessfulContactEnd);
    }

    KeyState deactivated(Instant at, DeactivationReason reason) {
        return new KeyState(id, contextId, direction, createdAt, notAfter, activatedAt, KeyStatus.DEACTIVATED, installedOnSpacecraft,
                at, reason, null, nextNonceCounter, lastSuccessfulContactEnd);
    }

    KeyState destroyed(Instant at) {
        return new KeyState(id, contextId, direction, createdAt, notAfter, activatedAt, KeyStatus.DESTROYED, installedOnSpacecraft,
                deactivatedAt, deactivationReason, at, nextNonceCounter, lastSuccessfulContactEnd);
    }

    KeyState withCounter(long counter) {
        return new KeyState(id, contextId, direction, createdAt, notAfter, activatedAt, status, installedOnSpacecraft,
                deactivatedAt, deactivationReason, destroyedAt, counter, lastSuccessfulContactEnd);
    }

    KeyState withSuccessfulContactEnd(Instant at) {
        return new KeyState(id, contextId, direction, createdAt, notAfter, activatedAt, status, installedOnSpacecraft,
                deactivatedAt, deactivationReason, destroyedAt, nextNonceCounter, at);
    }
}
