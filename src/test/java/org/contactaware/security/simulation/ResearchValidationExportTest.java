package org.contactaware.security.simulation;

import org.contactaware.contacts.JsonFiles;
import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.util.List;
import static org.assertj.core.api.Assertions.*;

class ResearchValidationExportTest {
    @Test void exportsRealCompleteSelectionAndRuleWithoutSerializingInProcessCertificate() throws Exception {
        var scenario=new RecedingHorizonControllerTest().keyed(2,List.of());
        var observation=PlanningObservation.from(new SimulationEngine().start(scenario),1);
        var planner=new ExactHorizonPlanner(new SearchLimits(5000,Duration.ofSeconds(1)));
        var selected=planner.selectUnrestricted(observation,"export regression");
        assertThat(selected.search().status()).isEqualTo(HorizonPlanResult.Status.COMPLETE);
        assertThat(selected.certificate()).isNotNull();
        var complete=new PlanningLatencyController.Decision(observation,selected,12,Duration.ofMillis(50),
                observation.at().plusMillis(50),false,"REVALIDATED_FIRST_ACTION");
        var fallback=new PlanningLatencyController.Decision(observation,PolicySelection.rule(selected.action(),"fallback"),12,
                Duration.ofMillis(500),observation.at().plusMillis(500),true,"DEADLINE_MISS_WAIT");
        var serialized=JsonFiles.JSON.writeValueAsString(ResearchValidationMain.exportLatencyDecisions(List.of(complete,fallback)));
        var json=JsonFiles.JSON.readTree(serialized);
        assertThat(json.get(0).path("selection").path("search").path("status").asText()).isEqualTo("COMPLETE");
        assertThat(json.get(0).path("selection").has("certificate")).isFalse();
        assertThat(json.get(1).path("selection").path("search").isNull()).isTrue();
        assertThat(json.get(1).path("deadlineMiss").asBoolean()).isTrue();
        assertThat(selected.certificate()).isNotNull(); // Export does not erase the live capability.
    }
}
