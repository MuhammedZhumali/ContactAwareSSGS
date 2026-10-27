package org.contactaware.security;

import org.contactaware.contacts.Model.ContactWindow;
import java.time.Instant;
import java.util.*;
import static org.contactaware.security.SecurityChecks.require;

/**
 * Pure domain transitions. Online methods represent already confirmed protocol steps at completedAt.
 * They do not execute messages, estimate costs, advance a simulation clock, or choose a policy.
 */
public final class LifecycleTransitions {
    private final SecurityConstraints constraints;
    private final SecurityConstraintChecker checker;

    public LifecycleTransitions(SecurityConstraints constraints) {
        this.constraints = Objects.requireNonNull(constraints, "constraints");
        this.checker = new SecurityConstraintChecker(constraints);
    }

    public SecurityState createContext(SecurityState state, ContextId id, String uplinkSaId, String downlinkSaId, Instant at) {
        require(constraints.authorizedContexts().contains(id), "Unauthorized context");
        var existing = state.contexts().get(id);
        SecurityAssociation up;
        SecurityAssociation down;
        if (existing == null) {
            up = SecurityAssociation.absent(uplinkSaId, id, Direction.UPLINK);
            down = SecurityAssociation.absent(downlinkSaId, id, Direction.DOWNLINK);
        } else {
            require(existing.status() == AssociationStatus.ABSENT, "Context already exists");
            require(existing.uplink().id().equals(uplinkSaId) && existing.downlink().id().equals(downlinkSaId), "SA identities cannot be reassigned");
            up = existing.uplink();
            down = existing.downlink();
        }
        var created = new SecurityContext(id, up.unbound(AssociationStatus.UNKEYED), down.unbound(AssociationStatus.UNKEYED), null, null, 0, 0);
        return state.replace(at, created, List.of());
    }

    public SecurityState deleteContext(SecurityState state, ContextId id, Instant at) {
        var context = state.context(id);
        require(context.status() == AssociationStatus.UNKEYED, "Only UNKEYED SAs can be deleted");
        var deleted = context.closed(context.uplink().unbound(AssociationStatus.ABSENT), context.downlink().unbound(AssociationStatus.ABSENT));
        return state.replace(at, deleted, List.of());
    }

    public SecurityState prepareKeys(SecurityState state, ContextId id, KeyPairIds ids, Instant at) {
        require(state.context(id).status() != AssociationStatus.ABSENT, "Create context before preparation");
        require(constraints.authorizedContexts().contains(id), "Unauthorized context");
        for (Direction direction : Direction.values()) {
            String keyId = ids.forDirection(direction);
            require(!state.keys().containsKey(keyId), "Key ID cannot be reused, including destroyed keys: " + keyId);
            require(!constraints.blockedKeyIds().contains(keyId), "Blocked key ID: " + keyId);
        }
        var keys = List.of(KeyState.prepared(ids.uplink(), id, Direction.UPLINK, at, constraints),
                KeyState.prepared(ids.downlink(), id, Direction.DOWNLINK, at, constraints));
        return state.replace(at, null, keys);
    }

    public SecurityState confirmInstallation(SecurityState state, ContextId id, KeyPairIds keys, ContactWindow contact, Instant completedAt) {
        checker.canInstallKeys(state, id, keys, contact, completedAt).requireAllowed();
        return state.replace(completedAt, null, pair(state, keys).stream().map(KeyState::installed).toList());
    }

    public SecurityState activateKeys(SecurityState state, ContextId id, KeyPairIds keys, ContactWindow contact, Instant completedAt) {
        checker.canActivateKeys(state, id, keys, contact, completedAt).requireAllowed();
        return state.replace(completedAt, null, pair(state, keys).stream().map(key -> key.activated(completedAt, constraints)).toList());
    }

    public SecurityState bindKeys(SecurityState state, ContextId id, KeyPairIds keys, ContactWindow contact, Instant completedAt) {
        checker.canBindKeys(state, id, keys, contact, completedAt).requireAllowed();
        var context = state.context(id);
        var bound = context.closed(context.uplink().boundTo(state.key(keys.uplink())), context.downlink().boundTo(state.key(keys.downlink())));
        return state.replace(completedAt, bound, List.of());
    }

    public SecurityState startContext(SecurityState state, ContextId id, ContactWindow contact, Instant completedAt) {
        checker.canStartContext(state, id, contact, completedAt).requireAllowed();
        var context = state.context(id);
        var opened = new SecurityContext(id, context.uplink().withStatus(AssociationStatus.OPERATIONAL),
                context.downlink().withStatus(AssociationStatus.OPERATIONAL), contact, completedAt, 0, 0);
        return state.replace(completedAt, opened, List.of());
    }

    /** Registers confirmed traffic over [from, until); no payload bytes or cryptographic operations are invented. */
    public SecurityState recordProtectedTraffic(SecurityState state, ContextId id, Instant from, Instant until,
                                                long uplinkFrames, long downlinkFrames) {
        checker.canProtect(state, id, from, until, uplinkFrames, downlinkFrames).requireAllowed();
        var context = state.context(id);
        var up = state.key(context.uplink().keyId());
        var down = state.key(context.downlink().keyId());
        var nextUp = up.withCounter(Math.addExact(up.nextNonceCounter(), uplinkFrames));
        var nextDown = down.withCounter(Math.addExact(down.nextNonceCounter(), downlinkFrames));
        var used = new SecurityContext(id, context.uplink().withCounter(nextUp.nextNonceCounter()),
                context.downlink().withCounter(nextDown.nextNonceCounter()), context.activeContact(), context.openedAt(),
                Math.addExact(context.uplinkFramesInSession(), uplinkFrames), Math.addExact(context.downlinkFramesInSession(), downlinkFrames));
        return state.replace(until, used, List.of(nextUp, nextDown));
    }

    /**
     * Consume the nonce of an already admitted, committed transmission that was not delivered.
     * This is accounting, not admission: its key may have been retired while a downlink was in
     * flight. Preserve status, binding, and successful-use/session history; never resurrect it.
     * The executor must call this exactly once per aborted frame, even for a zero-byte prefix.
     */
    public SecurityState recordAbortedTransmission(SecurityState state, String keyId, Instant at) {
        var key = state.key(keyId);
        require(key.activatedAt() != null, "An aborted transmission must use previously activated material");
        var updated = key.withCounter(Math.incrementExact(key.nextNonceCounter()));
        var context = state.context(key.contextId());
        var up = context.uplink();
        var down = context.downlink();
        if (keyId.equals(up.keyId())) up = up.withCounter(updated.nextNonceCounter());
        if (keyId.equals(down.keyId())) down = down.withCounter(updated.nextNonceCounter());
        var preserved = new SecurityContext(context.id(), up, down, context.activeContact(), context.openedAt(),
                context.uplinkFramesInSession(), context.downlinkFramesInSession());
        return state.replace(at, preserved, List.of(updated));
    }

    /** Planned local stop; an unsuccessful/empty session does not refresh reuse history. */
    public SecurityState stopContext(SecurityState state, ContextId id, Instant at) {
        var context = state.context(id);
        require(context.status() == AssociationStatus.OPERATIONAL, "Stop requires OPERATIONAL SAs");
        require(!at.isAfter(context.activeContact().end()), "Stop must occur no later than contact end");
        var keys = new ArrayList<KeyState>();
        if (context.uplinkFramesInSession() > 0) keys.add(state.key(context.uplink().keyId()).withSuccessfulContactEnd(at));
        if (context.downlinkFramesInSession() > 0) keys.add(state.key(context.downlink().keyId()).withSuccessfulContactEnd(at));
        var stopped = context.closed(context.uplink().withStatus(AssociationStatus.KEYED), context.downlink().withStatus(AssociationStatus.KEYED));
        return state.replace(at, stopped, keys);
    }

    /** Expire-SA semantics: detach keys without deactivating them. A later binding restores their nonce progress. */
    public SecurityState unbindKeys(SecurityState state, ContextId id, Instant at) {
        var context = state.context(id);
        require(context.status() == AssociationStatus.KEYED, "Unbinding requires KEYED SAs");
        return state.replace(at, unkeyed(context), List.of());
    }

    public SecurityState deactivateKeys(SecurityState state, KeyPairIds keys, DeactivationReason reason, Instant at) {
        Objects.requireNonNull(reason, "reason");
        for (var context : state.contexts().values()) {
            for (var sa : List.of(context.uplink(), context.downlink()))
                require(!Objects.equals(sa.keyId(), keys.uplink()) && !Objects.equals(sa.keyId(), keys.downlink()), "Detach keys before deactivation");
        }
        var retired = pair(state, keys).stream().map(key -> deactivate(key, reason, at)).toList();
        return state.replace(at, null, retired);
    }

    public SecurityState destroyKeys(SecurityState state, KeyPairIds keys, Instant at) {
        var destroyed = pair(state, keys).stream().map(key -> {
            require(key.status() == KeyStatus.DEACTIVATED, "Only deactivated keys can be destroyed");
            return key.destroyed(at);
        }).toList();
        return state.replace(at, null, destroyed);
    }

    /** Local safety/policy termination, not evidence of a remote deletion acknowledgement. */
    public SecurityState expireContext(SecurityState state, ContextId id, DeactivationReason reason, Instant at) {
        var context = state.context(id);
        require(context.status() == AssociationStatus.KEYED || context.status() == AssociationStatus.OPERATIONAL,
                "Expiration requires bound keys");
        var keys = pair(state, context.boundKeys());
        if (reason == DeactivationReason.EXPIRED) {
            require(keys.stream().anyMatch(key -> !at.isBefore(key.hardDeadline(constraints))), "No directional key has expired");
            // The paired context stops when its first directional key expires. Retire the other by policy.
            keys = keys.stream().map(key -> key.deactivated(at, at.isBefore(key.hardDeadline(constraints))
                    ? DeactivationReason.POLICY_RETIRED : DeactivationReason.EXPIRED)).toList();
        } else {
            keys = keys.stream().map(key -> deactivate(key, reason, at)).toList();
        }
        return state.replace(at, unkeyed(context), keys);
    }

    /** Deadline enforcement for an unbound candidate, independently of any other generation. */
    public SecurityState expireUnboundKey(SecurityState state, String keyId, Instant at) {
        var key = state.key(keyId);
        for (var context : state.contexts().values())
            require(!Objects.equals(context.uplink().keyId(), keyId) && !Objects.equals(context.downlink().keyId(), keyId),
                    "Use expireContext for a bound key");
        return state.replace(at, null, List.of(deactivate(key, DeactivationReason.EXPIRED, at)));
    }

    private KeyState deactivate(KeyState key, DeactivationReason reason, Instant at) {
        Objects.requireNonNull(reason, "reason");
        require(key.status() == KeyStatus.PRE_ACTIVE || key.status() == KeyStatus.ACTIVE, "Key is already retired");
        if (reason == DeactivationReason.EXPIRED) require(!at.isBefore(key.hardDeadline(constraints)), "Key has not expired");
        if (reason == DeactivationReason.CANCELLED_PREPARATION) require(key.status() == KeyStatus.PRE_ACTIVE, "Only preparation can be cancelled");
        return key.deactivated(at, reason);
    }

    private static SecurityContext unkeyed(SecurityContext context) {
        return context.closed(context.uplink().unbound(AssociationStatus.UNKEYED), context.downlink().unbound(AssociationStatus.UNKEYED));
    }

    private static List<KeyState> pair(SecurityState state, KeyPairIds ids) {
        var up = state.key(ids.uplink());
        var down = state.key(ids.downlink());
        require(up.direction() == Direction.UPLINK && down.direction() == Direction.DOWNLINK && up.contextId().equals(down.contextId()), "Invalid directional key pair");
        return List.of(up, down);
    }
}
