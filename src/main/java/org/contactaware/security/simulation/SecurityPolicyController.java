package org.contactaware.security.simulation;

import org.contactaware.security.ScheduledSecurityAction;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import static org.contactaware.security.simulation.ControlledSimulationResult.Status.*;

/**
 * Common actual execution for rule and horizon policies: select one action, execute, observe again.
 * Solver wall time is measured separately and does not advance the simulated physical clock.
 */
public final class SecurityPolicyController {
    private final SecurityPolicy policy;
    private final Duration maxElapsed;
    private final CheckpointListener listener;
    private final boolean continuationReuse;
    private final Duration decisionBudget;
    private final LongSupplier clock;
    private final Consumer<String> decisionCheckpointHook;
    private final List<DecisionTiming> decisionTimings = new ArrayList<>();

    /** Optimization time ends before actual execution. Mandatory incident response is exempt.
     * Recovery overhead is finite observation/response preparation outside an expired decision
     * scope, still subject to global cancellation. Neither interval is a hard real-time bound. */
    public record DecisionTiming(Instant at, long elapsedNanos, long budgetNanos, boolean deadlineMiss,
                                 String stopCheckpoint, String disposition, long fallbackOverheadNanos) { }

    /** Immutable timing records for the most recent run; controllers are thread-confined. */
    public List<DecisionTiming> decisionTimings() { return List.copyOf(decisionTimings); }

    /** Called only with a complete immutable event-boundary snapshot; may persist it atomically. */
    @FunctionalInterface public interface CheckpointListener {
        void save(ExecutionSnapshot snapshot, int completedDecisions);
    }

    private final SimulationEngine engine = new SimulationEngine();
    private final SecurityActionGenerator generator = new SecurityActionGenerator();

    public SecurityPolicyController(SecurityPolicy policy) {
        this(policy, Duration.ZERO, (snapshot, count) -> {});
    }

    public SecurityPolicyController(SecurityPolicy policy, Duration maxElapsed, CheckpointListener listener) {
        this(policy, maxElapsed, listener, false);
    }

    /** Opt-in and identical for Next/Multi. Proofs are local to this invocation, never restored from checkpoint. */
    public SecurityPolicyController(SecurityPolicy policy, Duration maxElapsed, CheckpointListener listener, boolean continuationReuse) {
        this(policy, maxElapsed, listener, continuationReuse, Duration.ZERO);
    }

    /** A fresh cooperative optimization deadline per observation; zero preserves unlimited decisions. */
    public SecurityPolicyController(SecurityPolicy policy, Duration maxElapsed, CheckpointListener listener,
                                    boolean continuationReuse, Duration decisionBudget) {
        this(policy, maxElapsed, listener, continuationReuse, decisionBudget, System::nanoTime, ignored -> {});
    }

    SecurityPolicyController(SecurityPolicy policy, Duration maxElapsed, CheckpointListener listener,
                             boolean continuationReuse, Duration decisionBudget, LongSupplier clock,
                             Consumer<String> decisionCheckpointHook) {
        this.policy = Objects.requireNonNull(policy);
        Objects.requireNonNull(policy.configuration());
        this.maxElapsed = Objects.requireNonNull(maxElapsed);
        this.listener = Objects.requireNonNull(listener);
        this.continuationReuse = continuationReuse;
        this.decisionBudget = Objects.requireNonNull(decisionBudget);
        this.clock = Objects.requireNonNull(clock);
        this.decisionCheckpointHook = Objects.requireNonNull(decisionCheckpointHook);
        if (maxElapsed.isNegative()) throw new IllegalArgumentException("Negative controller budget");
        if (decisionBudget.isNegative()) throw new IllegalArgumentException("Negative decision budget");
        maxElapsed.toNanos();
        decisionBudget.toNanos();
    }

    public ControlledSimulationResult run(SimulationScenario scenario) {
        if (!scenario.plan().isEmpty())
            throw new IllegalArgumentException("Use a real checkpoint for handoff after prescribed warmup");
        return run(engine.start(scenario));
    }

    public ControlledSimulationResult run(ExecutionSnapshot checkpoint) {
        return run(checkpoint, OperationOutcomes.nominal());
    }

    public ControlledSimulationResult run(ExecutionSnapshot checkpoint, OperationOutcomes outcomes) {
        Objects.requireNonNull(outcomes);
        decisionTimings.clear();
        var current = checkpoint;
        var decisions = new ArrayList<ControlledSimulationResult.Decision>();
        var work = new Work();
        var guard = new ContinuationReuseGuard();
        ContinuationReuseGuard.ProvenContinuation proof = null;
        try (var control = SearchControl.open(maxElapsed, SearchDiagnostics.StopReason.CONTROLLER_TIME_LIMIT, clock, ignored -> {})) {
        current = engine.controlledSnapshot(current);
        while (current.at().isBefore(current.scenario().end())) {
            listener.save(current, decisions.size());
            PlanningObservation observation = null;
            var decision = new DecisionBudget();
            try (decision) {
            SearchControl.checkpoint("controller.observation");
            observation = PlanningObservation.from(current, policy.configuration().horizon());
            observation = observation.withAdditionalWakeUps(policy.additionalWakeUps(observation));
            SearchControl.checkpoint("controller.candidates");
            var candidates = generator.candidates(observation);
            var response = IncidentResponse.next(current, observation, candidates);
            // Once a mandatory response is recognized, its preparation and execution use only
            // the global scope. A deadline that interrupted recognition is handled below.
            if (response != null) decision.close();
            // Mandatory response retains precedence. It uses realized notices, never a future attack schedule.
            if (response != null && response.notice() != null) {
                var audit = continuationReuse ? work.audit(proof, null, new ContinuationReuseGuard.Result(
                        ContinuationReuseGuard.Status.REJECTED, "EMERGENCY_RESPONSE: observed incident takes precedence"), 0, 0) : null;
                proof = null;
                decision.record(current.at(), false, "", "MANDATORY_RESPONSE", 0);
                SearchControl.checkpoint("controller.notification");
                var next = engine.controlledStart(current, Objects.requireNonNull(outcomes.forOperation(response.notice())).apply(response.notice()));
                decisions.add(new ControlledSimulationResult.Decision(observation, null, null, next.at(),
                        ControlledSimulationResult.DecisionSource.RULE, response.reason(), audit));
                if (next.at().equals(current.at()) && next.pendingOperations().equals(current.pendingOperations()))
                    return work.finish(policy.configuration(), EXECUTION_STALLED, next, null, decisions);
                current = next;
                continue;
            }
            SearchControl.checkpoint("controller.selection");
            var checkedProof = proof;
            ContinuationReuseGuard.Result verdict = null;
            long guardNanos = 0, preparationNanos = 0;
            boolean reused = false;
            var rules = policy.continuationRules();
            PolicySelection selection = null;
            if (continuationReuse && response == null) {
                long before = System.nanoTime();
                ContinuationReuseGuard.Assessment assessment;
                try {
                    SearchControl.checkpoint("controller.reuseGuard");
                    assessment = guard.assess(proof, observation, rules);
                } finally { guardNanos = System.nanoTime() - before; work.guardNanos += guardNanos; }
                verdict = assessment.result();
                SearchControl.checkpoint("controller.reuseGuard.complete");
                if (verdict.status() == ContinuationReuseGuard.Status.ALLOWED) {
                    before = System.nanoTime();
                    try { SearchControl.checkpoint("controller.reusePreparation"); proof = assessment.derive(); }
                    finally { long elapsed = System.nanoTime() - before; preparationNanos += elapsed; work.preparationNanos += elapsed; }
                    if (proof.firstAction() == null) throw new IllegalStateException("Proven suffix unexpectedly empty inside a contact");
                    selection = PolicySelection.rule(proof.firstAction(), "First action of proven continuation " + proof.reference());
                    reused = true;
                }
            } else if (continuationReuse) {
                verdict = new ContinuationReuseGuard.Result(ContinuationReuseGuard.Status.REJECTED,
                        "EMERGENCY_RESPONSE: observed incident takes precedence");
            }
            if (!reused) {
                proof = null;
                long before = System.nanoTime();
                try { selection = response == null ? Objects.requireNonNull(policy.select(observation, candidates))
                        : PolicySelection.rule(response.action(), response.reason()); }
                finally { work.selectionNanos += System.nanoTime() - before; }
            }
            // Exact search can catch an outer stop and return INCOMPLETE. Recheck before
            // any early return, proof adoption, or use of the selected first action.
            SearchControl.checkpoint("controller.selection.complete");
            var source = reused ? ControlledSimulationResult.DecisionSource.SUFFIX_REUSE
                    : selection.search() == null ? ControlledSimulationResult.DecisionSource.RULE : ControlledSimulationResult.DecisionSource.SEARCH;
            var search = selection.search();
            if (search != null) work.searches++;
            if (continuationReuse && search != null && search.status() == HorizonPlanResult.Status.COMPLETE
                    && rules != null && policy.configuration() instanceof HorizonPolicy && selection.certificate() != null
                    && selection.certificate().matches(observation, search, rules)) {
                long before = System.nanoTime();
                try { SearchControl.checkpoint("controller.reusePreparation"); proof = guard.start(selection.certificate().evidence(), "decision:" + decisions.size()); }
                finally { long elapsed = System.nanoTime() - before; preparationNanos += elapsed; work.preparationNanos += elapsed; }
            }
            var audit = continuationReuse ? work.audit(checkedProof, proof, verdict, guardNanos, preparationNanos) : null;
            if (search != null && search.status() != HorizonPlanResult.Status.COMPLETE) {
                SearchControl.checkpoint("controller.incomplete");
                decision.record(current.at(), false, "", "SEARCH_" + search.status(), 0);
                decisions.add(new ControlledSimulationResult.Decision(observation, search, null, current.at(), source, selection.reason(), audit));
                return work.finish(policy.configuration(),
                        search.status() == HorizonPlanResult.Status.INCOMPLETE ? stoppedStatus(search.diagnostics().stopReason()) : NO_FEASIBLE_PLAN,
                        current, null, decisions);
            }
            var action = selection.action();
            if (action != null && !candidates.contains(action))
                throw new IllegalArgumentException("Policy selected an inadmissible first action");
            SearchControl.checkpoint("controller.decision.complete");
            decision.record(current.at(), false, "", response == null ? "SELECTED_" + source : "MANDATORY_RESPONSE", 0);
            SearchControl.checkpoint("controller.execution");
            var next = executeAction(current, observation, action, outcomes);
            decisions.add(new ControlledSimulationResult.Decision(observation, search, action, next.at(), source, selection.reason(), audit));
            if (reused) work.suffixReuses++;
            // Valid positive-duration primitives create committed work. An immediate rejection
            // or zero-time interruption must not generate an unbounded same-time retry loop.
            if (next.at().equals(current.at()) && next.pendingOperations().equals(current.pendingOperations()))
                return work.finish(policy.configuration(), EXECUTION_STALLED, next, null, decisions);
            current = next;
            } catch (SearchControl.Stopped stopped) {
                if (stopped.reason() != SearchDiagnostics.StopReason.DECISION_TIME_LIMIT) {
                    decision.record(current.at(), false, stopped.checkpoint(), "STOP_" + stopped.reason(), 0);
                    throw stopped;
                }
                // No attempted transition has replaced current. Discard both the selection
                // and its proof; a miss never grants permission to start a frame/service.
                proof = null;
                long recoveryStarted = clock.getAsLong();
                Recovery recovery;
                try {
                    SearchControl.checkpoint("controller.deadline.recovery");
                    recovery = recoverDeadline(current, observation);
                    SearchControl.checkpoint("controller.deadline.recovery.complete");
                } catch (SearchControl.Stopped globalStop) {
                    decision.record(current.at(), true, stopped.checkpoint(), "STOP_" + globalStop.reason(),
                            Math.max(0, clock.getAsLong() - recoveryStarted));
                    throw globalStop;
                }
                decision.record(current.at(), true, stopped.checkpoint(),
                        recovery.response() == null ? "SAFE_WAIT" : "MANDATORY_RESPONSE",
                        Math.max(0, clock.getAsLong() - recoveryStarted));
                SearchControl.checkpoint("controller.execution");
                var response = recovery.response();
                var next = response != null && response.notice() != null
                        ? engine.controlledStart(current, Objects.requireNonNull(outcomes.forOperation(response.notice())).apply(response.notice()))
                        : executeAction(current, recovery.observation(), recovery.action(), outcomes);
                decisions.add(new ControlledSimulationResult.Decision(recovery.observation(), null, recovery.action(), next.at(),
                        ControlledSimulationResult.DecisionSource.RULE, response == null
                        ? "DECISION_TIME_LIMIT: safe WAIT at the earliest observed wakeup; no optimality certificate"
                        : response.reason()));
                if (next.at().equals(current.at()) && next.pendingOperations().equals(current.pendingOperations()))
                    return work.finish(policy.configuration(), EXECUTION_STALLED, next, null, decisions);
                current = next;
            }
        }
        listener.save(current, decisions.size());
        SearchControl.checkpoint("controller.finish");
        var completed = engine.finishControlled(current);
        return work.finish(policy.configuration(), FINISHED, completed.snapshot(), completed.result(), decisions);
        } catch (SearchControl.Stopped stopped) {
            // The attempted transition works on a private runtime. Only committed immutable
            // snapshots replace current; cancellation creates no physical failure/expiry event.
            listener.save(current, decisions.size());
            return work.finish(policy.configuration(), stoppedStatus(stopped.reason()), current, null, decisions);
        }
    }

    private record Recovery(PlanningObservation observation, IncidentResponse.Response response, PlanningAction action) { }

    private Recovery recoverDeadline(ExecutionSnapshot current, PlanningObservation completedObservation) {
        // Reuse a completed immutable observation where possible. Do not repeat arbitrary
        // policy timer hooks after expiry. Re-observation reads no future incident/outcome.
        var observation = completedObservation == null
                ? PlanningObservation.from(current, policy.configuration().horizon()) : completedObservation;
        IncidentResponse.Response response = null;
        if (current.journal().stream().anyMatch(e -> e.type() == SimulationEvent.Type.REVOCATION_RECEIVED_GROUND)) {
            // Only mandatory incident recovery may generate service candidates outside the
            // optimization budget. Its existing admissibility and global stop checks apply.
            response = IncidentResponse.next(current, observation, generator.candidates(observation));
        }
        if (response != null) return new Recovery(observation, response, response.action());
        // An empty visible horizon has no legal future observation wakeup. Preserve the
        // existing event-driven idle-to-end path; it still processes committed work/events.
        if (observation.visibleContacts().isEmpty()) return new Recovery(observation, null, null);
        var until = observation.wakeUps().stream().filter(t -> t.isAfter(observation.at()))
                .min(Comparator.naturalOrder()).orElse(observation.evaluationEnd());
        return new Recovery(observation, null,
                new PlanningAction(PlanningAction.Kind.WAIT, observation.at(), until, null, null, null));
    }

    private final class DecisionBudget implements AutoCloseable {
        private final SearchControl.Scope scope = SearchControl.open(decisionBudget,
                SearchDiagnostics.StopReason.DECISION_TIME_LIMIT, clock, decisionCheckpointHook);
        private long elapsedNanos;
        private boolean closed, recorded;
        @Override public void close() {
            if (closed) return;
            elapsedNanos = scope.elapsedNanos();
            scope.close();
            closed = true;
        }
        void record(Instant at, boolean missed, String checkpoint, String disposition, long recoveryNanos) {
            close();
            if (recorded) return;
            decisionTimings.add(new DecisionTiming(at, elapsedNanos, decisionBudget.toNanos(), missed,
                    checkpoint, disposition, recoveryNanos));
            recorded = true;
        }
    }

    private static final class Work {
        final long started = System.nanoTime();
        long guardNanos, preparationNanos, selectionNanos, searches, suffixReuses;
        final Map<String, Long> reasons = new TreeMap<>();
        ControlledSimulationResult.ContinuationAudit audit(ContinuationReuseGuard.ProvenContinuation checked,
                ContinuationReuseGuard.ProvenContinuation selected, ContinuationReuseGuard.Result verdict, long guard, long preparation) {
            if (verdict != null) reasons.merge(verdict.status() + ":" + verdict.reason().split(":", 2)[0], 1L, Long::sum);
            return new ControlledSimulationResult.ContinuationAudit(selected == null ? null : selected.reference(), selected == null ? -1 : selected.offset(),
                    checked == null ? null : checked.reference(), checked == null ? -1 : checked.offset(), verdict, guard, preparation);
        }
        ControlledSimulationResult finish(PolicyConfiguration policy, ControlledSimulationResult.Status status,
                ExecutionSnapshot snapshot, SimulationResult simulation, List<ControlledSimulationResult.Decision> decisions) {
            return new ControlledSimulationResult(policy, status, snapshot, simulation, decisions,
                    new ControlledSimulationResult.Computation(System.nanoTime() - started, guardNanos, preparationNanos,
                            selectionNanos, searches, suffixReuses, reasons));
        }
    }

    private static ControlledSimulationResult.Status stoppedStatus(SearchDiagnostics.StopReason reason) {
        return switch (reason) {
            case CONTROLLER_TIME_LIMIT -> CONTROLLER_TIME_LIMIT;
            case CANCELLED -> CANCELLED;
            case INTERRUPTED -> INTERRUPTED;
            default -> SEARCH_INCOMPLETE;
        };
    }

    /** Shared actual action execution for the controller and factual journal replay; no search. */
    static ExecutionSnapshot executeAction(ExecutionSnapshot current, PlanningObservation observation,
                                           PlanningAction action, OperationOutcomes outcomes) {
        var engine = new SimulationEngine();
        if (action == null) {
            if (!observation.visibleContacts().isEmpty())
                throw new IllegalArgumentException("A nonempty horizon needs a first action");
            return engine.controlledAdvance(current, current.scenario().end(), false);
        }
        if (action.kind() == PlanningAction.Kind.WAIT || action.kind() == PlanningAction.Kind.CONTINUE) {
            var until = action.until();
            for (var wake : observation.wakeUps()) if (wake.isBefore(until)) until = wake;
            return engine.controlledAdvance(current, until, action.kind() == PlanningAction.Kind.CONTINUE);
        }
        var selected = new PlannedOperation(BranchEngine.nextOperationId(observation.issuedOperationIds()),
                new ScheduledSecurityAction(action.securityAction(), action.at(), action.contextId(),
                        action.keys(), "Chosen policy action"), action.contactId());
        return engine.controlledStart(current, Objects.requireNonNull(outcomes.forOperation(selected)).apply(selected));
    }
}

