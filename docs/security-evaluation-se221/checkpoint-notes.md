# SE2.2.1 checkpoint repair — bounded evidence

Owner: `se221_checkpoint`; no experiments, builds, commits, or child agents run.

## Observed failures

All seven entries of `docs/security-evaluation-se22/tables/failed-attempts.json`
have the same recorded failure location: `Files.move(checkpoint.json.tmp,
checkpoint.json, ATOMIC_MOVE, REPLACE_EXISTING)` in `writeAtomic` throws
`AccessDeniedException`. The existing implementation retries neither this
exception nor the failed write. Each preserved directory contains a parseable
`se21-1` last checkpoint and the original `.tmp` file. The last saved times are
9, 35.75, 5.25, 6.25, 30 and 7 seconds in the six X/Y cases and
2026-10-10T00:10:45.617523193Z for ISS CP7200 Multi alternative.

This evidence establishes a rename failure. It does **not** identify the program,
filesystem filter, access-control condition, or other cause of Windows denial.
All old files remain untouched.

## Replacement protocol

The old `write`/`writeAtomic` calls remain source-compatible. New
`writeWithDiagnostics`/`writeAtomicWithDiagnostics` return `WriteDiagnostics`;
`WriteFailure extends IOException` carries the same record on failure.

1. Serialize into a unique temporary file in the target directory; close it.
   Never truncate or delete the previous checkpoint.
2. Attempt the same atomic replacement. Retry **only AccessDeniedException**,
   at most four attempts with 25 ms backoff and a 250 ms retry window starting
   immediately before the first rename. Initial serialization has separate
   total elapsed accounting and does not consume the rename window.
3. Recheck the window after backoff; do not launch a further attempt after it
   expires. An in-progress OS syscall or scheduler sleep cannot be preempted by
   this cooperative limit. Measured overshoot is retained; process watchdog
   remains the hard external safeguard.
4. Persistent denial, generic I/O errors, interruption and unsupported atomic
   replacement terminate explicitly. Interruption preserves the thread flag.
   Unsupported atomic replacement no longer falls back to non-atomic replacement:
   Java cannot guarantee preservation of the old target when that fallback fails.
5. A failed unique temp remains available as evidence; success moves it into
   place. Failed serialization temps are diagnostic files, not valid checkpoints.

Diagnostics include actual rename attempts/retries, AccessDenied failures,
measured backoff, total elapsed, rename elapsed, policy, outcome, failure type,
target/temp paths and each attempt's elapsed/duration/failure. Diagnostics are
returned in memory so the harness can append its trace independently; the writer
does not recursively checkpoint its own diagnostics.

## Deterministic tests to run

`CheckpointReliabilityTest` has nine tests, using the production replacement
protocol with injected mover, monotonic clock and sleeper:

- two transient denials then success; old bytes unchanged until replacement;
  restored in-flight operation and resumed full result equal uninterrupted run;
- permanent denial: four attempts/75 ms backoff; old checkpoint remains byte
  identical and restorable, new serialized state retained in distinct temp;
- slow rename exhausts time before any retry;
- delayed sleep exhausts time without another rename;
- slow serialization does not consume the rename retry window;
- unsupported atomic move fails closed without fallback;
- non-retryable IOException stops after one attempt;
- serialization failure causes zero rename attempts and preserves old bytes;
- interruption during backoff preserves the flag and previous checkpoint.

Existing `ControllerBudgetAndCheckpointTest` also exercises checkpoint restoration
with pending operations, in-flight frames, incident history and controller budget.
Root owns running these tests and the final build. `git diff --check` passed for
the writer/test changes; no test result is claimed here before root executes them.
