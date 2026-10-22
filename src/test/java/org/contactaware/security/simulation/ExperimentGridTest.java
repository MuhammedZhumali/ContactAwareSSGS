package org.contactaware.security.simulation;

import org.contactaware.contacts.JsonFiles;
import org.contactaware.contacts.Model.ContactWindow;
import org.contactaware.contacts.schedule.ScheduleFiles;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.*;
import java.time.Duration;
import java.util.*;

import static org.assertj.core.api.Assertions.*;
import static org.contactaware.security.simulation.HorizonWitness.*;
import static org.contactaware.security.simulation.PolicyExperimentFixtures.TrafficWorkload.*;

class ExperimentGridTest {
    @TempDir Path temp;

    @Test void arbitraryPrefixesAndRegisteredWorkloadsAreExplicitWhileOldDefaultsRemainCompatible() throws Exception {
        for (int count : List.of(1, 3, 6, 12, 24, 46)) {
            var parsed = FourPolicyExperimentMain.parse(new String[]{"--scope", "schedule", "--contact-count", Integer.toString(count),
                    "--traffic", "moderate", "--max-nodes", "100000", "--max-seconds", "10"});
            assertThat(parsed.contactCount()).isEqualTo(count);
            assertThat(parsed.traffic()).isEqualTo(MODERATE);
            assertThat(parsed.limits()).isEqualTo(new SearchLimits(100000, Duration.ofSeconds(10)));
        }
        assertThat(FourPolicyExperimentMain.parse(new String[]{"--scope", "schedule3"}).contactCount()).isEqualTo(3);
        assertThat(FourPolicyExperimentMain.parse(new String[]{"--scope", "schedule46"}).contactCount()).isEqualTo(46);
        assertThat(FourPolicyExperimentMain.parse(new String[]{"--scope", "schedule3"}).traffic()).isEqualTo(LIGHT);
        assertThat(FourPolicyExperimentMain.parse(new String[0]).traffic()).isNull(); // X/Y has its own fixed witness traffic.
        var original = PolicyExperimentFixtures.selectedSchedule(PolicyExperimentFixtures.DEFAULT_SCHEDULE, 3, Duration.ofSeconds(7200));
        assertThat(original.id()).isEqualTo("schedule3-cp7200-cost1");
        assertThat(original.scenario().contacts()).allSatisfy(c -> assertThat(c.payload()).isEqualTo(PolicyExperimentFixtures.FINITE_SCHEDULE_TRAFFIC));
    }

    @Test void cliRejectsUndeclaredOrAmbiguousFactors() {
        for (var args : List.of(
                new String[]{"--scope", "schedule"},
                new String[]{"--scope", "schedule", "--contact-count", "0"},
                new String[]{"--scope", "schedule", "--contact-count", "47"},
                new String[]{"--scope", "schedule3", "--contact-count", "6"},
                new String[]{"--scope", "schedule46", "--contact-count", "3"},
                new String[]{"--scope", "xy", "--traffic", "light"},
                new String[]{"--scope", "xy", "--contact-count", "3"},
                new String[]{"--scope", "schedule3", "--traffic", "adaptive"}))
            assertThatThrownBy(() -> FourPolicyExperimentMain.parse(args)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void workloadsSeparateRequiredServiceFromOfferedSaturationAndPreserveFrameGranularity() {
        var shortWindow = window("short", Duration.ofSeconds(58).plusNanos(999));
        var light = PolicyExperimentFixtures.traffic(LIGHT, shortWindow);
        var moderate = PolicyExperimentFixtures.traffic(MODERATE, shortWindow);
        var saturated = PolicyExperimentFixtures.traffic(SATURATED, shortWindow);
        assertThat(light.offeredUplinkBytes()).isEqualTo(light.minimumUplinkBytes()).isEqualTo(1000);
        assertThat(moderate.offeredUplinkBytes()).isEqualTo(moderate.minimumUplinkBytes()).isEqualTo(3000);
        assertThat(moderate.offeredDownlinkBytes()).isEqualTo(moderate.minimumDownlinkBytes()).isEqualTo(3000);
        assertThat(saturated.minimumUplinkBytes()).isEqualTo(saturated.minimumDownlinkBytes()).isEqualTo(1000);
        assertThat(saturated.offeredUplinkBytes()).isEqualTo(saturated.offeredDownlinkBytes()).isEqualTo(5000);
        for (var p : List.of(light, moderate, saturated)) assertThat(p.frameDuration()).isEqualTo(Duration.ofSeconds(10));

        var belowBoundary = PolicyExperimentFixtures.traffic(SATURATED, window("before", Duration.ofSeconds(60).minusNanos(1)));
        var atBoundary = PolicyExperimentFixtures.traffic(SATURATED, window("at", Duration.ofSeconds(60)));
        assertThat(belowBoundary.offeredUplinkBytes()).isEqualTo(5000);
        assertThat(atBoundary.offeredUplinkBytes()).isEqualTo(6000);
        assertThat(PolicyExperimentFixtures.traffic(SATURATED, window("tiny", Duration.ofNanos(1))).offeredUplinkBytes()).isEqualTo(1000);
    }

    @Test void geometryCeilingMarksModerateShortContactsAndRoundsRequiredBytesUpToFullFrames() {
        var shortWindow = window("short", Duration.ofSeconds(58));
        var exactWindow = window("exact", Duration.ofSeconds(60));
        var moderate = PolicyExperimentFixtures.payloadFeasibility(List.of(
                new SimulationContact(shortWindow, PolicyExperimentFixtures.traffic(MODERATE, shortWindow)),
                new SimulationContact(exactWindow, PolicyExperimentFixtures.traffic(MODERATE, exactWindow))));
        assertThat(moderate.payloadOnlyServiceCeiling()).isEqualTo(1);
        assertThat(moderate.perContact()).extracting(PolicyExperimentFixtures.ContactPayloadFeasibility::fitsWithoutSecurityOverhead)
                .containsExactly(false, true);
        assertThat(moderate.perContact()).allSatisfy(c -> assertThat(c.requiredPayloadTime()).isEqualTo(Duration.ofSeconds(60)));
        assertThat(moderate.interpretation()).contains("Excludes security operations", "not achievable-service certification");

        var partialMinimum = new SimulationContact.PayloadProfile(100, 1000, 2000, 1000, 1001, 1000);
        var rounded = PolicyExperimentFixtures.payloadFeasibility(List.of(
                new SimulationContact(window("rounded", Duration.ofSeconds(30).minusNanos(1)), partialMinimum)));
        assertThat(rounded.payloadOnlyServiceCeiling()).isZero();
        assertThat(rounded.perContact().getFirst().minimumUplinkFrames()).isEqualTo(2);
        assertThat(rounded.perContact().getFirst().requiredPayloadTime()).isEqualTo(Duration.ofSeconds(30));
    }

    @Test void growingPrefixesKeepOriginalContactsAndTrafficChangesDoNotAlterKeysCostsOrTiming() throws Exception {
        var path = PolicyExperimentFixtures.DEFAULT_SCHEDULE;
        var before = JsonFiles.sha256(Files.readAllBytes(path));
        var original = ScheduleFiles.readVerified(path).schedule().contacts();
        for (int count : List.of(3, 6, 12, 24, 46)) {
            var light = PolicyExperimentFixtures.selectedSchedule(path, count, Duration.ofSeconds(5400), 1, LIGHT);
            var moderate = PolicyExperimentFixtures.selectedSchedule(path, count, Duration.ofSeconds(5400), 1, MODERATE);
            var saturated = PolicyExperimentFixtures.selectedSchedule(path, count, Duration.ofSeconds(5400), 1, SATURATED);
            for (var f : List.of(light, moderate, saturated)) {
                assertThat(f.scenario().contacts()).extracting(SimulationContact::window).containsExactlyElementsOf(original.subList(0, count));
                assertThat(f.scenario().initialState()).isEqualTo(light.scenario().initialState());
                assertThat(f.scenario().costs()).isEqualTo(light.scenario().costs());
                assertThat(f.scenario().start()).isEqualTo(light.scenario().start());
                assertThat(f.scenario().end()).isEqualTo(light.scenario().end());
                assertThat(f.input().get("scheduleSha256")).isEqualTo(before);
            }
            assertThat(moderate.id()).endsWith("-moderate");
            assertThat(saturated.id()).endsWith("-saturated");
            assertThat(moderate.input().get("trafficWorkload")).isEqualTo("MODERATE");
            assertThat(moderate.input().get("payloadOnlyServiceCeiling")).isEqualTo(Long.toString(
                    PolicyExperimentFixtures.payloadFeasibility(moderate.scenario().contacts()).payloadOnlyServiceCeiling()));
        }
        assertThat(JsonFiles.sha256(Files.readAllBytes(path))).isEqualTo(before);
    }

    @Test void boundedExportIncludesFactorsGeometryCeilingActualStopReasonAndPerDecisionSearchWork() throws Exception {
        var output = temp.resolve("bounded-moderate");
        FourPolicyExperimentMain.main(new String[]{"--scope", "schedule", "--contact-count", "3", "--traffic", "moderate",
                "--policies", "next", "--max-nodes", "1", "--max-seconds", "0", "--out", output.toString()});
        var id = "schedule3-cp7200-cost1-moderate";
        var summary = JsonFiles.JSON.readTree(output.resolve("summary.json").toFile()).get(id + "-next");
        assertThat(summary.path("status").asText()).isEqualTo("SEARCH_INCOMPLETE");
        assertThat(summary.path("finalMetrics").isNull()).isTrue();
        assertThat(summary.path("partialProgress").isObject()).isTrue();
        var performance = summary.path("searchPerformance");
        assertThat(performance.path("searchDecisions").asLong()).isEqualTo(1);
        assertThat(performance.path("totalExploredNodes").asLong()).isEqualTo(summary.path("exploredNodes").asLong());
        assertThat(performance.path("maximumSearchNanos").asLong()).isEqualTo(summary.path("searchNanos").asLong());
        assertThat(performance.path("stopReasons").path("NODE_LIMIT").asLong()).isEqualTo(1);
        var decision = JsonFiles.JSON.readTree(output.resolve(id + "-next.decisions.json").toFile()).get(0);
        assertThat(decision.path("searchStopReason").asText()).isEqualTo("NODE_LIMIT");
        assertThat(decision.path("searchDiagnostics").path("stopReason").asText()).isEqualTo("NODE_LIMIT");
        assertThat(decision.path("searchDiagnostics").path("rootGeneratedByAction").isObject()).isTrue();
        assertThat(decision.path("searchDiagnostics").path("rootCandidateCount").asLong()).isPositive();
        assertThat(decision.path("visibleContacts").asInt()).isEqualTo(1);
        assertThat(decision.path("executedAction").isNull()).isTrue();
        var manifest = JsonFiles.JSON.readTree(output.resolve("manifest.json").toFile());
        assertThat(manifest.path("options").path("contactCount").asInt()).isEqualTo(3);
        assertThat(manifest.path("options").path("traffic").asText()).isEqualTo("MODERATE");
        assertThat(manifest.path("executionEnvironment").path("javaVersion").asText()).isNotBlank();
        var model = JsonFiles.JSON.readTree(output.resolve(id + ".model.json").toFile());
        assertThat(model.path("payloadFeasibility").path("contacts").asInt()).isEqualTo(3);
        assertThat(model.path("payloadFeasibility").path("perContact").size()).isEqualTo(3);
        assertThat(model.path("payloadFeasibility").path("perContact").get(0).path("requiredPayloadTime").decimalValue())
                .isEqualByComparingTo("60");
    }

    @Test void ruleExportHasNoSearchDiagnosticsAndNoInventedMeanSearchCost() throws Exception {
        var fixture = PolicyExperimentFixtures.witness(Sequence.Y, true);
        var actual = new SecurityPolicyController(new PerContactPolicy(PerContactPolicy.AdvancePreparation.JUST_IN_TIME))
                .run(fixture.checkpoint());
        var summary = FourPolicyExperimentMain.exportCase(temp, "rules", fixture, actual, 0, false);
        assertThat(summary.searchPerformance().searchDecisions()).isZero();
        assertThat(summary.searchPerformance().stopReasons()).isEmpty();
        assertThat(summary.searchPerformance().meanSearchNanos()).isNull();
        assertThat(summary.searchPerformance().meanExploredNodes()).isNull();
        var decisions = JsonFiles.JSON.readTree(temp.resolve("rules.decisions.json").toFile());
        assertThat(decisions).allSatisfy(d -> {
            assertThat(d.path("searchStatus").isNull()).isTrue();
            assertThat(d.path("searchStopReason").isNull()).isTrue();
            assertThat(d.path("searchDiagnostics").isNull()).isTrue();
        });
    }

    @Test void searchWorkAggregationUsesSearchDecisionsOnlyAndPreservesWorstDecision() {
        var observation = PlanningObservation.from(PolicyExperimentFixtures.witness(Sequence.X, true).checkpoint(), 1);
        var first = new ExactHorizonPlanner(new SearchLimits(1, Duration.ZERO)).plan(observation);
        var second = new ExactHorizonPlanner(new SearchLimits(2, Duration.ZERO)).plan(observation);
        var decisions = List.of(
                new ControlledSimulationResult.Decision(observation, first, null, observation.at(),
                        ControlledSimulationResult.DecisionSource.SEARCH, "Budgeted search one"),
                new ControlledSimulationResult.Decision(observation, null, null, observation.at(),
                        ControlledSimulationResult.DecisionSource.RULE, "No search"),
                new ControlledSimulationResult.Decision(observation, second, null, observation.at(),
                        ControlledSimulationResult.DecisionSource.SEARCH, "Budgeted search two"));
        var work = FourPolicyExperimentMain.searchPerformance(decisions);
        assertThat(work.searchDecisions()).isEqualTo(2);
        assertThat(work.totalExploredNodes()).isEqualTo(3);
        assertThat(work.maximumExploredNodes()).isEqualTo(2);
        assertThat(work.meanExploredNodes()).isEqualByComparingTo("1.5");
        assertThat(work.totalSearchNanos()).isEqualTo(first.elapsedNanos() + second.elapsedNanos());
        assertThat(work.maximumSearchNanos()).isEqualTo(Math.max(first.elapsedNanos(), second.elapsedNanos()));
        assertThat(work.stopReasons()).containsExactlyEntriesOf(Map.of("NODE_LIMIT", 2L));
        assertThat(work.totalGeneratedActions()).isEqualTo(first.diagnostics().generatedActions() + second.diagnostics().generatedActions());
    }

    private static ContactWindow window(String id, Duration length) {
        return new ContactWindow(id, "SC", "A", at(0), at(0).plus(length), null, null, List.of());
    }
}
