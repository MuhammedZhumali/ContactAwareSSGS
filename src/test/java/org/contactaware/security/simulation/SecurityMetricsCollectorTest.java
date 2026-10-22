package org.contactaware.security.simulation;

import org.contactaware.security.*;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.contactaware.security.simulation.SimulationEvent.Type.*;

class SecurityMetricsCollectorTest {
    private static final KeyPairIds OLD = new KeyPairIds("manual-old-up", "manual-old-down");
    private static final KeyPairIds FRESH = new KeyPairIds("independent-up", "independent-down");
    private static Instant at(double seconds) { return HorizonWitness.EPOCH.plusMillis(Math.round(seconds * 1000)); }

    @Test void countsRecordedHistoryFutureAndAbortedFragmentsWithoutCountingThemAsDelivered() {
        var events = new ArrayList<SimulationEvent>();
        events.add(frame(0, 1, 2, OLD, true, true));
        events.add(notice(1, 2.5, KEY_DISCLOSED));
        events.add(frame(2, 2, 3, OLD, false, true));
        events.add(frame(3, 3, 4, OLD, true, true));
        events.add(notice(4, 4.5, REVOCATION_RECEIVED_GROUND));
        events.add(frame(5, 4, 4.5, OLD, true, false));
        events.add(frame(6, 6, 7, FRESH, true, true));
        var result = new SecurityMetricsCollector().collect(scenario(true), events);
        assertThat(result.deliveredBytes()).isEqualTo(400);
        assertThat(result.emittedBytes()).isEqualTo(450);
        assertThat(result.decryptableBeforeBytes()).isEqualTo(150);
        assertThat(result.decryptableAfterBytes()).isEqualTo(200);
        assertThat(result.compromisedDeliveredBytes()).isEqualTo(300);
        assertThat(result.confidentialDeliveredBytes()).isEqualTo(100);
        assertThat(result.compromisedUseAfter()).isEqualTo(Duration.ofSeconds(2));
        assertThat(result.bytesAfterGroundNotice()).isZero();
        assertThat(result.useAfterGroundNotice()).isZero();
        assertThat(result.detectionToRecovery()).isEqualTo(Duration.ofMillis(2500));
        assertThat(result.recoveryStatus()).isEqualTo(SecurityMetricsCollector.RecoveryStatus.RECOVERED);
    }

    @Test void distinguishesUndetectedNoTargetAndCensoredInsteadOfInventingZeroRecovery() {
        var collector = new SecurityMetricsCollector();
        assertThat(collector.collect(scenario(true), List.of()).recoveryStatus())
                .isEqualTo(SecurityMetricsCollector.RecoveryStatus.NO_TARGET);
        var undetected = collector.collect(scenario(true), List.of(notice(0, 2.5, KEY_DISCLOSED)));
        assertThat(undetected.recoveryStatus()).isEqualTo(SecurityMetricsCollector.RecoveryStatus.UNDETECTED);
        assertThat(undetected.detectionToRecovery()).isNull();
        var censored = collector.collect(scenario(true), List.of(notice(0, 2.5, KEY_DISCLOSED),
                notice(1, 4.5, REVOCATION_RECEIVED_GROUND)));
        assertThat(censored.recoveryStatus()).isEqualTo(SecurityMetricsCollector.RecoveryStatus.CENSORED_NO_RECOVERY);
        assertThat(censored.detectionToRecovery()).isNull();
        assertThat(censored.censorAt()).isEqualTo(HorizonWitness.at(45));
        assertThat(collector.collect(scenario(false), List.of()).recoveryStatus())
                .isEqualTo(SecurityMetricsCollector.RecoveryStatus.NO_ATTACK);
    }

    @Test void lateNoticeCanRecoverOnAlreadyAdmittedIndependentFrameAndDuplicateFactsAreRejected() {
        var events = List.of(notice(0, 2.5, KEY_DISCLOSED), notice(1, 4.5, REVOCATION_RECEIVED_GROUND),
                frame(2, 4, 5, FRESH, true, true));
        var result = new SecurityMetricsCollector().collect(scenario(true), events);
        assertThat(result.detectionToRecovery()).isEqualTo(Duration.ofMillis(500));
        var duplicate = new ArrayList<>(events); duplicate.add(events.getLast());
        assertThatThrownBy(() -> new SecurityMetricsCollector().collect(scenario(true), duplicate))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Duplicate factual frame");
    }

    @Test void unnotifiedSpacecraftCanFinishCommittedDownlinkButCannotDeliverIt() {
        var events = List.of(notice(0, 2.5, KEY_DISCLOSED), notice(1, 4.5, REVOCATION_RECEIVED_GROUND),
                frame(2, 4, 5, OLD, false, false), notice(3, 5.4, REVOCATION_RECEIVED_SPACECRAFT));
        var result = new SecurityMetricsCollector().collect(scenario(true), events);
        assertThat(result.deliveredBytes()).isZero();
        assertThat(result.decryptableAfterBytes()).isEqualTo(100);
        assertThat(result.bytesAfterGroundNotice()).isEqualTo(50);
        assertThat(result.useAfterGroundNotice()).isEqualTo(Duration.ofMillis(500));
        assertThat(result.bytesAfterSpacecraftNotice()).isZero();
        assertThat(result.useAfterSpacecraftNotice()).isZero();
    }

    private SimulationScenario scenario(boolean attacked) {
        var s = SecurityEvaluationMain.manualScenario(60);
        return new SimulationScenario(s.name(), s.start(), s.end(), s.initialState(), s.constraints(), s.costs(),
                s.contacts(), s.plan(), attacked ? List.of(new TrafficKeyDisclosure("incident", HorizonWitness.A, at(2.5), at(4.5))) : List.of());
    }

    private SimulationEvent notice(long sequence, double time, SimulationEvent.Type type) {
        return new SimulationEvent(sequence, at(time), type, "incident", null, HorizonWitness.A, "M1", OLD,
                ResourceUsage.ZERO, Duration.ZERO, 0, 0, 0, 0, 0, null, "Hand-calculated fact");
    }

    private SimulationEvent frame(long sequence, double start, double end, KeyPairIds pair, boolean up, boolean delivered) {
        long bytes = delivered ? 100 : 0;
        var evidence = new SimulationEvent.FrameEvidence(at(start), at(end), up ? Direction.UPLINK : Direction.DOWNLINK,
                up ? pair.uplink() : pair.downlink(), 100, Math.round((end-start)*100), delivered);
        return new SimulationEvent(sequence, at(end), delivered ? PAYLOAD_TRANSFERRED : PAYLOAD_ABORTED,
                null, null, HorizonWitness.A, "M1", pair, ResourceUsage.ZERO,
                delivered ? Duration.ofSeconds(1) : Duration.ZERO, up ? bytes : 0, up ? 0 : bytes,
                up && delivered ? 1 : 0, !up && delivered ? 1 : 0, 0, null, "Hand-calculated frame", evidence);
    }
}
