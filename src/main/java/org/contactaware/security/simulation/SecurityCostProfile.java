package org.contactaware.security.simulation;

import org.contactaware.security.SecurityAction;
import java.math.BigInteger;
import java.time.Duration;
import java.util.*;
import static org.contactaware.security.SecurityAction.*;

/** Ordered resource phases; a completed phase is charged once, even if its operation has no ACK. */
public record SecurityCostProfile(String name, Provenance provenance, String source,
                                  Map<SecurityAction, OperationCost> operations) {
    public enum Provenance { SYNTHETIC_ASSUMPTION, MEASURED }
    public enum Resource { GROUND_CPU, SPACECRAFT_CPU, UPLINK, DOWNLINK, WAIT }

    public record Phase(Resource resource, Duration duration, long bytes) {
        public Phase {
            Objects.requireNonNull(resource); Objects.requireNonNull(duration);
            if (duration.isNegative() || duration.isZero()) throw new IllegalArgumentException("Phase duration must be positive");
            duration.toNanos(); // This profile uses exact nanosecond arithmetic.
            boolean radio = resource == Resource.UPLINK || resource == Resource.DOWNLINK;
            if (radio ? bytes <= 0 : bytes != 0) throw new IllegalArgumentException("Bytes must match a radio phase");
        }
    }

    public record OperationCost(List<Phase> phases) {
        public OperationCost { phases = List.copyOf(phases); }
        public Duration duration() { return phases.stream().map(Phase::duration).reduce(Duration.ZERO, Duration::plus); }
        public ResourceUsage total() { return consumedUntil(duration()); }

        public ResourceUsage consumedUntil(Duration elapsed) {
            if (elapsed.isNegative()) throw new IllegalArgumentException("Negative elapsed time");
            var remaining = elapsed.compareTo(duration()) > 0 ? duration() : elapsed;
            ResourceUsage usage = ResourceUsage.ZERO;
            for (var phase : phases) {
                if (remaining.isZero()) break;
                var part = remaining.compareTo(phase.duration()) < 0 ? remaining : phase.duration();
                long bytes = BigInteger.valueOf(phase.bytes()).multiply(BigInteger.valueOf(part.toNanos()))
                        .divide(BigInteger.valueOf(phase.duration().toNanos())).longValueExact();
                var zero = Duration.ZERO;
                usage = usage.plus(new ResourceUsage(part,
                        phase.resource() == Resource.SPACECRAFT_CPU ? part : zero,
                        phase.resource() == Resource.GROUND_CPU ? part : zero,
                        phase.resource() == Resource.UPLINK || phase.resource() == Resource.DOWNLINK ? part : zero,
                        phase.resource() == Resource.UPLINK ? bytes : 0,
                        phase.resource() == Resource.DOWNLINK ? bytes : 0));
                remaining = remaining.minus(part);
            }
            return usage;
        }
    }

    public SecurityCostProfile {
        if (name == null || name.isBlank() || source == null || source.isBlank()) throw new IllegalArgumentException("Profile needs provenance");
        Objects.requireNonNull(provenance);
        var copy = new EnumMap<SecurityAction, OperationCost>(SecurityAction.class);
        copy.putAll(operations);
        for (var action : List.of(PREPARE, INSTALL, SWITCH, OPEN, REUSE, STOP, EXPIRE_CONTEXT))
            Objects.requireNonNull(copy.get(action), "Missing cost for " + action);
        if (copy.containsKey(ROTATE) || copy.containsKey(ESTABLISH))
            throw new IllegalArgumentException("Composite costs are sums of their actually executed steps");
        if (copy.get(PREPARE).phases().stream().anyMatch(p -> p.resource() != Resource.GROUND_CPU && p.resource() != Resource.WAIT))
            throw new IllegalArgumentException("Preparation is ground-only");
        // The agreed v1 contract makes local stop/expiry gates instantaneous. Remote cleanup is not modeled.
        if (!copy.get(STOP).phases().isEmpty() || !copy.get(EXPIRE_CONTEXT).phases().isEmpty())
            throw new IllegalArgumentException("Local termination must be instantaneous in this model");
        operations = Collections.unmodifiableMap(copy);
    }

    public OperationCost cost(SecurityAction action) {
        var cost = operations.get(action);
        // Opt-in incident response uses a separate independent management message. Existing
        // profiles/results retain exactly their old ordinary-operation definitions.
        if (cost == null && action == NOTIFY_REVOCATION) {
            if (provenance == Provenance.SYNTHETIC_ASSUMPTION) return syntheticRevocationNotice();
            throw new IllegalArgumentException("Measured profile must explicitly specify NOTIFY_REVOCATION; synthetic fallback is not measured");
        }
        if (cost == null) throw new IllegalArgumentException("Resolve composite action into steps first: " + action);
        return cost;
    }

    public static OperationCost syntheticRevocationNotice() {
        return new OperationCost(List.of(phase(Resource.GROUND_CPU, 20, 0),
                phase(Resource.UPLINK, 160, 64), phase(Resource.SPACECRAFT_CPU, 20, 0),
                phase(Resource.DOWNLINK, 80, 32), phase(Resource.WAIT, 120, 0)));
    }

    /** ROTATE/ESTABLISH have no extra surcharge; omit preparation/installation already completed. */
    public ResourceUsage costOf(List<SecurityAction> resolvedSteps) {
        return resolvedSteps.stream().map(step -> cost(step).total()).reduce(ResourceUsage.ZERO, ResourceUsage::plus);
    }

    public static SecurityCostProfile syntheticWitness() {
        var costs = new EnumMap<SecurityAction, OperationCost>(SecurityAction.class);
        costs.put(PREPARE, new OperationCost(List.of(phase(Resource.GROUND_CPU, 200, 0), phase(Resource.WAIT, 1800, 0))));
        costs.put(INSTALL, serial(100, 1500, 600, 200, 500, 200, 1700));
        costs.put(SWITCH, serial(50, 250, 100, 100, 250, 100, 350));
        costs.put(OPEN, serial(20, 100, 40, 40, 100, 40, 740));
        costs.put(REUSE, costs.get(OPEN));
        costs.put(STOP, new OperationCost(List.of()));
        costs.put(EXPIRE_CONTEXT, new OperationCost(List.of()));
        return new SecurityCostProfile("synthetic-horizon-witness-v1", Provenance.SYNTHETIC_ASSUMPTION,
                "docs/formal-cost-model-and-horizon/main.tex; assumed 400 B/s management channel, not hardware measurements", costs);
    }

    private static OperationCost serial(long ground, long upTime, long upBytes, long spacecraft,
                                        long downTime, long downBytes, long wait) {
        return new OperationCost(List.of(phase(Resource.GROUND_CPU, ground, 0), phase(Resource.UPLINK, upTime, upBytes),
                phase(Resource.SPACECRAFT_CPU, spacecraft, 0), phase(Resource.DOWNLINK, downTime, downBytes),
                phase(Resource.WAIT, wait, 0)));
    }

    private static Phase phase(Resource resource, long milliseconds, long bytes) {
        return new Phase(resource, Duration.ofMillis(milliseconds), bytes);
    }
}
