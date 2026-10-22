package org.contactaware.security.simulation;

import org.contactaware.security.*;

import java.math.BigInteger;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * An optimistic lexicographic score, not an executable continuation.
 *
 * <p>Frame capacity ignores crypto deadlines, counters, resource contention and direction order.
 * Costs already consumed cannot be undone. A second, guarded relaxation conditions on serving
 * every geometrically eligible visible contact: mandatory online operations then occupy time
 * that payload cannot use. Eligibility ignores every security restriction and is fixed at the root.
 * Its costs/time are totals since the root, so work already executed is never subtracted twice.
 * A strictly worse metric bound permits pruning. Equal metrics still need an independent proof
 * that no continuation wins the canonical plan tie-break. A pruned subtree is not an exact
 * memoized suffix.</p>
 */
final class PlanningOptimisticBound {
    private static final BigInteger BILLION = BigInteger.valueOf(1_000_000_000L);
    private static final BigInteger ZERO = BigInteger.ZERO;
    private final PlanningObservation cachedOrigin;
    private final RootBound cachedRootBound;
    private final Set<String> cachedEligibleContacts;
    private final BigInteger cachedFrameQuantum;

    PlanningOptimisticBound() {
        cachedOrigin = null; cachedRootBound = null; cachedEligibleContacts = null; cachedFrameQuantum = null;
    }

    /** One immutable origin per search: its conditional relaxation need only be built once. */
    PlanningOptimisticBound(PlanningObservation origin) {
        SearchControl.checkpoint("bound.initialization");
        cachedOrigin = Objects.requireNonNull(origin);
        cachedEligibleContacts = geometricallyEligibleContacts(origin);
        cachedRootBound = eligibleContactsServedRootBound(origin, cachedEligibleContacts);
        cachedFrameQuantum = frameQuantum(origin);
    }

    BranchEvaluation upperBound(PlanningBranch branch) {
        Objects.requireNonNull(branch);
        var root = branch.origin();
        var snapshot = branch.snapshot();
        var eligibleContacts = root == cachedOrigin ? cachedEligibleContacts : geometricallyEligibleContacts(root);
        var quantum = root == cachedOrigin ? cachedFrameQuantum : frameQuantum(root);
        long initiallyServed = 0;
        long possiblyServed = 0;
        BigInteger futureUseful = ZERO;
        BigInteger remainingContactTime = ZERO;
        BigInteger inFlightElapsed = ZERO;
        Map<ContextId, BigInteger> remainingByContext = new HashMap<>();
        Map<ContextId, BigInteger> frameElapsedByContext = new HashMap<>();
        Map<ContextId, BigInteger> requiredPayloadByContext = new HashMap<>();

        var earlierRemainingContexts = new HashSet<ContextId>();
        for (var contact : root.visibleContacts()) {
            SearchControl.checkpoint("bound.loop");
            var payload = contact.payload();
            var oldProgress = root.payloadProgress().get(contact.window().id());
            if (served(payload, up(oldProgress), down(oldProgress))) initiallyServed++;
            var progress = snapshot.payloadProgress().get(contact.window().id());
            BigInteger up = up(progress), down = down(progress);
            var contextId = contextId(contact);
            var unitBytes = BigInteger.valueOf(payload.frameBytes());
            var neededFrames = ceilDivide(BigInteger.valueOf(payload.minimumUplinkBytes()).subtract(up).max(ZERO), unitBytes)
                    .add(ceilDivide(BigInteger.valueOf(payload.minimumDownlinkBytes()).subtract(down).max(ZERO), unitBytes));
            if (eligibleContacts.contains(contact.window().id()))
                requiredPayloadByContext.merge(contextId, neededFrames.multiply(nanos(payload.frameDuration())), BigInteger::add);
            Instant from = later(snapshot.at(), contact.window().start());
            Instant until = earlier(root.evaluationEnd(), contact.window().end());
            if (from.isBefore(until)) {
                var remaining = nanos(Duration.between(from, until));
                remainingContactTime = remainingContactTime.add(remaining);
                remainingByContext.merge(contextId, remaining, BigInteger::add);
            }
            var frame = snapshot.inFlightFrame();

            // Completion credits a whole frame, including its pre-snapshot elapsed part.
            // None of that frame has yet entered usefulSecureTime or payloadProgress.
            if (frame != null && frame.contact().window().id().equals(contact.window().id())
                    && !frame.until().isAfter(until)) {
                if (!frame.deliveryRejectedAtGround()) {
                    BigInteger bytes = BigInteger.valueOf(payload.frameBytes());
                    if (frame.uplink()) up = up.add(bytes); else down = down.add(bytes);
                    futureUseful = futureUseful.add(nanos(Duration.between(frame.from(), frame.until())));
                    inFlightElapsed = inFlightElapsed.add(nanos(Duration.between(frame.from(), snapshot.at())));
                    frameElapsedByContext.merge(contextId, nanos(Duration.between(frame.from(), snapshot.at())), BigInteger::add);
                }
                from = later(from, frame.until());
            }

            BigInteger availableTime = from.isBefore(until) ? nanos(Duration.between(from, until)) : ZERO;
            var context = snapshot.securityState().contexts().get(contextId(contact));
            boolean alreadyOpen = context != null && context.status() == AssociationStatus.OPERATIONAL
                    && context.activeContact().id().equals(contact.window().id());
            if (!alreadyOpen)
                availableTime = availableTime.subtract(openingDelay(branch, contextId,
                        later(snapshot.at(), contact.window().start()), earlierRemainingContexts.contains(contextId))).max(ZERO);
            if (later(snapshot.at(), contact.window().start()).isBefore(until)) earlierRemainingContexts.add(contextId);

            BigInteger frameTime = nanos(payload.frameDuration());
            BigInteger frameBytes = BigInteger.valueOf(payload.frameBytes());
            BigInteger upFrames = BigInteger.valueOf(payload.offeredUplinkBytes()).subtract(up).max(ZERO).divide(frameBytes);
            BigInteger downFrames = BigInteger.valueOf(payload.offeredDownlinkBytes()).subtract(down).max(ZERO).divide(frameBytes);
            BigInteger capacity = availableTime.divide(frameTime).min(upFrames.add(downFrames));
            futureUseful = futureUseful.add(capacity.multiply(frameTime));
            BigInteger missingUp = ceilDivide(BigInteger.valueOf(payload.minimumUplinkBytes()).subtract(up).max(ZERO), frameBytes);
            BigInteger missingDown = ceilDivide(BigInteger.valueOf(payload.minimumDownlinkBytes()).subtract(down).max(ZERO), frameBytes);
            if (eligibleContacts.contains(contact.window().id())
                    && upFrames.compareTo(missingUp) >= 0 && downFrames.compareTo(missingDown) >= 0
                    && capacity.compareTo(missingUp.add(missingDown)) >= 0) possiblyServed++;
        }

        BigInteger useful = nanos(snapshot.usefulSecureTime().minus(root.usefulSecureTime())).add(futureUseful);
        var accrued = snapshot.accruedCosts();
        Duration cpu = accrued.totalCpu().minus(root.accruedCosts().totalCpu());
        long bytes = Math.subtractExact(accrued.managementBytes(), root.accruedCosts().managementBytes());
        long keys = Math.subtractExact(snapshot.createdKeys(), root.createdKeys());
        var committedPreparationCpu = new HashMap<ContextId, Duration>();
        var committedPreparationKeys = new HashMap<ContextId, Long>();
        // A standalone PREPARE already started in a nominal branch cannot be cancelled by
        // a later decision or contact closure. Its remaining CPU is unavoidable, including
        // work on a pair that is never used; two keys are created if it finishes in the horizon.
        // Composite operations are excluded because their contact closure may interrupt them.
        for (var operation : snapshot.pendingOperations()) {
            SearchControl.checkpoint("bound.loop");
            if (operation.planned().intent().action() != SecurityAction.PREPARE
                    || operation.steps().size() != 1 || operation.step() != SecurityAction.PREPARE) continue;
            var cost = root.costs().cost(SecurityAction.PREPARE);
            var finish = operation.stepStartedAt().plus(cost.duration());
            var consumed = cost.consumedUntil(Duration.between(operation.stepStartedAt(), snapshot.at())).totalCpu();
            var projected = cost.consumedUntil(Duration.between(operation.stepStartedAt(), earlier(finish, root.evaluationEnd()))).totalCpu();
            var remaining = projected.minus(consumed);
            cpu = cpu.plus(remaining);
            var context = operation.planned().intent().contextId();
            committedPreparationCpu.merge(context, remaining, Duration::plus);
            if (!finish.isAfter(root.evaluationEnd())) {
                keys = Math.addExact(keys, 2);
                committedPreparationKeys.merge(context, 2L, Math::addExact);
            }
        }

        // No contact outside the root's geometric set can be served. Attaining |S| therefore
        // requires every S contact, even when some other window is intrinsically too short.
        // If this count is unattainable, it is already a strict upper bound on the first metric.
        if (possiblyServed == eligibleContacts.size()) {
            var allServed = root == cachedOrigin ? cachedRootBound : eligibleContactsServedRootBound(root, eligibleContacts);
            if (allServed != null) {
                var onlineByContext = consumedOnlineByContext(snapshot);
                BigInteger contextualFuture = ZERO;
                for (var entry : allServed.onlineByContext().entrySet()) {
                    SearchControl.checkpoint("bound.loop");
                    var context = entry.getKey();
                    var actualOnline = onlineByContext.getOrDefault(context, ResourceUsage.ZERO);
                    var remainingOnline = entry.getValue().subtract(nanos(actualOnline.elapsed())).max(ZERO);
                    var remaining = remainingByContext.getOrDefault(context, ZERO);
                    var partialFrame = frameElapsedByContext.getOrDefault(context, ZERO);
                    // INSTALL/SWITCH/OPEN for A cannot consume a B window. If even the relaxed
                    // per-context pool cannot hold the remaining mandatory work and required
                    // frames, serving ALL geometrically eligible contacts is impossible.
                    if (remaining.add(partialFrame).compareTo(remainingOnline.add(requiredPayloadByContext.getOrDefault(context, ZERO))) < 0)
                        return new BranchEvaluation(Math.subtractExact(possiblyServed - 1, initiallyServed),
                                duration(floorToQuantum(useful, quantum)), cpu, bytes, keys);
                    contextualFuture = contextualFuture.add(partialFrame).add(remaining.subtract(remainingOnline).max(ZERO));
                }
                useful = useful.min(allServed.usefulNanos());
                useful = useful.min(nanos(snapshot.usefulSecureTime().minus(root.usefulSecureTime())).add(contextualFuture));
                // All served plans require at least M online work. If A is all consumed work
                // and O its online part, A + max(0, M - O) is a lower bound. Unlike max(A,M),
                // this does not let an unrelated ground PREPARE pay for a future OPEN/INSTALL.
                // Extra online work overcredits M, which only makes the bound more optimistic.
                var online = onlineByContext.values().stream().peek(ignored -> SearchControl.checkpoint("bound.stream")).reduce(ResourceUsage.ZERO, ResourceUsage::plus);
                // Required online elapsed time also remains disjoint from payload. Unlike the
                // constant root cap, this tracks already wasted idle time: past useful + elapsed
                // part of the committed frame + remaining windows - unavoidable remaining work.
                BigInteger remainingOnline = allServed.onlineNanos().subtract(nanos(online.elapsed())).max(ZERO);
                BigInteger residualCap = nanos(snapshot.usefulSecureTime().minus(root.usefulSecureTime()))
                        .add(inFlightElapsed).add(remainingContactTime.subtract(remainingOnline).max(ZERO));
                useful = useful.min(residualCap);
                // Work at B cannot pay A's mandatory CPU or management bytes. Coordinatewise
                // residuals are lower bounds even when unnecessary work at A overcredits A.
                for (var context : allServed.onlineByContext().keySet()) {
                    SearchControl.checkpoint("bound.loop");
                    var actualOnline = onlineByContext.getOrDefault(context, ResourceUsage.ZERO);
                    var remainingCpu = allServed.onlineCpuByContext().get(context).minus(actualOnline.totalCpu());
                    if (!remainingCpu.isNegative()) cpu = cpu.plus(remainingCpu);
                    bytes = Math.addExact(bytes, Math.max(0, allServed.onlineBytesByContext().get(context) - actualOnline.managementBytes()));
                }
                // Without a prepared pair at the root, a required renewal must first finish
                // one PREPARE for that context, creating two directed keys. Ground work can
                // overlap payload, so no preparation elapsed time is subtracted from capacity.
                // Charge only the still-missing CPU/key creation, never the root's sunk work
                // again; an unrelated context's preparation cannot satisfy this requirement.
                if (!allServed.preparationContexts().isEmpty()) {
                    var preparationByContext = consumedPreparationByContext(snapshot);
                    var createdByContext = new HashMap<ContextId, Long>();
                    for (var event : snapshot.journal()) {
                        SearchControl.checkpoint("bound.journal");
                        if (event.createdKeys() > 0) createdByContext.merge(event.contextId(), event.createdKeys(), Math::addExact);
                    }
                    var mandatoryCpu = root.costs().cost(SecurityAction.PREPARE).total().totalCpu();
                    for (var context : allServed.preparationContexts()) {
                        SearchControl.checkpoint("bound.loop");
                        var spent = preparationByContext.getOrDefault(context, ResourceUsage.ZERO).totalCpu();
                        var remainingCpu = mandatoryCpu.minus(spent)
                                .minus(committedPreparationCpu.getOrDefault(context, Duration.ZERO));
                        if (!remainingCpu.isNegative()) cpu = cpu.plus(remainingCpu);
                        keys = Math.addExact(keys, Math.max(0L, 2L - createdByContext.getOrDefault(context, 0L)
                                - committedPreparationKeys.getOrDefault(context, 0L)));
                    }
                }
            }
        }
        // Every newly credited frame contributes its complete duration, including an inherited
        // in-flight frame. The objective increment is therefore an integer multiple of their
        // GCD. Rounding an optimistic continuous-time cap down to this lattice remains safe.
        return new BranchEvaluation(Math.subtractExact(possiblyServed, initiallyServed),
                duration(floorToQuantum(useful, quantum)), cpu, bytes, keys);
    }

    /**
     * Every geometrically eligible not-yet-served closed window needs OPEN/REUSE;
     * an open current window needs none. Ineligible windows remain in the time pool and
     * may still contribute useful frames or host an installation for a later eligible window.
     * Partial current delivery is retained, and an already served window may reopen for free.
     * A context
     * whose existing active material cannot reach its final window additionally needs at least
     * one SWITCH, and INSTALL unless an installed prepared pair already exists. If no prepared
     * pair exists, at least one completed PREPARE is necessary as well. Its CPU and created
     * material are charged; its elapsed time is free because it can overlap useful payload.
     * All further renewals and every other constraint are free in this relaxation.
     *
     * <p>The time pool includes the remaining portion of ALL visible contact windows, including idle/finite-budget
     * windows. Thus an installation staged in an earlier window is charged once without
     * incorrectly assuming it displaces useful payload in a particular later window. Pending
     * operations retain their full primitive requirement; only their consumed part is credited.
     * Completed work is represented by the root inventory/open state and is not charged again.
     * The elapsed part of a committed root frame is added to the pool: its eventual whole-frame
     * useful-time credit includes that pre-root portion, which is not yet recorded as useful.</p>
     */
    private RootBound eligibleContactsServedRootBound(PlanningObservation root, Set<String> eligibleContacts) {
        BigInteger pool = ZERO, overhead = ZERO;
        Map<ContextId, Instant> latestStarts = new HashMap<>();
        Map<ContextId, BigInteger> onlineByContext = new HashMap<>();
        Map<ContextId, Duration> onlineCpuByContext = new HashMap<>();
        Map<ContextId, Long> onlineBytesByContext = new HashMap<>();
        Set<ContextId> preparationContexts = new HashSet<>();
        var open = root.costs().cost(SecurityAction.OPEN).total();
        var reuse = root.costs().cost(SecurityAction.REUSE).total();
        Duration openingCpu = open.totalCpu().compareTo(reuse.totalCpu()) <= 0 ? open.totalCpu() : reuse.totalCpu();
        long openingBytes = Math.min(open.managementBytes(), reuse.managementBytes());
        for (var contact : root.visibleContacts()) {
            SearchControl.checkpoint("bound.loop");
            var progress = root.payloadProgress().get(contact.window().id());
            // The current PayloadProfile constructor already rejects zero total demand;
            // keep this sufficient guard explicit if that input contract is relaxed later.
            if (contact.payload().minimumUplinkBytes() == 0 && contact.payload().minimumDownlinkBytes() == 0) return null;
            if (contact.window().end().isAfter(root.evaluationEnd())) return null;
            var from = later(root.at(), contact.window().start());
            if (!from.isBefore(contact.window().end())) continue;
            pool = pool.add(nanos(Duration.between(from, contact.window().end())));
            var frame = root.inFlightFrame();
            if (frame != null && frame.contact().window().id().equals(contact.window().id())
                    && !frame.until().isAfter(contact.window().end()) && !frame.deliveryRejectedAtGround())
                pool = pool.add(nanos(Duration.between(frame.from(), root.at())));
            var id = contextId(contact);
            // Zero entries retain optional payload capacity in contexts whose service is
            // already achieved or whose current association is open.
            onlineByContext.putIfAbsent(id, ZERO);
            onlineCpuByContext.putIfAbsent(id, Duration.ZERO);
            onlineBytesByContext.putIfAbsent(id, 0L);
            if (!eligibleContacts.contains(contact.window().id())
                    || served(contact.payload(), up(progress), down(progress))) continue;
            var context = root.securityState().contexts().get(id);
            boolean alreadyOpen = context != null && context.status() == AssociationStatus.OPERATIONAL
                    && context.activeContact().id().equals(contact.window().id());
            if (!alreadyOpen) {
                overhead = overhead.add(minimumOpeningTime(root.costs()));
                onlineByContext.merge(id, minimumOpeningTime(root.costs()), BigInteger::add);
                onlineCpuByContext.merge(id, openingCpu, Duration::plus);
                onlineBytesByContext.merge(id, openingBytes, Math::addExact);
            }
            latestStarts.merge(id, from, PlanningOptimisticBound::later);
        }
        for (var entry : latestStarts.entrySet()) {
            SearchControl.checkpoint("bound.loop");
            Instant upDeadline = null, downDeadline = null;
            boolean installedUp = false, installedDown = false, preparedUp = false, preparedDown = false;
            for (var key : root.securityState().keys().values()) {
                SearchControl.checkpoint("bound.loop");
                if (!key.contextId().equals(entry.getKey())) continue;
                if (key.status() == KeyStatus.ACTIVE) {
                    // Grant arbitrary pairing/rebinding of every existing active key. This is
                    // more permissive than the executor, hence cannot invent a required renewal.
                    Instant deadline = key.hardDeadline(root.constraints());
                    if (key.direction() == Direction.UPLINK)
                        upDeadline = upDeadline == null ? deadline : later(upDeadline, deadline);
                    else downDeadline = downDeadline == null ? deadline : later(downDeadline, deadline);
                } else if (key.status() == KeyStatus.PRE_ACTIVE) {
                    // Grant arbitrary pairing and ignore prepared-key deadlines. This can only
                    // understate the preparation/installation required by an actual plan.
                    if (key.direction() == Direction.UPLINK) {
                        preparedUp = true;
                        installedUp |= key.installedOnBothPeers();
                    } else {
                        preparedDown = true;
                        installedDown |= key.installedOnBothPeers();
                    }
                }
            }
            if (upDeadline != null && downDeadline != null && entry.getValue().isBefore(upDeadline)
                    && entry.getValue().isBefore(downDeadline)) continue;
            if (!preparedUp || !preparedDown) preparationContexts.add(entry.getKey());
            var switching = root.costs().cost(SecurityAction.SWITCH);
            overhead = overhead.add(nanos(switching.duration()));
            onlineByContext.merge(entry.getKey(), nanos(switching.duration()), BigInteger::add);
            onlineCpuByContext.merge(entry.getKey(), switching.total().totalCpu(), Duration::plus);
            onlineBytesByContext.merge(entry.getKey(), switching.total().managementBytes(), Math::addExact);
            if (!installedUp || !installedDown) {
                var installation = root.costs().cost(SecurityAction.INSTALL);
                overhead = overhead.add(nanos(installation.duration()));
                onlineByContext.merge(entry.getKey(), nanos(installation.duration()), BigInteger::add);
                onlineCpuByContext.merge(entry.getKey(), installation.total().totalCpu(), Duration::plus);
                onlineBytesByContext.merge(entry.getKey(), installation.total().managementBytes(), Math::addExact);
            }
        }
        var rootPendingOnline = new HashMap<ContextId, ResourceUsage>();
        for (var operation : root.ongoingOperations()) {
            SearchControl.checkpoint("bound.loop");
            var step = operation.steps().get(operation.stepIndex());
            if (step.requiresContact()) rootPendingOnline.merge(operation.contextId(), root.costs().cost(step)
                    .consumedUntil(Duration.between(operation.stepStartedAt(), root.at())), ResourceUsage::plus);
        }
        BigInteger remainingOverhead = ZERO;
        for (var entry : onlineByContext.entrySet()) {
            SearchControl.checkpoint("bound.context");
            remainingOverhead = remainingOverhead.add(entry.getValue()
                    .subtract(nanos(rootPendingOnline.getOrDefault(entry.getKey(), ResourceUsage.ZERO).elapsed())).max(ZERO));
        }
        return new RootBound(pool.subtract(remainingOverhead).max(ZERO), overhead, Map.copyOf(onlineByContext),
                Map.copyOf(onlineCpuByContext), Map.copyOf(onlineBytesByContext), Set.copyOf(preparationContexts));
    }

    private record RootBound(BigInteger usefulNanos, BigInteger onlineNanos, Map<ContextId, BigInteger> onlineByContext,
                             Map<ContextId, Duration> onlineCpuByContext, Map<ContextId, Long> onlineBytesByContext,
                             Set<ContextId> preparationContexts) { }

    /**
     * A security-free geometric superset of every contact any continuation can serve.
     * Confirmed progress stays available; the committed frame is credited once before
     * distributing remaining whole-frame slots freely between directions. Ignoring setup,
     * deadlines, counters, alternation and resource use can only enlarge this fixed set.
     */
    private Set<String> geometricallyEligibleContacts(PlanningObservation root) {
        var eligible = new HashSet<String>();
        for (var contact : root.visibleContacts()) {
            SearchControl.checkpoint("bound.loop");
            var payload = contact.payload();
            var progress = root.payloadProgress().get(contact.window().id());
            var up = up(progress);
            var down = down(progress);
            var from = later(root.at(), contact.window().start());
            var until = earlier(root.evaluationEnd(), contact.window().end());
            var frame = root.inFlightFrame();
            if (frame != null && frame.contact().window().id().equals(contact.window().id())
                    && !frame.until().isAfter(until)) {
                if (!frame.deliveryRejectedAtGround()) {
                    if (frame.uplink()) up = up.add(BigInteger.valueOf(payload.frameBytes()));
                    else down = down.add(BigInteger.valueOf(payload.frameBytes()));
                }
                from = later(from, frame.until());
            }
            var unitBytes = BigInteger.valueOf(payload.frameBytes());
            var missingUp = ceilDivide(BigInteger.valueOf(payload.minimumUplinkBytes()).subtract(up).max(ZERO), unitBytes);
            var missingDown = ceilDivide(BigInteger.valueOf(payload.minimumDownlinkBytes()).subtract(down).max(ZERO), unitBytes);
            var offeredUp = BigInteger.valueOf(payload.offeredUplinkBytes()).subtract(up).max(ZERO).divide(unitBytes);
            var offeredDown = BigInteger.valueOf(payload.offeredDownlinkBytes()).subtract(down).max(ZERO).divide(unitBytes);
            var remaining = from.isBefore(until) ? nanos(Duration.between(from, until)) : ZERO;
            var slots = remaining.divide(nanos(payload.frameDuration()));
            if (offeredUp.compareTo(missingUp) >= 0 && offeredDown.compareTo(missingDown) >= 0
                    && slots.compareTo(missingUp.add(missingDown)) >= 0) eligible.add(contact.window().id());
        }
        return Set.copyOf(eligible);
    }

    private BigInteger frameQuantum(PlanningObservation root) {
        var quantum = ZERO;
        for (var contact : root.visibleContacts()) {
            SearchControl.checkpoint("bound.quantum");
            quantum = quantum.gcd(nanos(contact.payload().frameDuration()));
        }
        if (root.inFlightFrame() != null && !root.inFlightFrame().deliveryRejectedAtGround()) quantum = quantum.gcd(
                nanos(Duration.between(root.inFlightFrame().from(), root.inFlightFrame().until())));
        return quantum.signum() == 0 ? BigInteger.ONE : quantum;
    }

    private BigInteger floorToQuantum(BigInteger value, BigInteger quantum) {
        return value.divide(quantum).multiply(quantum);
    }

    /**
     * Before the first remaining window of a context, no future INSTALL/SWITCH can be staged
     * through another station. Grant free preparation, arbitrary compatible key pairing and
     * unlimited lifetime after renewal. Even with these relaxations, opening may require
     * INSTALL + SWITCH. Later windows may use material staged earlier, so only OPEN is charged.
     * Own pending online work grants instantaneous access: its residual cost is not charged twice.
     */
    private BigInteger openingDelay(PlanningBranch branch, ContextId context, Instant from, boolean earlierWindow) {
        var snapshot = branch.snapshot();
        var costs = branch.origin().costs();
        if (snapshot.pendingOperations().stream().peek(ignored -> SearchControl.checkpoint("bound.stream")).anyMatch(op -> op.planned().intent().contextId().equals(context)
                && op.planned().intent().action().requiresContact())) return ZERO;
        BigInteger delay = minimumOpeningTime(costs);
        if (earlierWindow) return delay;
        boolean activeUp = false, activeDown = false, installedUp = false, installedDown = false;
        for (var key : snapshot.securityState().keys().values()) {
            SearchControl.checkpoint("bound.loop");
            if (!key.contextId().equals(context)) continue;
            if (key.status() == KeyStatus.ACTIVE && from.isBefore(key.hardDeadline(branch.origin().constraints()))) {
                if (key.direction() == Direction.UPLINK) activeUp = true; else activeDown = true;
            } else if (key.status() == KeyStatus.PRE_ACTIVE && key.installedOnBothPeers()) {
                // Ignoring the staged key's age/deadline can only understate the necessary work.
                if (key.direction() == Direction.UPLINK) installedUp = true; else installedDown = true;
            }
        }
        if (activeUp && activeDown) return delay;
        delay = delay.add(nanos(costs.cost(SecurityAction.SWITCH).duration()));
        if (!installedUp || !installedDown) delay = delay.add(nanos(costs.cost(SecurityAction.INSTALL).duration()));
        return delay;
    }

    private Map<ContextId, ResourceUsage> consumedOnlineByContext(ExecutionSnapshot snapshot) {
        var usage = new HashMap<ContextId, ResourceUsage>();
        for (var event : snapshot.journal()) {
            SearchControl.checkpoint("bound.journal");
            if (event.action() != null && event.action().requiresContact()) usage.merge(event.contextId(), event.usage(), ResourceUsage::plus);
        }
        for (var operation : snapshot.pendingOperations()) {
            SearchControl.checkpoint("bound.operation");
            if (operation.step().requiresContact()) usage.merge(operation.planned().intent().contextId(), snapshot.scenario().costs().cost(operation.step())
                    .consumedUntil(Duration.between(operation.stepStartedAt(), snapshot.at())), ResourceUsage::plus);
        }
        return usage;
    }

    private Map<ContextId, ResourceUsage> consumedPreparationByContext(ExecutionSnapshot snapshot) {
        var usage = new HashMap<ContextId, ResourceUsage>();
        for (var event : snapshot.journal()) {
            SearchControl.checkpoint("bound.journal");
            if (event.action() == SecurityAction.PREPARE) usage.merge(event.contextId(), event.usage(), ResourceUsage::plus);
        }
        for (var operation : snapshot.pendingOperations()) {
            SearchControl.checkpoint("bound.operation");
            if (operation.step() == SecurityAction.PREPARE)
                usage.merge(operation.planned().intent().contextId(), snapshot.scenario().costs().cost(SecurityAction.PREPARE)
                        .consumedUntil(Duration.between(operation.stepStartedAt(), snapshot.at())), ResourceUsage::plus);
        }
        return usage;
    }

    private static ContextId contextId(SimulationContact contact) {
        return new ContextId(contact.window().satelliteId(), contact.window().groundStationId());
    }

    private static BigInteger minimumOpeningTime(SecurityCostProfile costs) {
        return nanos(costs.cost(SecurityAction.OPEN).duration()).min(nanos(costs.cost(SecurityAction.REUSE).duration()));
    }

    private static boolean served(SimulationContact.PayloadProfile payload, BigInteger up, BigInteger down) {
        return up.compareTo(BigInteger.valueOf(payload.minimumUplinkBytes())) >= 0
                && down.compareTo(BigInteger.valueOf(payload.minimumDownlinkBytes())) >= 0;
    }

    private static BigInteger up(ExecutionSnapshot.PayloadProgress progress) {
        return progress == null ? ZERO : BigInteger.valueOf(progress.uplinkBytes());
    }

    private static BigInteger down(ExecutionSnapshot.PayloadProgress progress) {
        return progress == null ? ZERO : BigInteger.valueOf(progress.downlinkBytes());
    }

    private static BigInteger ceilDivide(BigInteger numerator, BigInteger denominator) {
        return numerator.add(denominator).subtract(BigInteger.ONE).divide(denominator);
    }

    private static BigInteger nanos(Duration value) {
        return BigInteger.valueOf(value.getSeconds()).multiply(BILLION).add(BigInteger.valueOf(value.getNano()));
    }

    private static Duration duration(BigInteger nanos) {
        var parts = nanos.divideAndRemainder(BILLION);
        return Duration.ofSeconds(parts[0].longValueExact(), parts[1].longValueExact());
    }

    private static Instant later(Instant a, Instant b) { return a.isAfter(b) ? a : b; }
    private static Instant earlier(Instant a, Instant b) { return a.isBefore(b) ? a : b; }
}
