package org.contactaware.security.simulation;

import com.fasterxml.jackson.databind.JsonNode;
import org.contactaware.contacts.JsonFiles;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;

/** Execute factual decisions, never search again for their historical alternatives. */
public final class RecordedDecisionReplay {
    private RecordedDecisionReplay() {}
    public record Replayed(ExecutionSnapshot snapshot, PlanningObservation observation, int replayedDecisions,
                           int checkedEvents, String sourceCase, int decisionIndex) {}

    public static Replayed before(Path raw, String name, int index) throws IOException {
        JsonNode summary = JsonFiles.JSON.readTree(raw.resolve("summary.json").toFile()).required(name);
        var model = JsonFiles.JSON.readTree(raw.resolve(summary.required("modelFile").asText()).toFile());
        var scenario = ExecutionCheckpointFiles.convert(model.required("scenario"), SimulationScenario.class);
        var policy = SecurityEvaluationSe2Main.policy(summary.required("policy").asText(),
                ExecutionCheckpointFiles.convert(model.required("fixedPeriod"), java.time.Duration.class),
                DecisionTimeModel.valueOf(summary.required("timingModel").asText()));
        var saved = JsonFiles.JSON.readTree(raw.resolve(summary.required("decisionsFile").asText()).toFile());
        if (index < 0 || index >= saved.size()) throw new IllegalArgumentException("Decision index outside recorded sequence");
        var engine = new SimulationEngine();
        var current = engine.controlledSnapshot(engine.start(scenario));
        PlanningObservation observation = null;
        for (int i = 0; i <= index; i++) {
            var row = saved.get(i);
            observation = PlanningObservation.from(current, policy.configuration().horizon());
            observation = observation.withAdditionalWakeUps(policy.additionalWakeUps(observation));
            if (!current.at().equals(Instant.parse(row.required("at").asText()))
                    || !observation.evaluationEnd().equals(Instant.parse(row.required("evaluationEnd").asText()))
                    || observation.visibleContacts().size() != row.required("visibleContacts").asInt())
                throw new IllegalStateException("Recorded observation boundary mismatch at " + i);
            if (i == index) break;
            var candidates = new SecurityActionGenerator().candidates(observation);
            var response = IncidentResponse.next(current, observation, candidates);
            if (response != null && response.notice() != null) {
                if (!row.path("executedAction").isNull() || !"RULE".equals(row.path("source").asText()))
                    throw new IllegalStateException("Recorded notification mismatch");
                current = engine.controlledStart(current, response.notice());
            } else {
                if ("INCOMPLETE".equals(row.path("searchStatus").asText()))
                    throw new IllegalStateException("Cannot replay past an unfinished search");
                var action = row.path("executedAction").isNull() ? null
                        : ExecutionCheckpointFiles.convert(row.required("executedAction"), PlanningAction.class);
                if (action != null && !candidates.contains(action)) throw new IllegalStateException("Recorded action no longer admissible");
                if (response != null && !Objects.equals(action, response.action()))
                    throw new IllegalStateException("Common incident response differs");
                current = SecurityPolicyController.executeAction(current, observation, action, OperationOutcomes.nominal());
            }
            if (!current.at().equals(Instant.parse(row.required("resumedAt").asText())))
                throw new IllegalStateException("Recorded execution boundary mismatch at " + i);
        }
        var events = JsonFiles.JSON.readTree(raw.resolve(summary.required("eventsFile").asText()).toFile());
        var actualPrefix = JsonFiles.JSON.readTree(JsonFiles.JSON.writeValueAsBytes(current.journal()));
        if (actualPrefix.size() > events.size()) throw new IllegalStateException("Replay produced extra events");
        for (int i = 0; i < actualPrefix.size(); i++)
            if (!actualPrefix.get(i).equals(events.get(i))) throw new IllegalStateException("Factual journal mismatch at event " + i);
        return new Replayed(current, observation, index, actualPrefix.size(), name, index);
    }
}
