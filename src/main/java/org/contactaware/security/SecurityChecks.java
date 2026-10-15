package org.contactaware.security;

import java.time.Instant;
import java.util.Objects;

final class SecurityChecks {
    private SecurityChecks() {}

    static void require(boolean condition, String message) {
        if (!condition) throw new IllegalArgumentException(message);
    }

    static void text(String value, String name) {
        require(value != null && !value.isBlank(), name + " must not be blank");
    }

    static void notBefore(Instant value, Instant lowerBound, String name) {
        Objects.requireNonNull(value, name);
        require(!value.isBefore(lowerBound), name + " must not precede " + lowerBound);
    }
}
