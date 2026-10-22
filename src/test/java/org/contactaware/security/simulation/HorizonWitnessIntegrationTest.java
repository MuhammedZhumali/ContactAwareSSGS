package org.contactaware.security.simulation;

import com.fasterxml.jackson.databind.JsonNode;
import org.contactaware.contacts.JsonFiles;
import org.contactaware.security.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.contactaware.security.simulation.HorizonWitness.*;
import static org.contactaware.security.simulation.SimulationEvent.Type.*;

class HorizonWitnessIntegrationTest {
    static final Path REFERENCE = Path.of("docs/formal-cost-model-and-horizon/verification.json");

    @ParameterizedTest @CsvSource({"X,NEXT", "X,MULTI", "Y,NEXT", "Y,MULTI", "X,STAGED"})
    void executesFixedPlansAgainstIndependentEnumerationIncludingWarmup(Sequence sequence, Plan plan) throws Exception {
        var scenario = scenario(sequence, plan);
        var result = new SimulationEngine().run(scenario);
        JsonNode reference = JsonFiles.JSON.readTree(REFERENCE.toFile());
        var expectedActions = sequence == Sequence.Y ? List.of("REUSE", "REUSE", "REUSE")
                : switch (plan) {
                    case NEXT -> List.of("REUSE", "REUSE", "SKIP");
                    case MULTI -> List.of("ROTATE", "REUSE", "REUSE");
                    case STAGED -> List.of("INSTALL_REUSE", "REUSE", "ROTATE");
                };
        JsonNode expected = null;
        for (var leaf : reference.path("full").path(sequence.name())) {
            var actions = new ArrayList<String>();
            leaf.path("actions").forEach(node -> actions.add(node.asText()));
            if (actions.equals(expectedActions)) { expected = leaf; break; }
        }
        assertThat(expected).as("Reference plan exists").isNotNull();
        var metrics = result.metrics();
        assertThat(metrics.servedContacts()).isEqualTo(expected.path("N").asLong());
        assertThat(metrics.usefulSecureTime()).isEqualTo(Duration.ofSeconds(expected.path("T").asLong()));
        assertThat(metrics.controlCosts().groundCpu()).isEqualTo(Duration.ofMillis(expected.path("g").asLong()));
        assertThat(metrics.controlCosts().spacecraftCpu()).isEqualTo(Duration.ofMillis(expected.path("s").asLong()));
        assertThat(metrics.controlCosts().uplinkBytes()).isEqualTo(expected.path("up").asLong());
        assertThat(metrics.controlCosts().downlinkBytes()).isEqualTo(expected.path("down").asLong());
        assertThat(metrics.createdKeys()).isEqualTo(expected.path("newKeys").asLong());
        var warmup = result.events().stream().filter(e -> e.type() == STEP_COMPLETED && e.action() == SecurityAction.PREPARE).toList();
        assertThat(warmup).hasSize(1);
        assertThat(warmup.getFirst().at()).isEqualTo(at(reference.path("preparedWarmup").path("createdAt").asLong()));
        assertThat(warmup.getFirst().usage().groundCpu()).isEqualTo(
                Duration.ofMillis(reference.path("preparedWarmup").path("cpuGroundMs").asLong()));
        assertThat(metrics.operationFailures()).isEmpty();
        assertThat(result.uncertainContexts()).isEmpty();
        assertThat(new MetricsCollector().collect(scenario.contacts(), result.events())).isEqualTo(metrics);
    }

    @Test void reuseRetainsCountersAcrossAtoBtoAAndCountsOnlyActuallyDeliveredFrames() {
        var input = scenario(Sequence.X, Plan.MULTI);
        var result = new SimulationEngine().run(input);
        for (var key : result.finalState().keys().values()) {
            long initial = input.initialState().keys().containsKey(key.id()) ? input.initialState().key(key.id()).nextNonceCounter() : 0;
            long frames = result.events().stream().filter(e -> e.type() == PAYLOAD_TRANSFERRED && e.keyIds() != null)
                    .mapToLong(e -> key.id().equals(e.keyIds().uplink()) ? e.uplinkFrames()
                            : key.id().equals(e.keyIds().downlink()) ? e.downlinkFrames() : 0).sum();
            assertThat(key.nextNonceCounter()).isEqualTo(initial + frames);
        }
        assertThat(result.finalState().key(NEW_A.uplink()).nextNonceCounter()).isEqualTo(36);
        assertThat(result.finalState().key(NEW_A.uplink()).activatedAt()).isEqualTo(at(5));
        assertThat(result.finalState().key(NEW_A.uplink()).hardDeadline(CONSTRAINTS)).isEqualTo(at(155));
        assertThat(result.finalState().key(OLD_A.uplink()).deactivationReason()).isEqualTo(DeactivationReason.ROTATED);
        assertThat(result.finalState().key(OLD_A.uplink()).nextNonceCounter()).isEqualTo(1);
    }

    @Test void installedCandidateSurvivesOldPairExpiryAndCanActivateInC3() {
        var result = new SimulationEngine().run(scenario(Sequence.X, Plan.STAGED));
        assertThat(result.finalState().key(NEW_A.uplink()).activatedAt()).isEqualTo(at(81));
        assertThat(result.finalState().key(NEW_A.uplink()).hardDeadline(CONSTRAINTS)).isEqualTo(at(231));
        assertThat(result.finalState().key(OLD_A.uplink()).deactivationReason()).isEqualTo(DeactivationReason.EXPIRED);
        assertThat(result.metrics().usefulSecureTime()).isEqualTo(Duration.ofSeconds(37));
        assertThat(result.events().stream().filter(e -> e.type() == STEP_COMPLETED && e.action() == SecurityAction.INSTALL)).hasSize(1);
    }

    @Test void sameEngineAndShuffledInputPlanProduceByteForByteIdenticalJournalAndResults() throws Exception {
        var input = scenario(Sequence.X, Plan.MULTI);
        var reordered = new ArrayList<>(input.plan());
        Collections.reverse(reordered);
        var shuffled = new SimulationScenario(input.name(), input.start(), input.end(), input.initialState(), input.constraints(),
                input.costs(), input.contacts(), reordered);
        var engine = new SimulationEngine();
        var first = engine.run(input);
        var second = engine.run(shuffled);
        assertThat(second).isEqualTo(first);
        assertThat(JsonFiles.JSON.writeValueAsBytes(second)).isEqualTo(JsonFiles.JSON.writeValueAsBytes(first));
        assertThat(input.initialState()).isEqualTo(initialState());
        assertThatThrownBy(() -> first.events().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test void nextXHasNoInstallationAndNoSecurePayloadAfterExpiry() {
        var result = new SimulationEngine().run(scenario(Sequence.X, Plan.NEXT));
        assertThat(result.finalState().key(NEW_A.uplink()).status()).isEqualTo(KeyStatus.PRE_ACTIVE);
        assertThat(result.finalState().key(NEW_A.uplink()).installedOnBothPeers()).isFalse();
        assertThat(result.finalState().key(OLD_A.uplink()).deactivatedAt()).isEqualTo(at(70));
        assertThat(result.metrics().contacts().get(2).failureReasons()).contains("NO_SECURE_PAYLOAD");
        assertThat(result.events()).noneMatch(e -> e.type() == PAYLOAD_TRANSFERRED && e.at().isAfter(at(50)));
    }

    @ParameterizedTest @CsvSource({"X,valid_A_X", "Y,valid_A_Y"})
    void validOldAControlNeedsNoRenewal(Sequence sequence, String referenceName) throws Exception {
        var input = scenario(sequence, Plan.NEXT);
        var keys = new TreeMap<>(input.initialState().keys());
        // A different initial history, not extension of an already committed key in a running simulation.
        for (var id : List.of(OLD_A.uplink(), OLD_A.downlink())) {
            var k = keys.get(id);
            keys.put(id, new KeyState(k.id(), k.contextId(), k.direction(), k.createdAt(), at(100), at(-50),
                    k.status(), true, null, null, null, k.nextNonceCounter(), at(-40)));
        }
        var state = new SecurityState(SATELLITE, input.initialState().asOf(), input.initialState().contexts(), keys);
        var plan = new ArrayList<>(input.plan());
        if (sequence == Sequence.X) plan.add(operation("C3-reuse", SecurityAction.REUSE, 80, A, null, "C3"));
        var result = new SimulationEngine().run(new SimulationScenario("valid-A-" + sequence, input.start(), input.end(),
                state, CONSTRAINTS, input.costs(), input.contacts(), plan));
        assertControl(result, referenceName);
    }

    @Test void longThirdContactAllowsLateRenewalWithTheSamePerformance() throws Exception {
        var results = new ArrayList<SimulationResult>();
        for (var planName : List.of(Plan.NEXT, Plan.MULTI)) {
            var input = scenario(Sequence.X, planName);
            var contacts = new ArrayList<>(input.contacts());
            contacts.set(2, new SimulationContact(window("C3", A, 80, 100), contacts.get(2).payload()));
            var plan = new ArrayList<>(input.plan());
            if (planName == Plan.NEXT) plan.add(operation("C3-rotate", SecurityAction.ROTATE, 80, A, NEW_A, "C3"));
            var result = new SimulationEngine().run(new SimulationScenario("long-C3-" + planName, input.start(), at(100),
                    input.initialState(), CONSTRAINTS, input.costs(), contacts, plan));
            assertControl(result, "long_C3_X");
            results.add(result);
        }
        assertThat(results.get(0).metrics().controlCosts()).isEqualTo(results.get(1).metrics().controlCosts());
        assertThat(results.get(0).metrics().usefulSecureTime()).isEqualTo(results.get(1).metrics().usefulSecureTime());
    }

    private static void assertControl(SimulationResult result, String referenceName) throws Exception {
        var expected = JsonFiles.JSON.readTree(REFERENCE.toFile()).path("controls").path(referenceName).get(0);
        var m = result.metrics();
        assertThat(m.servedContacts()).isEqualTo(expected.path("N").asLong());
        assertThat(m.usefulSecureTime()).isEqualTo(Duration.ofSeconds(expected.path("T").asLong()));
        assertThat(m.controlCosts().groundCpu()).isEqualTo(Duration.ofMillis(expected.path("g").asLong()));
        assertThat(m.controlCosts().spacecraftCpu()).isEqualTo(Duration.ofMillis(expected.path("s").asLong()));
        assertThat(m.controlCosts().managementBytes()).isEqualTo(expected.path("up").asLong() + expected.path("down").asLong());
    }
}
