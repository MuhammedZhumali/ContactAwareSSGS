package org.contactaware.security;

import java.time.Instant;
import java.util.*;

/** Immutable domain snapshot. asOf is the last applied transition, not a wall-clock source. */
public record SecurityState(String satelliteId, Instant asOf,
                            Map<ContextId, SecurityContext> contexts, Map<String, KeyState> keys) {
    public SecurityState {
        SecurityChecks.text(satelliteId, "satelliteId");
        Objects.requireNonNull(asOf, "asOf");
        contexts = Collections.unmodifiableMap(new TreeMap<>(contexts));
        keys = Collections.unmodifiableMap(new TreeMap<>(keys));
        Set<String> saIds = new HashSet<>();
        Set<String> boundKeys = new HashSet<>();
        int operationalContexts = 0;
        for (var entry : keys.entrySet()) {
            var key = Objects.requireNonNull(entry.getValue(), "key");
            SecurityChecks.require(entry.getKey().equals(key.id()), "Key map identity mismatch");
            SecurityChecks.require(satelliteId.equals(key.contextId().satelliteId()), "Mixed satellites");
            SecurityChecks.require(contexts.containsKey(key.contextId()), "Key context is missing");
            for (Instant time : Arrays.asList(key.createdAt(), key.activatedAt(), key.deactivatedAt(), key.destroyedAt(), key.lastSuccessfulContactEnd()))
                if (time != null) SecurityChecks.require(!time.isAfter(asOf), "Key timestamp is later than snapshot");
        }
        for (var entry : contexts.entrySet()) {
            var context = Objects.requireNonNull(entry.getValue(), "context");
            SecurityChecks.require(entry.getKey().equals(context.id()), "Context map identity mismatch");
            SecurityChecks.require(satelliteId.equals(context.id().satelliteId()), "Mixed satellites");
            if (context.status() == AssociationStatus.OPERATIONAL) {
                operationalContexts++;
                SecurityChecks.require(!context.openedAt().isAfter(asOf), "Session opens after snapshot");
                SecurityChecks.require(!asOf.isAfter(context.activeContact().end()), "Close session before advancing past contact end");
            }
            for (var sa : List.of(context.uplink(), context.downlink())) {
                SecurityChecks.require(saIds.add(sa.id()), "Duplicate SA id: " + sa.id());
                if (sa.keyId() == null) continue;
                var key = keys.get(sa.keyId());
                SecurityChecks.require(key != null && key.status() == KeyStatus.ACTIVE, "Bound key must exist and be active");
                SecurityChecks.require(sa.contextId().equals(key.contextId()) && sa.direction() == key.direction(), "Key scope mismatch");
                SecurityChecks.require(boundKeys.add(key.id()), "A key cannot be bound to multiple SAs");
                SecurityChecks.require(sa.nextSequenceNumber() == key.nextNonceCounter(), "SA/key counter mismatch");
                if (context.status() == AssociationStatus.OPERATIONAL)
                    SecurityChecks.require(!key.activatedAt().isAfter(context.openedAt()), "Key must be active when session opens");
            }
        }
        SecurityChecks.require(operationalContexts <= 1, "Only one simultaneous spacecraft link is allowed");
    }

    public static SecurityState empty(String satelliteId, Instant at) {
        return new SecurityState(satelliteId, at, Map.of(), Map.of());
    }

    public SecurityContext context(ContextId id) {
        var context = contexts.get(id);
        if (context == null) throw new IllegalArgumentException("Unknown context: " + id);
        return context;
    }

    public KeyState key(String id) {
        var key = keys.get(id);
        if (key == null) throw new IllegalArgumentException("Unknown key: " + id);
        return key;
    }

    SecurityState replace(Instant at, SecurityContext context, Collection<KeyState> updatedKeys) {
        SecurityChecks.notBefore(at, asOf, "Transition time");
        var nextContexts = new TreeMap<>(contexts);
        if (context != null) nextContexts.put(context.id(), context);
        var nextKeys = new TreeMap<>(keys);
        updatedKeys.forEach(key -> nextKeys.put(key.id(), key));
        return new SecurityState(satelliteId, at, nextContexts, nextKeys);
    }
}
