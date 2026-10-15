package org.contactaware.security;

import java.time.Duration;
import java.util.Objects;
import java.util.Set;

/** Shared limits and currently observed authorization information, not policy preferences. */
public record SecurityConstraints(Duration cryptoperiod, Duration maxMaterialAge, Duration maxReuseGap,
                                  Set<ContextId> authorizedContexts, Set<String> blockedKeyIds) {
    public SecurityConstraints {
        positive(cryptoperiod, "cryptoperiod");
        positive(maxMaterialAge, "maxMaterialAge");
        Objects.requireNonNull(maxReuseGap, "maxReuseGap");
        SecurityChecks.require(!maxReuseGap.isNegative(), "maxReuseGap must be nonnegative");
        authorizedContexts = Set.copyOf(authorizedContexts);
        blockedKeyIds = Set.copyOf(blockedKeyIds);
        blockedKeyIds.forEach(id -> SecurityChecks.text(id, "blocked key id"));
    }

    private static void positive(Duration value, String name) {
        Objects.requireNonNull(value, name);
        SecurityChecks.require(!value.isNegative() && !value.isZero(), name + " must be positive");
    }
}
