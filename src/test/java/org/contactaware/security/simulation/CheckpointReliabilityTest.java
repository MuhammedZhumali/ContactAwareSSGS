package org.contactaware.security.simulation;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.file.*;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import static org.assertj.core.api.Assertions.*;
import static org.contactaware.security.simulation.HorizonWitness.*;

class CheckpointReliabilityTest {
    @TempDir Path directory;
    private static final ExecutionCheckpointFiles.RetryPolicy POLICY =
            new ExecutionCheckpointFiles.RetryPolicy(4, Duration.ofMillis(25), Duration.ofMillis(250));

    private static void replace(Path from, Path to) throws IOException {
        Files.move(from, to, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }
    private static ExecutionCheckpointFiles.Checkpoint checkpoint(ExecutionSnapshot snapshot) {
        return new ExecutionCheckpointFiles.Checkpoint("se21-1", snapshot);
    }

    @Test void transientRenameFailuresPreserveOldCheckpointThenRestoreExactInFlightState() throws Exception {
        var scenario = scenario(Sequence.X, Plan.MULTI);
        var engine = new SimulationEngine();
        var original = engine.start(scenario);
        var next = engine.advance(original, at(2));
        assertThat(next.pendingOperations()).isNotEmpty();
        var target = directory.resolve("checkpoint.json");
        ExecutionCheckpointFiles.write(target, original);
        byte[] saved = Files.readAllBytes(target);
        var clock = new AtomicLong();
        var moves = new AtomicInteger();
        var runtime = new ExecutionCheckpointFiles.WriteRuntime(clock::get, clock::addAndGet, (from, to) -> {
            assertThat(Files.readAllBytes(to)).isEqualTo(saved);
            clock.addAndGet(1_000_000);
            if (moves.incrementAndGet() <= 2) throw new AccessDeniedException(from.toString(), to.toString(), "controlled transient denial");
            replace(from, to);
        });
        var result = ExecutionCheckpointFiles.writeAtomicWithDiagnostics(target, checkpoint(next), POLICY, runtime);
        assertThat(result.outcome()).isEqualTo(ExecutionCheckpointFiles.WriteOutcome.WRITTEN);
        assertThat(result.attempts()).isEqualTo(3);
        assertThat(result.retries()).isEqualTo(2);
        assertThat(result.accessDeniedFailures()).isEqualTo(2);
        assertThat(result.backoffNanos()).isEqualTo(50_000_000);
        assertThat(result.renameElapsedNanos()).isEqualTo(53_000_000);
        assertThat(result.elapsedNanos()).isEqualTo(53_000_000);
        assertThat(result.moves()).hasSize(3);
        assertThat(result.moves().getLast().failureType()).isNull();
        assertThat(Path.of(result.temporaryPath())).doesNotExist();
        var restored = ExecutionCheckpointFiles.read(target);
        assertThat(restored).isEqualTo(next);
        assertThat(engine.resume(restored)).isEqualTo(engine.run(scenario));
    }

    @Test void permanentFailureHasFiniteAttemptsAndPreservesRestorableOldStateAndNewTemp() throws Exception {
        var engine = new SimulationEngine();
        var scenario = scenario(Sequence.X, Plan.MULTI);
        var initial = engine.start(scenario);
        var saved = engine.advance(initial, at(2));
        var newer = engine.advance(initial, at(3));
        var target = directory.resolve("checkpoint.json");
        ExecutionCheckpointFiles.write(target, saved);
        byte[] before = Files.readAllBytes(target);
        var clock = new AtomicLong();
        var runtime = new ExecutionCheckpointFiles.WriteRuntime(clock::get, clock::addAndGet, (from, to) -> {
            assertThat(Files.readAllBytes(to)).isEqualTo(before);
            throw new AccessDeniedException(from.toString(), to.toString(), "controlled persistent denial");
        });
        var failure = catchThrowableOfType(() -> ExecutionCheckpointFiles.writeAtomicWithDiagnostics(
                target, checkpoint(newer), POLICY, runtime), ExecutionCheckpointFiles.WriteFailure.class);
        var diagnostic = failure.diagnostics();
        assertThat(diagnostic.outcome()).isEqualTo(ExecutionCheckpointFiles.WriteOutcome.ACCESS_DENIED_ATTEMPTS_EXHAUSTED);
        assertThat(diagnostic.attempts()).isEqualTo(4);
        assertThat(diagnostic.retries()).isEqualTo(3);
        assertThat(diagnostic.accessDeniedFailures()).isEqualTo(4);
        assertThat(diagnostic.backoffNanos()).isEqualTo(75_000_000);
        assertThat(diagnostic.elapsedNanos()).isEqualTo(75_000_000);
        assertThat(failure).hasCauseInstanceOf(AccessDeniedException.class);
        assertThat(Files.readAllBytes(target)).isEqualTo(before);
        assertThat(ExecutionCheckpointFiles.read(target)).isEqualTo(saved);
        assertThat(engine.resume(ExecutionCheckpointFiles.read(target))).isEqualTo(engine.run(scenario));
        assertThat(ExecutionCheckpointFiles.read(Path.of(diagnostic.temporaryPath()))).isEqualTo(newer);
        var second = catchThrowableOfType(() -> ExecutionCheckpointFiles.writeAtomicWithDiagnostics(
                target, checkpoint(newer), POLICY, runtime), ExecutionCheckpointFiles.WriteFailure.class);
        assertThat(second.diagnostics().temporaryPath()).isNotEqualTo(diagnostic.temporaryPath());
        assertThat(Path.of(diagnostic.temporaryPath())).exists();
    }

    @Test void retryWindowStopsAdditionalAttemptsAfterSlowRenameFailure() throws Exception {
        var target = directory.resolve("checkpoint.json");
        ExecutionCheckpointFiles.writeAtomic(target, Map.of("old", true));
        var before = Files.readAllBytes(target);
        var clock = new AtomicLong();
        var runtime = new ExecutionCheckpointFiles.WriteRuntime(clock::get,
                nanos -> { throw new AssertionError("No retry sleep after expired window"); }, (from, to) -> {
                    clock.addAndGet(250_000_000);
                    throw new AccessDeniedException(from.toString());
                });
        var failure = catchThrowableOfType(() -> ExecutionCheckpointFiles.writeAtomicWithDiagnostics(
                target, Map.of("new", true), POLICY, runtime), ExecutionCheckpointFiles.WriteFailure.class);
        assertThat(failure.diagnostics().outcome()).isEqualTo(ExecutionCheckpointFiles.WriteOutcome.ACCESS_DENIED_TIME_EXHAUSTED);
        assertThat(failure.diagnostics().attempts()).isEqualTo(1);
        assertThat(failure.diagnostics().backoffNanos()).isZero();
        assertThat(failure.diagnostics().renameElapsedNanos()).isEqualTo(250_000_000);
        assertThat(Files.readAllBytes(target)).isEqualTo(before);
    }

    @Test void oversleepCannotLaunchAnotherRenameOutsideRetryWindow() throws Exception {
        var target = directory.resolve("checkpoint.json");
        ExecutionCheckpointFiles.writeAtomic(target, Map.of("old", true));
        var before = Files.readAllBytes(target);
        var clock = new AtomicLong();
        var runtime = new ExecutionCheckpointFiles.WriteRuntime(clock::get, nanos -> clock.addAndGet(300_000_000),
                (from, to) -> { throw new AccessDeniedException(from.toString()); });
        var failure = catchThrowableOfType(() -> ExecutionCheckpointFiles.writeAtomicWithDiagnostics(
                target, Map.of("new", true), POLICY, runtime), ExecutionCheckpointFiles.WriteFailure.class);
        assertThat(failure.diagnostics().outcome()).isEqualTo(ExecutionCheckpointFiles.WriteOutcome.ACCESS_DENIED_TIME_EXHAUSTED);
        assertThat(failure.diagnostics().attempts()).isEqualTo(1);
        assertThat(failure.diagnostics().backoffNanos()).isEqualTo(300_000_000);
        assertThat(Files.readAllBytes(target)).isEqualTo(before);
    }

    @Test void slowSerializationDoesNotConsumeRenameRetryWindow() throws Exception {
        var target = directory.resolve("checkpoint.json");
        var clock = new AtomicLong();
        var moves = new AtomicInteger();
        class SlowValue {
            public boolean isValue() { clock.addAndGet(1_000_000_000); return true; }
        }
        var runtime = new ExecutionCheckpointFiles.WriteRuntime(clock::get, clock::addAndGet, (from, to) -> {
            if (moves.incrementAndGet() == 1) throw new AccessDeniedException(from.toString());
            replace(from, to);
        });
        var result = ExecutionCheckpointFiles.writeAtomicWithDiagnostics(target, new SlowValue(), POLICY, runtime);
        assertThat(result.attempts()).isEqualTo(2);
        assertThat(result.elapsedNanos()).isEqualTo(1_025_000_000);
        assertThat(result.renameElapsedNanos()).isEqualTo(25_000_000);
    }

    @Test void unsupportedAtomicMoveFailsClosedWithoutNonAtomicFallback() throws Exception {
        var target = directory.resolve("checkpoint.json");
        ExecutionCheckpointFiles.writeAtomic(target, Map.of("old", true));
        var before = Files.readAllBytes(target);
        var calls = new AtomicInteger();
        var runtime = new ExecutionCheckpointFiles.WriteRuntime(System::nanoTime,
                nanos -> { throw new AssertionError("Unsupported move is not transient"); }, (from, to) -> {
                    calls.incrementAndGet();
                    throw new AtomicMoveNotSupportedException(from.toString(), to.toString(), "controlled unsupported FS");
                });
        var failure = catchThrowableOfType(() -> ExecutionCheckpointFiles.writeAtomicWithDiagnostics(
                target, Map.of("new", true), POLICY, runtime), ExecutionCheckpointFiles.WriteFailure.class);
        assertThat(calls).hasValue(1);
        assertThat(failure.diagnostics().outcome()).isEqualTo(ExecutionCheckpointFiles.WriteOutcome.ATOMIC_MOVE_UNSUPPORTED);
        assertThat(failure.diagnostics().retries()).isZero();
        assertThat(Files.readAllBytes(target)).isEqualTo(before);
    }

    @Test void nonRetryableIoFailureIsExplicitAndDoesNotRetry() throws Exception {
        var target = directory.resolve("checkpoint.json");
        ExecutionCheckpointFiles.writeAtomic(target, Map.of("old", true));
        var before = Files.readAllBytes(target);
        var runtime = new ExecutionCheckpointFiles.WriteRuntime(System::nanoTime,
                nanos -> { throw new AssertionError("Generic IOException must not retry"); },
                (from, to) -> { throw new IOException("controlled non-transient failure"); });
        var failure = catchThrowableOfType(() -> ExecutionCheckpointFiles.writeAtomicWithDiagnostics(
                target, Map.of("new", true), POLICY, runtime), ExecutionCheckpointFiles.WriteFailure.class);
        assertThat(failure.diagnostics().outcome()).isEqualTo(ExecutionCheckpointFiles.WriteOutcome.IO_ERROR);
        assertThat(failure.diagnostics().attempts()).isEqualTo(1);
        assertThat(failure.diagnostics().accessDeniedFailures()).isZero();
        assertThat(Files.readAllBytes(target)).isEqualTo(before);
    }

    @Test void serializationFailureDoesNotTouchPreviousCheckpointOrAttemptRename() throws Exception {
        var target = directory.resolve("checkpoint.json");
        ExecutionCheckpointFiles.writeAtomic(target, Map.of("old", true));
        var before = Files.readAllBytes(target);
        class BadValue { public boolean isValue() { throw new IllegalStateException("controlled serialization failure"); } }
        var runtime = new ExecutionCheckpointFiles.WriteRuntime(System::nanoTime,
                nanos -> { throw new AssertionError("No retry before serialization completes"); },
                (from, to) -> { throw new AssertionError("Must not rename incomplete JSON"); });
        var failure = catchThrowableOfType(() -> ExecutionCheckpointFiles.writeAtomicWithDiagnostics(
                target, new BadValue(), POLICY, runtime), ExecutionCheckpointFiles.WriteFailure.class);
        assertThat(failure.diagnostics().outcome()).isEqualTo(ExecutionCheckpointFiles.WriteOutcome.IO_ERROR);
        assertThat(failure.diagnostics().attempts()).isZero();
        assertThat(failure.diagnostics().renameElapsedNanos()).isZero();
        assertThat(Files.readAllBytes(target)).isEqualTo(before);
    }

    @Test void interruptionDuringBackoffPreservesFlagAndOldCheckpoint() throws Exception {
        var target = directory.resolve("checkpoint.json");
        ExecutionCheckpointFiles.writeAtomic(target, Map.of("old", true));
        var before = Files.readAllBytes(target);
        var clock = new AtomicLong();
        var runtime = new ExecutionCheckpointFiles.WriteRuntime(clock::get, nanos -> {
            clock.addAndGet(5_000_000); throw new InterruptedException("controlled interrupt");
        }, (from, to) -> { throw new AccessDeniedException(from.toString()); });
        try {
            var failure = catchThrowableOfType(() -> ExecutionCheckpointFiles.writeAtomicWithDiagnostics(
                    target, Map.of("new", true), POLICY, runtime), ExecutionCheckpointFiles.WriteFailure.class);
            assertThat(failure.diagnostics().outcome()).isEqualTo(ExecutionCheckpointFiles.WriteOutcome.INTERRUPTED);
            assertThat(failure.diagnostics().attempts()).isEqualTo(1);
            assertThat(failure.diagnostics().backoffNanos()).isEqualTo(5_000_000);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally { Thread.interrupted(); }
        assertThat(Files.readAllBytes(target)).isEqualTo(before);
    }
}
