package org.contactaware.security.simulation;

import java.time.Duration;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/** Cooperative, thread-confined budget scopes. They do not interrupt a paused host or native call. */
public final class SearchControl {
    private static final ThreadLocal<Scope> CURRENT = new ThreadLocal<>();
    private SearchControl() { }

    public static Scope open(Duration budget, SearchDiagnostics.StopReason timeoutReason) {
        return open(budget, timeoutReason, System::nanoTime, ignored -> { });
    }

    static Scope open(Duration budget, SearchDiagnostics.StopReason timeoutReason,
                      LongSupplier clock, Consumer<String> checkpointHook) {
        var scope = new Scope(CURRENT.get(), budget, timeoutReason, clock, checkpointHook);
        CURRENT.set(scope);
        return scope;
    }

    public static void checkpoint(String phase) {
        var scope = CURRENT.get();
        // Outside a controlled run these hooks are deliberately inert.
        if (scope == null) return;
        if (Thread.currentThread().isInterrupted()) throw new Stopped(SearchDiagnostics.StopReason.INTERRUPTED, phase);
        scope.check(phase);
    }

    public static final class Scope implements AutoCloseable {
        private final Scope parent;
        private final long start, budgetNanos;
        private final SearchDiagnostics.StopReason timeoutReason;
        private final LongSupplier clock;
        private final Consumer<String> checkpointHook;
        private final Thread owner = Thread.currentThread();
        private volatile boolean cancelled;
        private boolean closed;

        private Scope(Scope parent, Duration budget, SearchDiagnostics.StopReason timeoutReason,
                      LongSupplier clock, Consumer<String> checkpointHook) {
            if (Objects.requireNonNull(budget).isNegative()) throw new IllegalArgumentException("Negative budget");
            if (timeoutReason == null || timeoutReason == SearchDiagnostics.StopReason.NONE)
                throw new IllegalArgumentException("A timeout requires a stop reason");
            this.parent = parent;
            this.budgetNanos = budget.toNanos();
            this.timeoutReason = timeoutReason;
            this.clock = Objects.requireNonNull(clock);
            this.checkpointHook = Objects.requireNonNull(checkpointHook);
            this.start = clock.getAsLong();
        }

        public void cancel() { cancelled = true; }
        public long elapsedNanos() { return Math.max(0, clock.getAsLong() - start); }

        private void check(String phase) {
            if (parent != null) parent.check(phase);
            checkpointHook.accept(phase);
            if (cancelled) throw new Stopped(SearchDiagnostics.StopReason.CANCELLED, phase);
            if (Thread.currentThread().isInterrupted()) throw new Stopped(SearchDiagnostics.StopReason.INTERRUPTED, phase);
            if (budgetNanos != 0 && elapsedNanos() >= budgetNanos) throw new Stopped(timeoutReason, phase);
        }

        @Override public void close() {
            if (closed) return;
            if (Thread.currentThread() != owner || CURRENT.get() != this)
                throw new IllegalStateException("Budget scopes must close in nesting order on their owner thread");
            closed = true;
            if (parent == null) CURRENT.remove(); else CURRENT.set(parent);
        }
    }

    /** A control signal, deliberately distinct from model/feasibility exceptions. */
    public static final class Stopped extends RuntimeException {
        private final SearchDiagnostics.StopReason reason;
        private final String checkpoint;
        Stopped(SearchDiagnostics.StopReason reason, String checkpoint) {
            super(reason + " at " + checkpoint, null, false, false);
            this.reason = reason;
            this.checkpoint = checkpoint;
        }
        public SearchDiagnostics.StopReason reason() { return reason; }
        public String checkpoint() { return checkpoint; }
    }
}
