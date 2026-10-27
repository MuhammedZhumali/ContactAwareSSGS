package org.contactaware.security.simulation;

import org.contactaware.security.AssociationStatus;
import org.contactaware.security.ContextId;
import org.contactaware.security.SecurityAction;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import static org.contactaware.security.simulation.PlanningAction.Kind.*;

/**
 * A common, deterministic feasible rollout, not an optimizer or an optimality certificate.
 * Every transition uses the ordinary finite generator and immutable branch execution.
 */
public final class FeasibleSeedPlanBuilder {
    private static final long DEFAULT_MAX_TRANSITIONS = 10_000;
    private static final List<PlanningAction.Kind> SERVICE_ORDER = List.of(CONTINUE, OPEN, REUSE, SWITCH, INSTALL, PREPARE);
    private final BranchEngine branches = new BranchEngine();
    private final SecurityActionGenerator generator = new SecurityActionGenerator();
    private final long maxTransitions;
    private long transitions;

    /** A failed bounded rollout has no terminal branch or score; its work is still counted. */
    public record Result(PlanningResult result, PlanningBranch terminal, long transitions) {
        public Result {
            if (transitions < 0 || (result == null) != (terminal == null))
                throw new IllegalArgumentException("Invalid feasible seed result");
            if (terminal != null && !terminal.snapshot().at().equals(terminal.origin().evaluationEnd()))
                throw new IllegalArgumentException("A seed score requires the complete evaluation horizon");
        }
        public boolean completed() { return result != null; }
    }

    public FeasibleSeedPlanBuilder() { this(DEFAULT_MAX_TRANSITIONS); }

    /** Last invocation's transition attempts, including an execution interrupted by SearchControl. */
    public long transitions() { return transitions; }

    FeasibleSeedPlanBuilder(long maxTransitions) {
        if (maxTransitions < 0) throw new IllegalArgumentException("Negative seed transition limit");
        this.maxTransitions = maxTransitions;
    }

    public Result build(PlanningObservation observation, PlanTieBreak rule) {
        transitions = 0;
        Objects.requireNonNull(observation); Objects.requireNonNull(rule);
        SearchControl.checkpoint("seed.build");
        return complete(branches.create(observation), rule);
    }

    /** Preserve an existing generated prefix and count only the additional transitions. */
    public Result complete(PlanningBranch branch, PlanTieBreak rule) {
        transitions = 0;
        Objects.requireNonNull(branch); Objects.requireNonNull(rule);
        var comparator = new PlanningResultComparator(rule);
        while (branch.snapshot().at().isBefore(branch.origin().evaluationEnd())) {
            SearchControl.checkpoint("seed.transition");
            if (transitions >= maxTransitions) return new Result(null, null, transitions);
            var observation = branch.observation();
            var actions = generator.candidates(observation);
            var action = choose(observation, actions, comparator);
            transitions++;
            branch = branches.executeGenerated(branch, observation, action);
        }
        SearchControl.checkpoint("seed.evaluate");
        // Inherited committed work can fail at a known expiry. Such a rollout is not a seed.
        for (var event : branch.snapshot().journal()) {
            SearchControl.checkpoint("seed.journal");
            if (event.type() == SimulationEvent.Type.ACTION_FAILED || event.type() == SimulationEvent.Type.PAYLOAD_REJECTED)
                return new Result(null, null, transitions);
        }
        return new Result(new ContinuationEvaluator().evaluate(branch), branch, transitions);
    }

    private PlanningAction choose(PlanningObservation observation, List<PlanningAction> actions,
                                  PlanningResultComparator comparator) {
        // An admitted frame or operation is already committed. WAIT settles its next event;
        // it neither restarts a frame nor grants automatic payload after that frame completes.
        if (observation.inFlightFrame() == null && observation.ongoingOperations().isEmpty()) {
            var current = observation.currentContact();
            if (current != null && hasDemand(observation, current)) {
                var target = context(current);
                for (var kind : SERVICE_ORDER) {
                    SearchControl.checkpoint("seed.service");
                    PlanningAction best = null;
                    for (var action : actions) {
                        SearchControl.checkpoint("seed.candidate");
                        if (action.kind() != kind || !target.equals(action.contextId())
                                || !canFinishService(observation, current, action)) continue;
                        if (best == null || comparator.compareActions(action, best) < 0) best = action;
                    }
                    if (best != null) return best;
                }
            } else if (current == null) {
                for (var contact : observation.visibleContacts()) {
                    SearchControl.checkpoint("seed.contact");
                    if (!hasDemand(observation, contact)) continue;
                    var target = context(contact);
                    var state = observation.securityState().contexts().get(target);
                    if (state == null || state.status() == AssociationStatus.ABSENT
                            || observation.uncertainContexts().contains(target)
                            || !observation.constraints().authorizedContexts().contains(target)) continue;
                    // Prepare once, near the nearest useful contact. Existing keyed material
                    // can be opened first; its ordinary expiry timers permit later renewal.
                    var prepareAt = contact.window().start().minus(observation.costs().cost(SecurityAction.PREPARE).duration());
                    if (state.status() == AssociationStatus.UNKEYED && !observation.at().isBefore(prepareAt)) {
                        PlanningAction best = null;
                        for (var action : actions) {
                            SearchControl.checkpoint("seed.candidate");
                            if (action.kind() != PREPARE || !target.equals(action.contextId())) continue;
                            if (best == null || comparator.compareActions(action, best) < 0) best = action;
                        }
                        if (best != null) return best;
                    }
                    break;
                }
            }
        }
        PlanningAction wait = null;
        for (var action : actions) {
            SearchControl.checkpoint("seed.wait");
            if (action.kind() == WAIT && (wait == null || action.until().isBefore(wait.until())
                    || action.until().equals(wait.until()) && comparator.compareActions(action, wait) < 0)) wait = action;
        }
        return Objects.requireNonNull(wait, "A nonterminal generated observation must permit WAIT");
    }

    private boolean hasDemand(PlanningObservation observation, SimulationContact contact) {
        var progress = observation.payloadProgress().get(contact.window().id());
        var payload = contact.payload();
        long up = progress == null ? 0 : progress.uplinkBytes();
        long down = progress == null ? 0 : progress.downlinkBytes();
        return payload.offeredUplinkBytes() - up >= payload.frameBytes()
                || payload.offeredDownlinkBytes() - down >= payload.frameBytes();
    }

    /** Do not begin a service chain when even its optimistic final complete frame cannot fit. */
    private boolean canFinishService(PlanningObservation observation, SimulationContact contact, PlanningAction action) {
        if (observation.uncertainContexts().contains(action.contextId())) return false;
        Duration remaining = contact.payload().frameDuration();
        switch (action.kind()) {
            case PREPARE -> remaining = remaining.plus(observation.costs().cost(SecurityAction.INSTALL).duration())
                    .plus(observation.costs().cost(SecurityAction.SWITCH).duration())
                    .plus(observation.costs().cost(SecurityAction.OPEN).duration());
            case INSTALL -> remaining = remaining.plus(observation.costs().cost(SecurityAction.SWITCH).duration())
                    .plus(observation.costs().cost(SecurityAction.OPEN).duration());
            case SWITCH -> remaining = remaining.plus(observation.costs().cost(SecurityAction.OPEN).duration());
            case CONTINUE -> remaining = Duration.ZERO;
            default -> { }
        }
        return !action.until().plus(remaining).isAfter(contact.window().end());
    }

    private ContextId context(SimulationContact contact) {
        return new ContextId(contact.window().satelliteId(), contact.window().groundStationId());
    }
}
