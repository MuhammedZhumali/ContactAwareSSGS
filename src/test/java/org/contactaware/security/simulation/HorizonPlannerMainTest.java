package org.contactaware.security.simulation;

import org.contactaware.contacts.JsonFiles;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
import static org.contactaware.security.simulation.HorizonWitness.*;

class HorizonPlannerMainTest {
    @TempDir Path directory;

    @Test void originalWitnessStartsAfterCommonWarmupBeforeAnyContactDecision() {
        var nextX = HorizonPlannerMain.observation(Sequence.X, 1);
        var nextY = HorizonPlannerMain.observation(Sequence.Y, 1);
        var multiX = HorizonPlannerMain.observation(Sequence.X, 2);
        assertThat(nextX).isEqualTo(nextY);
        assertThat(nextX.ongoingOperations()).isEmpty();
        assertThat(nextX.inFlightFrame()).isNull();
        assertThat(nextX.at()).isEqualTo(at(0));
        assertThat(nextX.createdKeys()).isEqualTo(2);
        assertThat(nextX.accruedCosts().totalCpu()).isEqualTo(Duration.ofMillis(200));
        assertThat(nextX.issuedOperationIds()).containsExactly("warmup");
        assertThat(nextX.evaluationEnd()).isEqualTo(at(50));
        assertThat(multiX.evaluationEnd()).isEqualTo(at(85));
        assertThat(multiX.visibleContacts()).extracting(c -> c.window().id()).containsExactly("C1", "C2", "C3");
        assertThat(new SecurityActionGenerator().candidates(multiX)).anyMatch(a -> a.kind() == PlanningAction.Kind.INSTALL)
                .anyMatch(a -> a.kind() == PlanningAction.Kind.OPEN);
    }

    @Test void boundedExperimentExportsHonestIncompleteResultsAndNeverFabricatesTotals() throws Exception {
        var output = directory.resolve("experiment");
        HorizonPlannerMain.main(new String[]{output.toString(), "1", "0"});
        for (var name : List.of("X-NEXT", "X-MULTI", "Y-NEXT", "Y-MULTI")) {
            var json = JsonFiles.JSON.readTree(output.resolve(name + ".json").toFile());
            assertThat(json.path("result").path("status").asText()).isEqualTo("INCOMPLETE");
            assertThat(json.path("result").path("exploredNodes").asLong()).isEqualTo(1);
            assertThat(json.path("result").path("score").isNull()).isTrue();
            assertThat(json.path("cpuIncludingWarmup").isNull()).isTrue();
            assertThat(json.path("bytesIncludingWarmup").isNull()).isTrue();
            assertThat(json.path("keysIncludingWarmup").isNull()).isTrue();
        }
        assertThat(output.resolve("summary.json")).exists();
        assertThatThrownBy(() -> HorizonPlannerMain.main(new String[]{output.toString(), "1", "0"}))
                .isInstanceOf(java.io.IOException.class);
    }
}
