package org.contactaware.security.simulation;

import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.contactaware.security.SecurityAction.*;
import static org.contactaware.security.simulation.SecurityCostProfile.*;

class SecurityCostProfileTest {
    final SecurityCostProfile profile = syntheticWitness();

    @Test void serialPhasesDistinguishCpuRadioWaitingAndElapsedTime() {
        var cost = profile.cost(INSTALL).total();
        assertThat(cost).isEqualTo(new ResourceUsage(Duration.ofSeconds(4), Duration.ofMillis(200),
                Duration.ofMillis(100), Duration.ofSeconds(2), 600, 200));
        assertThat(profile.provenance()).isEqualTo(Provenance.SYNTHETIC_ASSUMPTION);
    }

    @Test void interruptedInstallationChargesExecutedPhasesNotProratedFullOperation() {
        assertThat(profile.cost(INSTALL).consumedUntil(Duration.ofSeconds(2))).isEqualTo(
                new ResourceUsage(Duration.ofSeconds(2), Duration.ofMillis(200), Duration.ofMillis(100),
                        Duration.ofMillis(1700), 600, 80));
        assertThat(profile.cost(INSTALL).consumedUntil(Duration.ofMillis(50))).isEqualTo(
                new ResourceUsage(Duration.ofMillis(50), Duration.ZERO, Duration.ofMillis(50), Duration.ZERO, 0, 0));
        var waiting = profile.cost(INSTALL).consumedUntil(Duration.ofSeconds(3));
        assertThat(waiting.managementBytes()).isEqualTo(800);
        assertThat(waiting.radioOccupancy()).isEqualTo(Duration.ofSeconds(2));
    }

    @Test void compositeChargesOnlyItsMissingStepsAndNeverAddsAnExtraRotationFee() {
        var ready = profile.costOf(List.of(INSTALL, SWITCH, OPEN));
        assertThat(ready.elapsed()).isEqualTo(Duration.ofSeconds(6));
        assertThat(ready.totalCpu()).isEqualTo(Duration.ofMillis(510));
        assertThat(ready.managementBytes()).isEqualTo(1080);
        assertThat(profile.costOf(List.of(SWITCH, OPEN)).elapsed()).isEqualTo(Duration.ofSeconds(2));
        assertThat(profile.costOf(List.of(PREPARE, INSTALL, SWITCH, OPEN)).elapsed()).isEqualTo(Duration.ofSeconds(8));
        assertThatThrownBy(() -> profile.cost(ROTATE)).hasMessageContaining("Resolve");
    }

    @Test void invalidPhasesAndUnsupportedNonzeroLocalCleanupAreRejected() {
        assertThatThrownBy(() -> new Phase(Resource.WAIT, Duration.ZERO, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Phase(Resource.GROUND_CPU, Duration.ofSeconds(1), 5)).isInstanceOf(IllegalArgumentException.class);
        var costs = new EnumMap<>(profile.operations());
        costs.put(STOP, profile.cost(PREPARE));
        assertThatThrownBy(() -> new SecurityCostProfile("bad", Provenance.SYNTHETIC_ASSUMPTION, "test", costs))
                .hasMessageContaining("instantaneous");
        assertThatThrownBy(() -> profile.operations().clear()).isInstanceOf(UnsupportedOperationException.class);
    }
}
