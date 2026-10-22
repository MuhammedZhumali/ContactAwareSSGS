package org.contactaware.security.simulation;

import org.contactaware.security.*;
import java.math.BigInteger;
import java.time.*;
import java.util.*;
import static org.contactaware.security.simulation.SimulationEvent.Type.*;

/** Evaluation only: never feeds attacker knowledge or security scores into policy observations. */
public final class SecurityMetricsCollector {
    public enum RecoveryStatus { NO_ATTACK, NO_TARGET, UNDETECTED, RECOVERED, CENSORED_NO_RECOVERY }
    public record Metrics(String incidentId, Instant disclosureAt, KeyPairIds disclosedKeys,
                          long deliveredBytes, long emittedBytes, long decryptableBeforeBytes,
                          long decryptableAfterBytes, long compromisedDeliveredBytes, long confidentialDeliveredBytes,
                          Duration compromisedUseAfter, Instant lastCompromisedEmissionAt,
                          Instant groundNoticeAt, Instant spacecraftNoticeAt,
                          long bytesAfterGroundNotice, Duration useAfterGroundNotice,
                          long bytesAfterSpacecraftNotice, Duration useAfterSpacecraftNotice,
                          RecoveryStatus recoveryStatus, Instant firstIndependentFrameAt,
                          Duration detectionToRecovery, Duration detectionToLocalStop,
                          Duration groundToSpacecraftNotice, Instant censorAt, ResourceUsage notificationCosts) {}
    public record PairedDelta(long lostServedContacts, long lostDeliveredBytes, Duration lostUsefulTime,
                              Duration extraControlCpu, long extraManagementBytes, long extraCreatedKeys) {}

    public Metrics collect(SimulationScenario scenario, List<SimulationEvent> events) {
        if (scenario.disclosures().size() > 1)
            throw new IllegalArgumentException("This evaluation protocol specifies one disclosure per run");
        var incident = scenario.disclosures().isEmpty() ? null : scenario.disclosures().getFirst();
        var disclosure = events.stream().filter(e -> e.type() == KEY_DISCLOSED).findFirst().orElse(null);
        var keys = disclosure == null ? null : disclosure.keyIds();
        var known = keys == null ? Set.<String>of() : Set.of(keys.uplink(), keys.downlink());
        Instant ground = firstAt(events, REVOCATION_RECEIVED_GROUND);
        Instant spacecraft = firstAt(events, REVOCATION_RECEIVED_SPACECRAFT);
        long delivered = 0, emitted = 0, before = 0, after = 0, exposedDelivered = 0, afterNotice = 0, afterRemote = 0;
        Duration use = Duration.ZERO, noticeUse = Duration.ZERO, remoteUse = Duration.ZERO;
        Instant last = null, recovery = null;
        var frameIds = new HashSet<String>();
        var notification = ResourceUsage.ZERO;
        for (var event : events) {
            if (event.action() != null && event.action().name().equals("NOTIFY_REVOCATION"))
                notification = notification.plus(event.usage());
            if (event.type() != PAYLOAD_TRANSFERRED && event.type() != PAYLOAD_ABORTED) continue;
            var frame = evidence(event, scenario);
            String identity = event.contactId() + ":" + frame.from() + ":" + frame.direction();
            if (!frameIds.add(identity)) throw new IllegalArgumentException("Duplicate factual frame " + identity);
            emitted = Math.addExact(emitted, frame.emittedBytes());
            if (frame.delivered()) delivered = Math.addExact(delivered, frame.frameBytes());
            if (known.contains(frame.keyId())) {
                long prior = emittedBefore(frame, incident.at(), scenario, event.contactId());
                before = Math.addExact(before, prior);
                after = Math.addExact(after, frame.emittedBytes() - prior);
                use = use.plus(overlapAfter(frame, incident.at()));
                if (last == null || frame.until().isAfter(last)) last = frame.until();
                if (frame.delivered()) exposedDelivered = Math.addExact(exposedDelivered, frame.frameBytes());
                if (ground != null) {
                    afterNotice = Math.addExact(afterNotice, frame.emittedBytes()
                            - emittedBefore(frame, ground, scenario, event.contactId()));
                    noticeUse = noticeUse.plus(overlapAfter(frame, ground));
                }
                if (spacecraft != null) {
                    afterRemote = Math.addExact(afterRemote, frame.emittedBytes()
                            - emittedBefore(frame, spacecraft, scenario, event.contactId()));
                    remoteUse = remoteUse.plus(overlapAfter(frame, spacecraft));
                }
            } else if (ground != null && frame.delivered() && frame.until().isAfter(ground)
                    && incident.contextId().equals(event.contextId()) && recovery == null) recovery = frame.until();
        }
        var status = incident == null ? RecoveryStatus.NO_ATTACK : keys == null ? RecoveryStatus.NO_TARGET
                : ground == null ? RecoveryStatus.UNDETECTED : recovery == null
                ? RecoveryStatus.CENSORED_NO_RECOVERY : RecoveryStatus.RECOVERED;
        return new Metrics(incident == null ? null : incident.id(), incident == null ? null : incident.at(), keys,
                delivered, emitted, before, after, exposedDelivered, delivered - exposedDelivered, use, last,
                ground, spacecraft, afterNotice, noticeUse, afterRemote, remoteUse, status, recovery,
                recovery == null ? null : Duration.between(ground, recovery), ground == null ? null : Duration.ZERO,
                ground == null || spacecraft == null ? null : Duration.between(ground, spacecraft),
                status == RecoveryStatus.CENSORED_NO_RECOVERY ? scenario.end() : null, notification);
    }

    public PairedDelta compare(SimulationResult.Metrics baseline, Metrics baselineSecurity,
                               SimulationResult.Metrics attacked, Metrics attackedSecurity) {
        return new PairedDelta(baseline.servedContacts() - attacked.servedContacts(),
                baselineSecurity.deliveredBytes() - attackedSecurity.deliveredBytes(),
                baseline.usefulSecureTime().minus(attacked.usefulSecureTime()),
                attacked.controlCosts().totalCpu().minus(baseline.controlCosts().totalCpu()),
                attacked.controlCosts().managementBytes() - baseline.controlCosts().managementBytes(),
                attacked.createdKeys() - baseline.createdKeys());
    }

    private static Instant firstAt(List<SimulationEvent> events, SimulationEvent.Type type) {
        return events.stream().filter(e -> e.type() == type).map(SimulationEvent::at).findFirst().orElse(null);
    }

    static SimulationEvent.FrameEvidence evidence(SimulationEvent event, SimulationScenario scenario) {
        if (event.frameEvidence() != null) return event.frameEvidence();
        if (event.type() != PAYLOAD_TRANSFERRED) throw new IllegalArgumentException("Aborted frame lacks factual evidence");
        boolean up = event.uplinkFrames() == 1;
        if (!up && event.downlinkFrames() != 1) throw new IllegalArgumentException("Expected exactly one complete frame");
        long bytes = Math.addExact(event.uplinkPayloadBytes(), event.downlinkPayloadBytes());
        return new SimulationEvent.FrameEvidence(event.at().minus(event.usefulTime()), event.at(),
                up ? Direction.UPLINK : Direction.DOWNLINK, up ? event.keyIds().uplink() : event.keyIds().downlink(),
                bytes, bytes, true);
    }

    private static Duration overlapAfter(SimulationEvent.FrameEvidence frame, Instant boundary) {
        var start = frame.from().isAfter(boundary) ? frame.from() : boundary;
        return frame.until().isAfter(start) ? Duration.between(start, frame.until()) : Duration.ZERO;
    }

    /** Byte emission is the same exact floor integral as modeled phase costs; never round two halves separately. */
    static long emittedBefore(SimulationEvent.FrameEvidence frame, Instant boundary,
                              SimulationScenario scenario, String contactId) {
        if (!boundary.isAfter(frame.from())) return 0;
        if (!boundary.isBefore(frame.until())) return frame.emittedBytes();
        var duration = scenario.contacts().stream().filter(c -> c.window().id().equals(contactId)).findFirst()
                .orElseThrow().payload().frameDuration();
        return Math.min(frame.emittedBytes(), BigInteger.valueOf(frame.frameBytes())
                .multiply(BigInteger.valueOf(Duration.between(frame.from(), boundary).toNanos()))
                .divide(BigInteger.valueOf(duration.toNanos())).longValueExact());
    }
}
