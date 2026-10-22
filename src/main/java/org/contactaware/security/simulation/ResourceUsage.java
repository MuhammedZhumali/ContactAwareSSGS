package org.contactaware.security.simulation;

import java.time.Duration;
import java.util.Objects;

/** Control-plane work. Summed operation elapsed time is not the experiment wall time. */
public record ResourceUsage(Duration elapsed, Duration spacecraftCpu, Duration groundCpu,
                            Duration radioOccupancy, long uplinkBytes, long downlinkBytes) {
    public static final ResourceUsage ZERO = new ResourceUsage(Duration.ZERO, Duration.ZERO, Duration.ZERO, Duration.ZERO, 0, 0);

    public ResourceUsage {
        for (var duration : new Duration[]{elapsed, spacecraftCpu, groundCpu, radioOccupancy})
            if (Objects.requireNonNull(duration).isNegative()) throw new IllegalArgumentException("Negative resource usage");
        if (uplinkBytes < 0 || downlinkBytes < 0) throw new IllegalArgumentException("Negative byte count");
    }

    public ResourceUsage plus(ResourceUsage other) {
        return new ResourceUsage(elapsed.plus(other.elapsed), spacecraftCpu.plus(other.spacecraftCpu),
                groundCpu.plus(other.groundCpu), radioOccupancy.plus(other.radioOccupancy),
                Math.addExact(uplinkBytes, other.uplinkBytes), Math.addExact(downlinkBytes, other.downlinkBytes));
    }

    public Duration totalCpu() { return spacecraftCpu.plus(groundCpu); }
    public long managementBytes() { return Math.addExact(uplinkBytes, downlinkBytes); }
}
