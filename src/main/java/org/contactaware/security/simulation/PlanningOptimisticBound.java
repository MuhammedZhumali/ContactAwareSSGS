package org.contactaware.security.simulation;

import org.contactaware.security.*;

import java.math.BigInteger;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;

/**
 * An optimistic lexicographic score, not an executable continuation.
 *
 * <p>Frame capacity ignores crypto deadlines, counters, resource contention and direction order.
 * Costs already consumed cannot be undone. A second, guarded relaxation conditions on serving
 * every visible contact: mandatory online operations then occupy time that payload cannot use.
 * Its costs/time are totals since the root, so work already executed is never subtracted twice.
 * Only a STRICTLY worse metric bound permits pruning; equal metrics may still win the canonical
 * plan tie-break. A pruned subtree is not an exact memoized suffix.</p>
 */
final class PlanningOptimisticBound {
    private static final BigInteger BILLION = BigInteger.valueOf(1_000_000_000L);
    private static final BigInteger ZERO = BigInteger.ZERO;

    BranchEvaluation upperBound(PlanningBranch branch) {
        Objects.requireNonNull(branch);
        var root = branch.origin();
        var snapshot = branch.snapshot();
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
            var payload = contact.payload();
            var oldProgress = root.payloadProgress().get(contact.window().id());
            if (served(payload, up(oldProgress), down(oldProgress))) initiallyServed++;
            var progress = snapshot.payloadProgress().get(contact.window().id());
            BigInteger up = up(progress), down = down(progress);
            var contextId = contextId(contact);
            var unitBytes = BigInteger.valueOf(payload.frameBytes());
            var neededFrames = ceilDivide(BigInteger.valueOf(payload.minimumUplinkBytes()).subtract(up).max(ZERO), unitBytes)
                    .add(ceilDivide(BigInteger.valueOf(payload.minimumDownlinkBytes()).subtract(down).max(ZERO), unitBytes));
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
                BigInteger bytes = BigInteger.valueOf(payload.frameBytes());
                if (frame.uplink()) up = up.add(bytes); else down = down.add(bytes);
                futureUseful = futureUseful.add(nanos(Duration.between(frame.from(), frame.until())));
                inFlightElapsed = inFlightElapsed.add(nanos(Duration.between(frame.from(), snapshot.at())));
                frameElapsedByContext.merge(contextId, nanos(Duration.between(frame.from(), snapshot.at())), BigInteger::add);
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
            if (upFrames.compareTo(missingUp) >= 0 && downFrames.compareTo(missingDown) >= 0
                    && capacity.compareTo(missingUp.add(missingDown)) >= 0) possiblyServed++;
        }

        BigInteger useful = nanos(snapshot.usefulSecureTime().minus(root.usefulSecureTime())).add(futureUseful);
        var accrued = snapshot.accruedCosts();
        Duration cpu = accrued.totalCpu().minus(root.accruedCosts().totalCpu());
        long bytes = Math.subtractExact(accrued.managementBytes(), root.accruedCosts().managementBytes());
        long keys = Math.subtractExact(snapshot.createdKeys(), root.createdKeys());

        // This tighter tuple is conditional on attaining its first component. If all visible
        // contacts cannot in fact be served, the first component is itself a strict upper bound.
        if (possiblyServed == root.visibleContacts().size()) {
            var allServed = allContactsServedRootBound(root);
            if (allServed != null) {
                var onlineByContext = consumedOnlineByContext(snapshot);
                BigInteger contextualFuture = ZERO;
                for (var entry : allServed.onlineByContext().entrySet()) {
                    var context = entry.getKey();
                    var actualOnline = onlineByContext.getOrDefault(context, ResourceUsage.ZERO);
                    var remainingOnline = entry.getValue().subtract(nanos(actualOnline.elapsed())).max(ZERO);
                    var remaining = remainingByContext.getOrDefault(context, ZERO);
                    var partialFrame = frameElapsedByContext.getOrDefault(context, ZERO);
                    // INSTALL/SWITCH/OPEN for A cannot consume a B window. If even the relaxed
                    // per-context pool cannot hold the remaining mandatory work and required
                    // frames, serving ALL visible contacts is impossible (at least one is lost).
                    if (remaining.add(partialFrame).compareTo(remainingOnline.add(requiredPayloadByContext.getOrDefault(context, ZERO))) < 0)
                        return new BranchEvaluation(Math.subtractExact(possiblyServed - 1, initiallyServed),
                                duration(useful), cpu, bytes, keys);
                    contextualFuture = contextualFuture.add(partialFrame).add(remaining.subtract(remainingOnline).max(ZERO));
                }
                useful = useful.min(allServed.usefulNanos());
                useful = useful.min(nanos(snapshot.usefulSecureTime().minus(root.usefulSecureTime())).add(contextualFuture));
                // All served plans require at least M online work. If A is all consumed work
                // and O its online part, A + max(0, M - O) is a lower bound. Unlike max(A,M),
                // this does not let an unrelated ground PREPARE pay for a future OPEN/INSTALL.
                // Extra online work overcredits M, which only makes the bound more optimistic.
                var online = onlineByContext.values().stream().reduce(ResourceUsage.ZERO, ResourceUsage::plus);
                // Required online elapsed time also remains disjoint from payload. Unlike the
                // constant root cap, this tracks already wasted idle time: past useful + elapsed
                // part of the committed frame + remaining windows - unavoidable remaining work.
                BigInteger remainingOnline = allServed.onlineNanos().subtract(nanos(online.elapsed())).max(ZERO);
                BigInteger residualCap = nanos(snapshot.usefulSecureTime().minus(root.usefulSecureTime()))
                        .add(inFlightElapsed).add(remainingContactTime.subtract(remainingOnline).max(ZERO));
                useful = useful.min(residualCap);
                Duration remainingCpu = allServed.cpu().minus(online.totalCpu());
                if (!remainingCpu.isNegative()) cpu = cpu.plus(remainingCpu);
                bytes = Math.addExact(bytes, Math.max(0, allServed.bytes() - online.managementBytes()));
            }
        }
        return new BranchEvaluation(Math.subtractExact(possiblyServed, initiallyServed),
                duration(useful), cpu, bytes, keys);
    }

    /**
     * Safe sufficient guards: all visible windows are ahead, no partial payload/operation exists,
     * and all contexts are closed. Every served window therefore needs OPEN/REUSE. A context
     * whose existing active material cannot reach its final window additionally needs at least
     * one SWITCH, and INSTALL unless an installed prepared pair already exists. Preparation,
     * all further renewals and every other constraint are free in this relaxation.
     *
     * <p>The time pool includes ALL visible contact windows, including idle/finite-budget
     * windows. Thus an installation staged in an earlier window is charged once without
     * incorrectly assuming it displaces useful payload in a particular later window.</p>
     */
    private RootBound allContactsServedRootBound(PlanningObservation root) {
        if (!root.ongoingOperations().isEmpty() || root.inFlightFrame() != null
                || root.securityState().contexts().values().stream()
                .anyMatch(context -> context.status() == AssociationStatus.OPERATIONAL)) return null;
        BigInteger pool = ZERO, overhead = ZERO;
        Duration cpu = Duration.ZERO;
        long bytes = 0;
        Map<ContextId, Instant> latestStarts = new HashMap<>();
        Map<ContextId, BigInteger> onlineByContext = new HashMap<>();
        var open = root.costs().cost(SecurityAction.OPEN).total();
        var reuse = root.costs().cost(SecurityAction.REUSE).total();
        Duration openingCpu = open.totalCpu().compareTo(reuse.totalCpu()) <= 0 ? open.totalCpu() : reuse.totalCpu();
        long openingBytes = Math.min(open.managementBytes(), reuse.managementBytes());
        for (var contact : root.visibleContacts()) {
            var progress = root.payloadProgress().get(contact.window().id());
            // The current PayloadProfile constructor already rejects zero total demand;
            // keep this sufficient guard explicit if that input contract is relaxed later.
            if (contact.payload().minimumUplinkBytes() == 0 && contact.payload().minimumDownlinkBytes() == 0) return null;
            if (contact.window().start().isBefore(root.at()) || up(progress).signum() != 0
                    || down(progress).signum() != 0 || contact.window().end().isAfter(root.evaluationEnd())) return null;
            pool = pool.add(nanos(Duration.between(contact.window().start(), contact.window().end())));
            overhead = overhead.add(minimumOpeningTime(root.costs()));
            onlineByContext.merge(contextId(contact), minimumOpeningTime(root.costs()), BigInteger::add);
            cpu = cpu.plus(openingCpu);
            bytes = Math.addExact(bytes, openingBytes);
            latestStarts.merge(contextId(contact), contact.window().start(), PlanningOptimisticBound::later);
        }
        for (var entry : latestStarts.entrySet()) {
            Instant upDeadline = null, downDeadline = null;
            boolean installedUp = false, installedDown = false;
            for (var key : root.securityState().keys().values()) {
                if (!key.contextId().equals(entry.getKey())) continue;
                if (key.status() == KeyStatus.ACTIVE) {
                    // Grant arbitrary pairing/rebinding of every existing active key. This is
                    // more permissive than the executor, hence cannot invent a required renewal.
                    Instant deadline = key.hardDeadline(root.constraints());
                    if (key.direction() == Direction.UPLINK)
                        upDeadline = upDeadline == null ? deadline : later(upDeadline, deadline);
                    else downDeadline = downDeadline == null ? deadline : later(downDeadline, deadline);
                } else if (key.status() == KeyStatus.PRE_ACTIVE && key.installedOnBothPeers()) {
                    if (key.direction() == Direction.UPLINK) installedUp = true; else installedDown = true;
                }
            }
            if (upDeadline != null && downDeadline != null && entry.getValue().isBefore(upDeadline)
                    && entry.getValue().isBefore(downDeadline)) continue;
            var switching = root.costs().cost(SecurityAction.SWITCH);
            overhead = overhead.add(nanos(switching.duration()));
            onlineByContext.merge(entry.getKey(), nanos(switching.duration()), BigInteger::add);
            cpu = cpu.plus(switching.total().totalCpu());
            bytes = Math.addExact(bytes, switching.total().managementBytes());
            if (!installedUp || !installedDown) {
                var installation = root.costs().cost(SecurityAction.INSTALL);
                overhead = overhead.add(nanos(installation.duration()));
                onlineByContext.merge(entry.getKey(), nanos(installation.duration()), BigInteger::add);
                cpu = cpu.plus(installation.total().totalCpu());
                bytes = Math.addExact(bytes, installation.total().managementBytes());
            }
        }
        return new RootBound(pool.subtract(overhead).max(ZERO), overhead, cpu, bytes, Map.copyOf(onlineByContext));
    }

    private record RootBound(BigInteger usefulNanos, BigInteger onlineNanos, Duration cpu, long bytes,
                             Map<ContextId, BigInteger> onlineByContext) { }

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
        if (snapshot.pendingOperations().stream().anyMatch(op -> op.planned().intent().contextId().equals(context)
                && op.planned().intent().action().requiresContact())) return ZERO;
        BigInteger delay = minimumOpeningTime(costs);
        if (earlierWindow) return delay;
        boolean activeUp = false, activeDown = false, installedUp = false, installedDown = false;
        for (var key : snapshot.securityState().keys().values()) {
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
        for (var event : snapshot.journal())
            if (event.action() != null && event.action().requiresContact()) usage.merge(event.contextId(), event.usage(), ResourceUsage::plus);
        for (var operation : snapshot.pendingOperations())
            if (operation.step().requiresContact()) usage.merge(operation.planned().intent().contextId(), snapshot.scenario().costs().cost(operation.step())
                    .consumedUntil(Duration.between(operation.stepStartedAt(), snapshot.at())), ResourceUsage::plus);
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
