package org.contactaware.security.simulation;

import com.fasterxml.jackson.databind.JsonNode;
import org.contactaware.contacts.JsonFiles;
import org.junit.jupiter.api.Test;
import java.nio.file.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

/** Original numeric witness, through the real CLI/controller, not a prescribed operation plan. */
class ControlledWitnessIntegrationTest {
    @Test void originalXYReplanToExperimentEndAndMatchTheIndependentWitnessTotals() throws Exception {
        var output = Files.createTempDirectory(Path.of("target"), "controlled-witness-");
        // Deterministic node limit, no machine-speed deadline. The CLI still reports search time.
        ControlledWitnessMain.main(new String[]{output.toString(), "2000000", "0"});
        var runs = new TreeMap<String, JsonNode>();
        var expected = Map.of("X-NEXT", new long[]{2, 38, 320, 160},
                "X-MULTI", new long[]{3, 37, 830, 1240},
                "Y-NEXT", new long[]{3, 42, 380, 240},
                "Y-MULTI", new long[]{3, 42, 380, 240});
        for (var entry : expected.entrySet()) {
            var document = JsonFiles.JSON.readTree(output.resolve(entry.getKey() + ".json").toFile());
            var result = document.get("result");
            runs.put(entry.getKey(), result);
            assertThat(result.get("status").asText()).isEqualTo("FINISHED");
            assertThat(result.get("snapshot").get("at").asText()).isEqualTo(HorizonWitness.at(85).toString());
            var metrics = result.get("simulation").get("metrics");
            var values = entry.getValue();
            assertThat(metrics.get("servedContacts").asLong()).isEqualTo(values[0]);
            assertThat(metrics.get("usefulSecureTime").decimalValue()).isEqualByComparingTo(Long.toString(values[1]));
            var costs = metrics.get("controlCosts");
            assertThat(costs.get("spacecraftCpu").decimalValue().add(costs.get("groundCpu").decimalValue())
                    .movePointRight(3)).isEqualByComparingTo(Long.toString(values[2]));
            assertThat(costs.get("uplinkBytes").asLong() + costs.get("downlinkBytes").asLong()).isEqualTo(values[3]);
            assertThat(metrics.get("createdKeys").asLong()).isEqualTo(2);
            assertThat(metrics.get("operationFailures").isEmpty()).isTrue();
            for (var decision : result.get("decisions")) {
                assertThat(decision.get("search").get("status").asText()).isEqualTo("COMPLETE");
                assertThat(decision.get("executedAction")).isEqualTo(decision.get("search").get("firstAction"));
            }
        }
        // Next has the same past and forecast until C2 opens at 30; then it may learn C3.
        var prefixX = prefix(runs.get("X-NEXT"));
        var prefixY = prefix(runs.get("Y-NEXT"));
        assertThat(prefixX).containsExactlyElementsOf(prefixY);
        assertThat(runs.get("X-NEXT").get("decisions")).anyMatch(d ->
                d.get("observation").get("at").asText().equals(HorizonWitness.at(30).toString())
                        && d.get("observation").get("visibleContacts").size() == 2);
        assertThat(runs.get("X-MULTI").get("decisions")).anyMatch(d ->
                "INSTALL".equals(d.path("executedAction").path("kind").asText())
                        && "C1".equals(d.path("executedAction").path("contactId").asText()));
        assertThat(runs.get("Y-MULTI").get("decisions")).noneMatch(d ->
                "INSTALL".equals(d.path("executedAction").path("kind").asText()));
        // CLI must refuse accidental replacement of an existing experiment.
        assertThatThrownBy(() -> ControlledWitnessMain.main(new String[]{output.toString(), "1", "0"}))
                .isInstanceOf(java.io.IOException.class);
        System.out.println("Verified controlled X/Y export: " + output.toAbsolutePath());
    }

    private List<JsonNode> prefix(JsonNode run) {
        var prefix = new ArrayList<JsonNode>();
        for (var decision : run.get("decisions")) {
            if (!java.time.Instant.parse(decision.get("observation").get("at").asText()).isBefore(HorizonWitness.at(30))) break;
            var factual = JsonFiles.JSON.createObjectNode();
            factual.set("observation", decision.get("observation"));
            factual.set("action", decision.get("executedAction"));
            prefix.add(factual);
        }
        return prefix;
    }
}
