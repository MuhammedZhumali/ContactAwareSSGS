package org.contactaware.security.simulation;

import org.contactaware.security.*;
import java.math.BigInteger;
import java.util.*;

/** Pure allocation preview; issuance is recorded only when the selected attempt actually starts. */
public final class KeyGenerationIds {
    private KeyGenerationIds() {}

    public static KeyPairIds next(ContextId context, Collection<String> issuedIds, Collection<String> blockedIds) {
        Objects.requireNonNull(context); Objects.requireNonNull(issuedIds); Objects.requireNonNull(blockedIds);
        String prefix = "planner-key:" + segment(context.satelliteId()) + segment(context.groundStationId()) + "g";
        BigInteger highest = BigInteger.ZERO;
        var unavailable = new HashSet<>(issuedIds);
        unavailable.addAll(blockedIds);
        for (var id : issuedIds) {
            SearchControl.checkpoint("generation.issuedKey");
            if (!id.startsWith(prefix)) continue;
            String suffix = id.substring(prefix.length());
            int colon = suffix.indexOf(':');
            if (colon <= 0 || !(suffix.substring(colon).equals(":up") || suffix.substring(colon).equals(":down"))) continue;
            String number = suffix.substring(0, colon);
            if (!number.chars().allMatch(c -> c >= '0' && c <= '9')) continue;
            highest = highest.max(new BigInteger(number));
        }
        for (var generation = highest.add(BigInteger.ONE); ; generation = generation.add(BigInteger.ONE)) {
            SearchControl.checkpoint("generation.keyId");
            var pair = new KeyPairIds(prefix + generation + ":up", prefix + generation + ":down");
            if (!unavailable.contains(pair.uplink()) && !unavailable.contains(pair.downlink())) return pair;
        }
    }

    private static String segment(String value) { return value.length() + ":" + value + ":"; }
}
