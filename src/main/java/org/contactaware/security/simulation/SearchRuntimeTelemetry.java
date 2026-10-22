package org.contactaware.security.simulation;

import java.lang.management.ManagementFactory;
import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;

/** Runtime measurements, separate from modeled security CPU and physical simulation time. */
public record SearchRuntimeTelemetry(long elapsedNanos, Long threadCpuNanos,
                                     Long gcCollectionCountDelta, Long gcCollectionTimeMillisDelta,
                                     Map<String, Long> phaseElapsedNanos, long unattributedElapsedNanos,
                                     String stoppedAtCheckpoint, String gcAttribution) {
    public SearchRuntimeTelemetry {
        phaseElapsedNanos = Collections.unmodifiableMap(new TreeMap<>(phaseElapsedNanos));
    }

    static final class Recorder {
        private final Long cpuStart = cpu();
        private final Long gcCountStart = gc(false);
        private final Long gcMillisStart = gc(true);
        private final Map<String, Long> phases = new TreeMap<>();
        private String activePhase;
        private long activeStart;

        Recorder() {
            for (var name : new String[]{"initialization", "bound", "generation", "candidateProcessing",
                    "execution", "stateKey", "evaluation", "completion"}) phases.put(name, 0L);
        }

        /** Nested phases suspend their parent, making reported phase totals disjoint. */
        Phase phase(String name) {
            long now = System.nanoTime();
            if (activePhase != null) phases.merge(activePhase, Math.max(0, now - activeStart), Long::sum);
            var previous = activePhase;
            activePhase = name; activeStart = now;
            return new Phase(previous);
        }

        final class Phase implements AutoCloseable {
            private final String previous;
            private Phase(String previous) { this.previous = previous; }
            @Override public void close() {
                long now = System.nanoTime();
                phases.merge(activePhase, Math.max(0, now - activeStart), Long::sum);
                activePhase = previous; activeStart = now;
            }
        }

        SearchRuntimeTelemetry snapshot(long elapsed, String checkpoint) {
            long accounted = phases.values().stream().mapToLong(Long::longValue).sum();
            return new SearchRuntimeTelemetry(elapsed, delta(cpuStart, cpu()), delta(gcCountStart, gc(false)),
                    delta(gcMillisStart, gc(true)), phases, Math.max(0, elapsed - accounted), checkpoint,
                    "GC MXBean deltas are JVM-wide and cannot be attributed exclusively to this search; "
                            + "thread CPU excludes other threads and unavailable/disabled counters are null.");
        }
    }

    private static Long delta(Long start, Long end) {
        return start == null || end == null || end < start ? null : end - start;
    }

    private static Long cpu() {
        try {
            var bean = ManagementFactory.getThreadMXBean();
            if (!bean.isCurrentThreadCpuTimeSupported() || !bean.isThreadCpuTimeEnabled()) return null;
            long value = bean.getCurrentThreadCpuTime();
            return value < 0 ? null : value;
        } catch (UnsupportedOperationException | SecurityException unavailable) { return null; }
    }

    private static Long gc(boolean time) {
        try {
            var beans = ManagementFactory.getGarbageCollectorMXBeans();
            if (beans.isEmpty()) return null;
            long total = 0;
            for (var bean : beans) {
                long value = time ? bean.getCollectionTime() : bean.getCollectionCount();
                if (value < 0) return null;
                total = Math.addExact(total, value);
            }
            return total;
        } catch (UnsupportedOperationException | SecurityException unavailable) { return null; }
    }
}
