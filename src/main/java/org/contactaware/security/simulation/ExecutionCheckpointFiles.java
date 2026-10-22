package org.contactaware.security.simulation;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.module.SimpleModule;
import org.contactaware.contacts.JsonFiles;
import org.contactaware.security.*;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.file.*;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.ArrayList;
import java.util.Objects;
import java.util.TreeMap;
import java.util.function.LongSupplier;

/** Versioned local checkpoints. A full checkpoint is privileged and must not be passed to a planner. */
public final class ExecutionCheckpointFiles {
    private ExecutionCheckpointFiles() {}
    public static final RetryPolicy DEFAULT_RETRY_POLICY =
            new RetryPolicy(4, Duration.ofMillis(25), Duration.ofMillis(250));

    /** Retry window begins before the first rename; a blocking OS operation is not preemptible. */
    public record RetryPolicy(int maxAttempts, Duration retryDelay, Duration retryWindow) {
        public RetryPolicy {
            Objects.requireNonNull(retryDelay); Objects.requireNonNull(retryWindow);
            if (maxAttempts < 1 || retryDelay.isNegative() || retryWindow.isNegative()
                    || retryWindow.isZero()) throw new IllegalArgumentException("Invalid checkpoint retry policy");
            retryDelay.toNanos(); retryWindow.toNanos();
        }
    }
    public enum WriteOutcome {
        WRITTEN, ACCESS_DENIED_ATTEMPTS_EXHAUSTED, ACCESS_DENIED_TIME_EXHAUSTED,
        ATOMIC_MOVE_UNSUPPORTED, INTERRUPTED, IO_ERROR
    }
    public record MoveAttempt(int number, long elapsedNanos, long durationNanos, String failureType) { }
    public record WriteDiagnostics(String targetPath, String temporaryPath, WriteOutcome outcome,
                                   int attempts, int retries, int accessDeniedFailures, long backoffNanos,
                                   long elapsedNanos, long renameElapsedNanos, RetryPolicy policy,
                                   String failureType, List<MoveAttempt> moves) {
        public WriteDiagnostics { moves = List.copyOf(moves); }
    }
    public static final class WriteFailure extends IOException {
        private final WriteDiagnostics diagnostics;
        private WriteFailure(WriteDiagnostics diagnostics, IOException cause) {
            super("Checkpoint write failed: " + diagnostics, cause);
            this.diagnostics = diagnostics;
        }
        public WriteDiagnostics diagnostics() { return diagnostics; }
    }
    @FunctionalInterface interface AtomicMover { void move(Path from, Path to) throws IOException; }
    @FunctionalInterface interface Sleeper { void sleep(long nanos) throws InterruptedException; }
    record WriteRuntime(LongSupplier clock, Sleeper sleeper, AtomicMover mover) {
        WriteRuntime { Objects.requireNonNull(clock); Objects.requireNonNull(sleeper); Objects.requireNonNull(mover); }
    }
    private static final WriteRuntime SYSTEM = new WriteRuntime(System::nanoTime,
            nanos -> Thread.sleep(Duration.ofNanos(nanos)),
            (from, to) -> Files.move(from, to, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING));
    public record Checkpoint(String version, ExecutionSnapshot snapshot) {
        public Checkpoint {
            if (!"se21-1".equals(version)) throw new IllegalArgumentException("Unsupported checkpoint version");
            java.util.Objects.requireNonNull(snapshot);
        }
    }
    @com.fasterxml.jackson.annotation.JsonIgnoreProperties("securityOperation")
    private abstract static class PlanningActionFields { }
    private static final ObjectMapper JSON = JsonFiles.JSON.copy().addMixIn(PlanningAction.class, PlanningActionFields.class)
            .registerModule(new SimpleModule()
            .addDeserializer(SecurityState.class, new JsonDeserializer<>() {
                @Override public SecurityState deserialize(JsonParser parser, DeserializationContext context) throws IOException {
                    JsonNode node = parser.getCodec().readTree(parser);
                    var contexts = new TreeMap<ContextId, SecurityContext>();
                    var keys = new TreeMap<String, KeyState>();
                    for (var value : node.required("contexts")) {
                        var entry = parser.getCodec().treeToValue(value, SecurityContext.class);
                        if (contexts.put(entry.id(), entry) != null) throw new IOException("Duplicate context identity");
                    }
                    for (var value : node.required("keys")) {
                        var entry = parser.getCodec().treeToValue(value, KeyState.class);
                        if (keys.put(entry.id(), entry) != null) throw new IOException("Duplicate key identity");
                    }
                    return new SecurityState(node.required("satelliteId").asText(),
                            Instant.parse(node.required("asOf").asText()), contexts, keys);
                }
            }));

    public static <T> T read(Path path, Class<T> type) throws IOException { return JSON.readValue(path.toFile(), type); }
    public static <T> T convert(JsonNode value, Class<T> type) throws IOException { return JSON.treeToValue(value, type); }
    public static ExecutionSnapshot read(Path path) throws IOException { return read(path, Checkpoint.class).snapshot(); }
    public static void write(Path path, ExecutionSnapshot snapshot) throws IOException { writeWithDiagnostics(path, snapshot); }
    public static void writeAtomic(Path path, Object value) throws IOException {
        writeAtomicWithDiagnostics(path, value);
    }
    public static WriteDiagnostics writeWithDiagnostics(Path path, ExecutionSnapshot snapshot) throws IOException {
        return writeAtomicWithDiagnostics(path, new Checkpoint("se21-1", snapshot));
    }
    public static WriteDiagnostics writeAtomicWithDiagnostics(Path path, Object value) throws IOException {
        return writeAtomicWithDiagnostics(path, value, DEFAULT_RETRY_POLICY, SYSTEM);
    }

    /** Package-private fault-injection seam; production and tests execute the same replacement protocol. */
    static WriteDiagnostics writeAtomicWithDiagnostics(Path path, Object value, RetryPolicy policy,
                                                       WriteRuntime runtime) throws IOException {
        Objects.requireNonNull(path); Objects.requireNonNull(policy); Objects.requireNonNull(runtime);
        Path target = path.toAbsolutePath(), temporary = null;
        long started = runtime.clock().getAsLong(), renameStarted = 0, backoff = 0;
        boolean renamePhase = false;
        int denied = 0;
        var attempts = new ArrayList<MoveAttempt>();
        WriteOutcome outcome = WriteOutcome.IO_ERROR;
        try {
            checkInterrupted();
            Files.createDirectories(target.getParent());
            temporary = Files.createTempFile(target.getParent(), target.getFileName() + ".write-", ".tmp");
            JSON.writeValue(temporary.toFile(), value);
            renameStarted = runtime.clock().getAsLong();
            renamePhase = true;
            while (true) {
                checkInterrupted();
                long attemptStarted = runtime.clock().getAsLong();
                try {
                    runtime.mover().move(temporary, target);
                    long now = runtime.clock().getAsLong();
                    attempts.add(new MoveAttempt(attempts.size() + 1, elapsed(started, now), elapsed(attemptStarted, now), null));
                    return diagnostics(target, temporary, WriteOutcome.WRITTEN, denied, backoff,
                            started, renameStarted, now, policy, null, attempts);
                } catch (IOException failure) {
                    long now = runtime.clock().getAsLong();
                    attempts.add(new MoveAttempt(attempts.size() + 1, elapsed(started, now), elapsed(attemptStarted, now), failure.getClass().getName()));
                    if (failure instanceof AtomicMoveNotSupportedException) {
                        // A non-atomic REPLACE_EXISTING may lose the old target on failure. Fail closed.
                        outcome = WriteOutcome.ATOMIC_MOVE_UNSUPPORTED;
                        throw failure;
                    }
                    if (!(failure instanceof AccessDeniedException)) throw failure;
                    denied++;
                    if (attempts.size() >= policy.maxAttempts()) {
                        outcome = WriteOutcome.ACCESS_DENIED_ATTEMPTS_EXHAUSTED;
                        throw failure;
                    }
                    long remaining = policy.retryWindow().toNanos() - elapsed(renameStarted, now);
                    if (remaining <= 0 || policy.retryDelay().toNanos() >= remaining) {
                        outcome = WriteOutcome.ACCESS_DENIED_TIME_EXHAUSTED;
                        throw failure;
                    }
                    checkInterrupted();
                    long waitStarted = runtime.clock().getAsLong();
                    try {
                        runtime.sleeper().sleep(policy.retryDelay().toNanos());
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        var io = new InterruptedIOException("Interrupted during checkpoint retry backoff");
                        io.initCause(interrupted);
                        throw io;
                    } finally {
                        backoff += elapsed(waitStarted, runtime.clock().getAsLong());
                    }
                    if (elapsed(renameStarted, runtime.clock().getAsLong()) >= policy.retryWindow().toNanos()) {
                        outcome = WriteOutcome.ACCESS_DENIED_TIME_EXHAUSTED;
                        throw failure;
                    }
                }
            }
        } catch (IOException failure) {
            if (failure instanceof InterruptedIOException) outcome = WriteOutcome.INTERRUPTED;
            long now = runtime.clock().getAsLong();
            throw new WriteFailure(diagnostics(target, temporary, outcome, denied, backoff, started,
                    renamePhase ? renameStarted : now, now, policy, failure.getClass().getName(), attempts), failure);
        }
    }

    private static void checkInterrupted() throws InterruptedIOException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("Checkpoint writer interrupted");
    }
    private static long elapsed(long started, long now) { return Math.max(0, now - started); }
    private static WriteDiagnostics diagnostics(Path target, Path temporary, WriteOutcome outcome, int denied,
                                               long backoff, long started, long renameStarted, long now,
                                               RetryPolicy policy, String failure, List<MoveAttempt> attempts) {
        return new WriteDiagnostics(target.toString(), temporary == null ? null : temporary.toString(), outcome,
                attempts.size(), Math.max(0, attempts.size() - 1), denied, backoff,
                elapsed(started, now), elapsed(renameStarted, now), policy, failure, attempts);
    }
}
