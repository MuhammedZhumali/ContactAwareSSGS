package org.contactaware.security.simulation;

import org.contactaware.security.KeyStatus;
import java.time.Instant;
import java.util.*;

/**
 * Sufficient check for a nominal optimal suffix after ONE completed CONTINUE. No cache,
 * search, suffix execution or privileged actual snapshot is used. See docs/continue-suffix-check.md.
 * Evidence must be captured at the unrestricted ExactHorizonPlanner.plan call in this engine version;
 * HorizonPlanResult alone does not identify its root, rules or constrained-search provenance.
 */
public final class ContinuationReuseGuard {
    public enum Status { ALLOWED, REJECTED, UNSUPPORTED }
    public record Result(Status status, String reason) { }
    public record Rules(DecisionTimeModel timing, PlanTieBreak tieBreak) { }
    public enum SearchScope { UNRESTRICTED_CURRENT_ENGINE, FIXED_FIRST_ACTION, UNKNOWN }
    public record SearchEvidence(PlanningObservation origin, HorizonPlanResult result, Rules rules, SearchScope scope) { }

    /** A root proof or its derived suffix. A derivation is NOT another COMPLETE search result. */
    public static final class ProvenContinuation {
        private final SearchEvidence root;
        private final String reference;
        private final ProvenContinuation parent;
        private final PlanningObservation origin;
        private final int offset;
        private ProvenContinuation(SearchEvidence root, String reference) {
            this.root = root; this.reference = Objects.requireNonNull(reference);
            parent = null; origin = root.origin(); offset = 0;
        }
        private ProvenContinuation(ProvenContinuation parent, PlanningObservation origin) {
            this.root = parent.root; reference = parent.reference; this.parent = parent;
            this.origin = origin; offset = Math.incrementExact(parent.offset);
        }
        public String reference() { return reference; }
        public int offset() { return offset; }
        public PlanningAction firstAction() {
            return offset < root.result().bestPlan().size() ? root.result().bestPlan().get(offset) : null;
        }
    }

    /** Only an ALLOWED assessment can construct the next link; callers cannot forge a derived COMPLETE result. */
    public static final class Assessment {
        private final Result result;
        private final ProvenContinuation previous;
        private final PlanningObservation actual;
        private Assessment(Result result, ProvenContinuation previous, PlanningObservation actual) {
            this.result = result; this.previous = previous; this.actual = actual;
        }
        public Result result() { return result; }
        public ProvenContinuation derive() {
            if (result.status() != Status.ALLOWED) throw new IllegalStateException("Only a successful guard proves a suffix");
            return new ProvenContinuation(previous, actual);
        }
    }

    public ProvenContinuation start(SearchEvidence evidence, String reference) {
        if (evidence == null || evidence.origin() == null || evidence.result() == null || !known(evidence.rules())
                || evidence.scope() != SearchScope.UNRESTRICTED_CURRENT_ENGINE
                || evidence.result().status() != HorizonPlanResult.Status.COMPLETE)
            throw new IllegalArgumentException("A same-engine unrestricted COMPLETE root proof is required");
        return new ProvenContinuation(evidence, reference);
    }

    public Assessment assess(ProvenContinuation proof, PlanningObservation actual, Rules currentRules) {
        if (proof == null) return new Assessment(unsupported("NO_PROOF: no retained COMPLETE continuation"), null, actual);
        var verdict = checkStep(proof.origin, proof.firstAction(), proof.root.rules(), actual, currentRules);
        return new Assessment(verdict, proof, actual);
    }

    public Result check(SearchEvidence evidence, PlanningObservation actual, Rules currentRules) {
        if (evidence == null || evidence.origin() == null || evidence.result() == null
                || !known(evidence.rules()) || !known(currentRules) || actual == null)
            return unsupported("MISSING_EVIDENCE: original observation, result and both rule sets are required");
        var result = evidence.result();
        if (result.status() != HorizonPlanResult.Status.COMPLETE)
            return rejected("PLAN_NOT_COMPLETE: original search status is " + result.status());
        if (evidence.scope() != SearchScope.UNRESTRICTED_CURRENT_ENGINE)
            return unsupported("SEARCH_PROVENANCE: require an unrestricted search for this root in the current engine");
        return checkStep(evidence.origin(), result.firstAction(), evidence.rules(), actual, currentRules);
    }

    private Result checkStep(PlanningObservation origin, PlanningAction first, Rules originalRules,
                             PlanningObservation actual, Rules currentRules) {
        if (actual == null || !known(currentRules)) return unsupported("MISSING_EVIDENCE: actual observation and rules required");
        if (!originalRules.equals(currentRules))
            return rejected("RULES_CHANGED: decision timing or full-plan tie-break differs");
        if (currentRules.timing() != DecisionTimeModel.V2_EVENT_BOUNDARIES)
            return unsupported("TIMING_MODEL: only the existing V2 event-boundary model is supported");
        if (origin.horizon() != actual.horizon()) return rejected("HORIZON_CHANGED: H differs");
        if (!origin.visibleContacts().equals(actual.visibleContacts()))
            return rejected("FORECAST_CHANGED: visible contacts, their order or payload differs");
        if (!origin.evaluationEnd().equals(actual.evaluationEnd()))
            return rejected("EVALUATION_END_CHANGED: terminal time differs");
        if (!origin.costs().equals(actual.costs())) return rejected("COSTS_CHANGED: operation cost profile differs");
        if (!origin.constraints().equals(actual.constraints())) return rejected("CONSTRAINTS_CHANGED: security limits differ");
        if (origin.currentContact() == null) return unsupported("NO_CURRENT_CONTACT: root must be inside a contact");
        if (!origin.currentContact().equals(actual.currentContact()))
            return rejected("CONTACT_CHANGED: the first frame must finish inside the same contact");
        if (first == null || first.kind() != PlanningAction.Kind.CONTINUE)
            return unsupported("PREFIX_KIND: only one initial CONTINUE is supported");
        if (!first.at().equals(origin.at())
                || !first.until().equals(origin.at().plus(origin.currentContact().payload().frameDuration())))
            return rejected("PREFIX_INTERVAL: first action does not describe one complete frame from the root");
        if (!first.until().isBefore(origin.currentContact().window().end()))
            return unsupported("CONTACT_BOUNDARY: completion at or beyond contact end is outside this check");
        if (!actual.at().equals(first.until()) || actual.inFlightFrame() != null)
            return rejected("FRAME_NOT_COMPLETED: actual observation is not the settled full-frame endpoint");
        if (!quiescent(origin))
            return unsupported("COMMITTED_WORK: root has a frame, running operation or occupied resource");
        if (!origin.uncertainContexts().isEmpty())
            return unsupported("UNCERTAIN_CONTEXT: reconciliation is outside the supported nominal case");

        var branches = new BranchEngine();
        try {
            var root = branches.create(origin);
            // In particular, reject unrepresented custom timers or unsettled overdue deadlines.
            if (!root.observation().equals(origin))
                return unsupported("ROOT_RESTORATION: the supplied root cannot be reconstructed exactly");
            var child = branches.execute(root, first); // Only the first frame, never the saved suffix.
            var expected = child.observation();
            if (!child.snapshot().at().equals(first.until()) || !quiescent(expected))
                return unsupported("INTERRUPTED_PREFIX: a decision event interrupted the frame or work remains");
            var frames = child.snapshot().journal().stream()
                    .filter(e -> e.type() == SimulationEvent.Type.PAYLOAD_TRANSFERRED).toList();
            if (frames.size() != 1 || !frames.getFirst().usefulTime().equals(origin.currentContact().payload().frameDuration()))
                return unsupported("FRAME_EVIDENCE: nominal replay did not deliver exactly one whole frame");
            var mismatch = mismatch(expected, actual);
            if (mismatch != null) return rejected(mismatch);
            var restored = branches.create(actual);
            if (!restored.observation().equals(actual))
                return unsupported("CHILD_RESTORATION: the settled observation is not reproduced by restoration");
            var restorationIssue = restorationIssue(child.snapshot(), restored.snapshot());
            if (restorationIssue != null) return unsupported(restorationIssue);
            return new Result(Status.ALLOWED,
                    "ONE_FRAME_NOMINAL: full settled observation, forecast, rules and reconstructible future events match");
        } catch (IllegalArgumentException invalid) {
            return rejected("PREFIX_NOT_REPLAYABLE: " + invalid.getMessage());
        } catch (IllegalStateException unsupported) {
            return unsupported("RESTORATION_NOT_ESTABLISHED: " + unsupported.getMessage());
        }
    }

    private static boolean known(Rules rules) {
        return rules != null && rules.timing() != null && rules.tieBreak() != null;
    }

    private static boolean quiescent(PlanningObservation observation) {
        return observation.inFlightFrame() == null && observation.ongoingOperations().isEmpty()
                && observation.resourceReservations().isEmpty() && observation.recordedCosts().equals(observation.accruedCosts());
    }

    private static String mismatch(PlanningObservation expected, PlanningObservation actual) {
        if (!expected.securityState().equals(actual.securityState()))
            return "SECURITY_STATE_MISMATCH: keys, SAs, counters, deadlines or state timestamp differs";
        if (!expected.payloadProgress().equals(actual.payloadProgress()))
            return "PROGRESS_MISMATCH: delivered bytes or next-frame direction differs";
        if (!expected.recordedCosts().equals(actual.recordedCosts()) || !expected.accruedCosts().equals(actual.accruedCosts()))
            return "COST_PROGRESS_MISMATCH: recorded or accrued work differs";
        if (!expected.usefulSecureTime().equals(actual.usefulSecureTime()) || expected.createdKeys() != actual.createdKeys())
            return "TOTALS_MISMATCH: useful time or created-key count differs";
        if (!expected.issuedKeyIds().equals(actual.issuedKeyIds()) || !expected.issuedOperationIds().equals(actual.issuedOperationIds()))
            return "ISSUED_IDS_MISMATCH: key or operation ID history differs";
        if (!expected.ongoingOperations().equals(actual.ongoingOperations())
                || !expected.resourceReservations().equals(actual.resourceReservations())
                || !expected.uncertainContexts().equals(actual.uncertainContexts()))
            return "EXECUTION_MISMATCH: committed work, resources or uncertainty differs";
        if (!expected.wakeUps().equals(actual.wakeUps())) return "WAKEUPS_MISMATCH: future decision timers differ";
        // Record equality covers every field, including any fields added in a future version.
        return expected.equals(actual) ? null : "OBSERVATION_MISMATCH: another available observation field differs";
    }

    /** Event data is from nominal branches created from observations, never from the actual private queue. */
    private static String restorationIssue(ExecutionSnapshot child, ExecutionSnapshot restored) {
        if (!child.scenario().equals(restored.scenario()))
            return "RESTORED_CONFIGURATION: nominal execution configuration differs";
        var a = futureEvents(child);
        var b = futureEvents(restored);
        if (a == null || b == null || !a.equals(b))
            return "FUTURE_EVENTS: event semantics or ordering cannot be reproduced";
        // A frame can expire material. Registry entries for those permanently retired IDs are inert;
        // all still-live registrations must survive. IDs and retired keys themselves are preserved.
        var extra = new HashSet<>(child.registeredExpiryEvents());
        extra.removeAll(restored.registeredExpiryEvents());
        var retired = new HashSet<String>();
        for (var key : child.securityState().keys().values())
            if (key.status() == KeyStatus.DEACTIVATED || key.status() == KeyStatus.DESTROYED)
                retired.add(key.id() + ":" + key.hardDeadline(child.scenario().constraints()));
        if (!child.registeredExpiryEvents().containsAll(restored.registeredExpiryEvents()) || !retired.containsAll(extra))
            return "EXPIRY_REGISTRY: lost registration is not explained by permanently retired material";
        return null;
    }

    private record EventOrder(Instant at, ExecutionSnapshot.EventKind kind, String stableId) { }
    private record FutureEvent(EventOrder order, String referenceId, int stepIndex, Instant expectedDeadline) { }

    private static List<FutureEvent> futureEvents(ExecutionSnapshot snapshot) {
        var events = new ArrayList<FutureEvent>();
        var orderKeys = new HashSet<EventOrder>();
        for (var event : snapshot.queuedEvents()) {
            if (event.kind() != ExecutionSnapshot.EventKind.CONTACT_START
                    && event.kind() != ExecutionSnapshot.EventKind.CONTACT_END
                    && event.kind() != ExecutionSnapshot.EventKind.KEY_DEADLINE) return null;
            var order = new EventOrder(event.at(), event.kind(), event.stableId());
            // Insertion counters may differ after restoration; permit this only when never a tie-break.
            if (!event.at().isAfter(snapshot.at()) || !orderKeys.add(order)) return null;
            events.add(new FutureEvent(order, event.referenceId(), event.stepIndex(), event.expectedDeadline()));
        }
        return List.copyOf(events);
    }

    private static Result rejected(String reason) { return new Result(Status.REJECTED, reason); }
    private static Result unsupported(String reason) { return new Result(Status.UNSUPPORTED, reason); }
}
