package org.contactaware.security.simulation;

import org.contactaware.contacts.Model.ContactWindow;
import java.time.Duration;
import java.util.Objects;

/** Explicit synthetic/experimental traffic inputs, independent of geometric contact metadata. */
public record SimulationContact(ContactWindow window, PayloadProfile payload) {
    public SimulationContact { Objects.requireNonNull(window); Objects.requireNonNull(payload); }

    /** Fixed complete frames, alternating directions; budgets and minimum service are per direction. */
    public record PayloadProfile(long bytesPerSecond, long frameBytes, long offeredUplinkBytes, long offeredDownlinkBytes,
                                 long minimumUplinkBytes, long minimumDownlinkBytes) {
        public PayloadProfile {
            if (bytesPerSecond <= 0 || frameBytes <= 0 || offeredUplinkBytes < 0 || offeredDownlinkBytes < 0
                    || minimumUplinkBytes < 0 || minimumDownlinkBytes < 0
                    || minimumUplinkBytes == 0 && minimumDownlinkBytes == 0)
                throw new IllegalArgumentException("Invalid payload profile");
            long numerator = Math.multiplyExact(frameBytes, 1_000_000_000L);
            if (numerator % bytesPerSecond != 0 || numerator / bytesPerSecond == 0)
                throw new IllegalArgumentException("Frame duration must be an integral positive number of nanoseconds");
        }

        public Duration frameDuration() { return Duration.ofNanos(Math.multiplyExact(frameBytes, 1_000_000_000L) / bytesPerSecond); }

        public static PayloadProfile syntheticWitness() {
            return new PayloadProfile(400, 100, Long.MAX_VALUE, Long.MAX_VALUE, 400, 400);
        }
    }
}
