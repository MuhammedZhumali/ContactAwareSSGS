package org.contactaware.security.simulation;

import org.contactaware.contacts.JsonFiles;
import org.contactaware.contacts.schedule.ScheduleFiles;
import org.contactaware.security.SecurityAction;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.*;
import java.time.Duration;
import java.util.*;

import static org.assertj.core.api.Assertions.*;
import static org.contactaware.security.simulation.HorizonWitness.*;

class FourPolicyExperimentTest {
    @TempDir Path temp;

    @Test void preparedAndAutonomousWitnessesHaveTheSameStartingStateAndAccountForOnlyActualWarmup() {
        for (var sequence : Sequence.values()) {
            var prepared = PolicyExperimentFixtures.witness(sequence, true);
            var autonomous = PolicyExperimentFixtures.witness(sequence, false);
            assertThat(prepared.scenario().start()).isEqualTo(at(-2)).isEqualTo(autonomous.scenario().start());
            assertThat(prepared.scenario().end()).isEqualTo(autonomous.scenario().end());
            assertThat(prepared.scenario().contacts()).isEqualTo(autonomous.scenario().contacts());
            assertThat(prepared.scenario().initialState()).isEqualTo(autonomous.scenario().initialState());
            assertThat(prepared.scenario().constraints()).isEqualTo(autonomous.scenario().constraints());
            assertThat(prepared.scenario().costs()).isEqualTo(autonomous.scenario().costs());
            assertThat(prepared.scenario().plan()).extracting(p -> p.intent().action()).containsExactly(SecurityAction.PREPARE);
            assertThat(autonomous.scenario().plan()).isEmpty();
            assertThat(prepared.checkpoint().at()).isEqualTo(at(0));
            assertThat(prepared.checkpoint().accruedCosts().totalCpu()).isEqualTo(Duration.ofMillis(200));
            assertThat(prepared.checkpoint().createdKeys()).isEqualTo(2);
            assertThat(prepared.checkpoint().pendingOperations()).isEmpty();
            assertThat(autonomous.checkpoint().at()).isEqualTo(at(-2));
            assertThat(autonomous.checkpoint().accruedCosts()).isEqualTo(ResourceUsage.ZERO);
            assertThat(autonomous.checkpoint().createdKeys()).isZero();
            assertThat(autonomous.checkpoint().securityState().keys()).doesNotContainKeys(NEW_A.uplink(), NEW_A.downlink());
        }
    }

    @Test void scheduleFixturesPreserveVerifiedOriginalWindowsAndKeepTrafficAssumptionsSeparate() throws Exception {
        var path = PolicyExperimentFixtures.DEFAULT_SCHEDULE;
        var before = JsonFiles.sha256(Files.readAllBytes(path));
        var verified = ScheduleFiles.readVerified(path).schedule();
        var shortRun = PolicyExperimentFixtures.selectedSchedule(path, 3, Duration.ofSeconds(5400));
        var fullRun = PolicyExperimentFixtures.selectedSchedule(path, 46, Duration.ofSeconds(7200));
        assertThat(shortRun.scenario().contacts()).extracting(SimulationContact::window)
                .containsExactlyElementsOf(verified.contacts().subList(0, 3));
        assertThat(fullRun.scenario().contacts()).extracting(SimulationContact::window)
                .containsExactlyElementsOf(verified.contacts());
        assertThat(fullRun.scenario().initialState().contexts()).hasSize(17);
        assertThat(shortRun.scenario().initialState().contexts()).hasSize(2);
        assertThat(fullRun.scenario().initialState().keys()).isEmpty();
        assertThat(fullRun.scenario().plan()).isEmpty();
        assertThat(shortRun.scenario().start()).isEqualTo(verified.contacts().getFirst().start().minusSeconds(2));
        assertThat(shortRun.scenario().end()).isEqualTo(verified.contacts().get(2).end());
        assertThat(fullRun.scenario().constraints().maxMaterialAge()).isEqualTo(Duration.ofDays(2));
        assertThat(fullRun.scenario().constraints().maxReuseGap()).isEqualTo(Duration.ofDays(1));
        assertThat(shortRun.scenario().constraints().cryptoperiod()).isEqualTo(Duration.ofSeconds(5400));
        assertThat(fullRun.scenario().constraints().cryptoperiod()).isEqualTo(Duration.ofSeconds(7200));
        assertThat(fullRun.scenario().contacts()).allSatisfy(c -> {
            assertThat(c.payload().frameDuration()).isEqualTo(Duration.ofSeconds(10));
            assertThat(c.payload().minimumUplinkBytes()).isEqualTo(c.payload().offeredUplinkBytes()).isEqualTo(1000);
            assertThat(c.payload().minimumDownlinkBytes()).isEqualTo(c.payload().offeredDownlinkBytes()).isEqualTo(1000);
            assertThat(c.window().linkRateBps()).isNull();
            assertThat(c.window().expectedDataBytes()).isNull();
        });
        assertThat(fullRun.input().get("scheduleSha256")).isEqualTo(before);
        assertThat(fullRun.input().get("traffic")).contains("demand-capped", "SYNTHETIC_ASSUMPTION");
        assertThat(JsonFiles.sha256(Files.readAllBytes(path))).isEqualTo(before);
    }

    @Test void changingCryptoperiodDoesNotChangeScheduleTrafficOrInitialMaterial() throws Exception {
        var a = PolicyExperimentFixtures.selectedSchedule(PolicyExperimentFixtures.DEFAULT_SCHEDULE, 3, Duration.ofSeconds(5400));
        var b = PolicyExperimentFixtures.selectedSchedule(PolicyExperimentFixtures.DEFAULT_SCHEDULE, 3, Duration.ofSeconds(7200));
        assertThat(a.scenario().contacts()).isEqualTo(b.scenario().contacts());
        assertThat(a.scenario().initialState()).isEqualTo(b.scenario().initialState());
        assertThat(a.scenario().costs()).isEqualTo(b.scenario().costs());
        assertThat(a.scenario().start()).isEqualTo(b.scenario().start());
        assertThat(a.scenario().end()).isEqualTo(b.scenario().end());
        assertThat(a.scenario().contacts().getFirst().window().groundStationId())
                .isEqualTo(a.scenario().contacts().getLast().window().groundStationId());
    }

    @Test void scheduleCostSensitivityScalesSecurityDurationsAndBytesWithoutChangingGeometryOrTraffic() throws Exception {
        var original = PolicyExperimentFixtures.selectedSchedule(PolicyExperimentFixtures.DEFAULT_SCHEDULE, 3, Duration.ofSeconds(7200));
        var scaled = PolicyExperimentFixtures.selectedSchedule(PolicyExperimentFixtures.DEFAULT_SCHEDULE, 3, Duration.ofSeconds(7200), 2);
        assertThat(scaled.scenario().contacts()).isEqualTo(original.scenario().contacts());
        assertThat(scaled.scenario().start()).isEqualTo(original.scenario().start().minusSeconds(2));
        assertThat(scaled.scenario().costs().provenance()).isEqualTo(SecurityCostProfile.Provenance.SYNTHETIC_ASSUMPTION);
        assertThat(scaled.input().get("costScale")).isEqualTo("2");
        for (var entry : original.scenario().costs().operations().entrySet()) {
            var before = entry.getValue();
            var after = scaled.scenario().costs().cost(entry.getKey());
            assertThat(after.duration()).isEqualTo(before.duration().multipliedBy(2));
            assertThat(after.total().totalCpu()).isEqualTo(before.total().totalCpu().multipliedBy(2));
            assertThat(after.total().managementBytes()).isEqualTo(Math.multiplyExact(before.total().managementBytes(), 2));
            for (int i = 0; i < before.phases().size(); i++) {
                assertThat(after.phases().get(i).resource()).isEqualTo(before.phases().get(i).resource());
                assertThat(after.phases().get(i).duration()).isEqualTo(before.phases().get(i).duration().multipliedBy(2));
                assertThat(after.phases().get(i).bytes()).isEqualTo(before.phases().get(i).bytes() * 2);
            }
        }
        assertThat(scaled.scenario().costs().cost(SecurityAction.STOP).duration()).isZero();
        assertThat(scaled.scenario().costs().cost(SecurityAction.EXPIRE_CONTEXT).duration()).isZero();
        assertThatThrownBy(() -> PolicyExperimentFixtures.scaledCosts(0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> FourPolicyExperimentMain.parse(new String[]{"--scope", "xy", "--cost-scale", "2"}))
                .hasMessageContaining("schedule");
        assertThatThrownBy(() -> FourPolicyExperimentMain.parse(new String[]{"--scope", "schedule3", "--cost-scale", "2147483648"}))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test void cliParsesDeterministicBudgetsExactDurationsAndSubsetSelection() {
        var defaults = FourPolicyExperimentMain.parse(new String[0]);
        assertThat(defaults.scope()).isEqualTo("xy");
        assertThat(defaults.mode()).isEqualTo("both");
        assertThat(defaults.policies()).containsExactly("fixed", "percontact", "next", "multi");
        var options = FourPolicyExperimentMain.parse(new String[]{"--scope", "schedule3", "--policies", "next,multi",
                "--max-nodes", "1", "--max-seconds", "0", "--fixed-period-seconds", "60.000000001",
                "--cryptoperiod-seconds", "5400", "--per-contact", "contact-only", "--multi-horizon", "3", "--cost-scale", "2"});
        assertThat(options.fixedPeriod()).isEqualTo(Duration.ofSeconds(60).plusNanos(1));
        assertThat(options.limits()).isEqualTo(new SearchLimits(1, Duration.ZERO));
        assertThat(options.cryptoperiod()).isEqualTo(Duration.ofSeconds(5400));
        assertThat(options.multiHorizon()).isEqualTo(3);
        assertThat(options.costScale()).isEqualTo(2);
        assertThat(options.perContactPreparation()).isEqualTo(PerContactPolicy.AdvancePreparation.CONTACT_ONLY);
        assertThatThrownBy(() -> FourPolicyExperimentMain.parse(new String[]{"--policies", "next,next"})).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> FourPolicyExperimentMain.parse(new String[]{"--horizon", "1"})).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> FourPolicyExperimentMain.parse(new String[]{"--unknown", "value"})).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> FourPolicyExperimentMain.parse(new String[]{"--trace", "maybe"})).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> FourPolicyExperimentMain.parse(new String[]{"--scope", "xy", "--cryptoperiod-seconds", "5400"}))
                .hasMessageContaining("only to schedule");
        assertThatThrownBy(() -> FourPolicyExperimentMain.parse(new String[]{"--scope", "schedule3", "--mode", "prepared"}))
                .hasMessageContaining("autonomous");
    }

    @Test void boundedCliExportsPartialAccountingAndNeverFabricatesFinalMetrics() throws Exception {
        var output = temp.resolve("bounded");
        var args = new String[]{"--out", output.toString(), "--scope", "xy", "--mode", "prepared",
                "--policies", "next", "--max-nodes", "1", "--max-seconds", "0"};
        FourPolicyExperimentMain.main(args);
        var summaries = JsonFiles.JSON.readTree(output.resolve("summary.json").toFile());
        assertThat(summaries.size()).isEqualTo(2);
        for (var sequence : Sequence.values()) {
            var name = sequence + "-prepared-next";
            var summary = summaries.get(name);
            assertThat(summary.path("status").asText()).isEqualTo("SEARCH_INCOMPLETE");
            assertThat(summary.path("finalMetrics").isNull()).isTrue();
            assertThat(summary.path("partialProgress").path("createdKeysSoFar").asLong()).isEqualTo(2);
            assertThat(summary.path("partialProgress").path("accruedCosts").path("groundCpu").decimalValue()).isEqualByComparingTo("0.2");
            assertThat(summary.path("searchStatuses").path("INCOMPLETE").asLong()).isEqualTo(1);
            var events = Arrays.asList(JsonFiles.JSON.readValue(output.resolve(name + ".events.json").toFile(), SimulationEvent[].class));
            assertThat(events).noneMatch(e -> e.type() == SimulationEvent.Type.SIMULATION_ENDED);
            assertThat(events).filteredOn(e -> e.type() == SimulationEvent.Type.ACTION_STARTED)
                    .extracting(SimulationEvent::action).containsExactly(SecurityAction.PREPARE);
            assertThat(Files.exists(output.resolve(name + ".full-trace.json"))).isFalse();
        }
        assertThat(Files.exists(output.resolve("manifest.json"))).isTrue();
        assertThatThrownBy(() -> FourPolicyExperimentMain.main(args)).hasMessageContaining("empty");
    }

    @Test void partialExportPreservesRunningStepNeededToAuditAccruedCosts() throws Exception {
        var fixture = PolicyExperimentFixtures.witness(Sequence.X, true);
        var engine = new SimulationEngine();
        var checkpoint = engine.advance(engine.start(fixture.scenario()), at(-2).plusMillis(100));
        var result = new ControlledSimulationResult(HorizonPolicy.next(), ControlledSimulationResult.Status.SEARCH_INCOMPLETE,
                checkpoint, null, List.of());
        var summary = FourPolicyExperimentMain.exportCase(temp, "pending", fixture, result, 0, false);
        assertThat(summary.finalMetrics()).isNull();
        assertThat(summary.partialProgress().recordedCosts()).isEqualTo(ResourceUsage.ZERO);
        assertThat(summary.partialProgress().accruedCosts().totalCpu()).isEqualTo(Duration.ofMillis(100));
        var actual = JsonFiles.JSON.readTree(temp.resolve("pending.actual.json").toFile());
        assertThat(actual.path("pendingOperations").size()).isEqualTo(1);
        assertThat(actual.path("pendingOperations").get(0).path("stepStartedAt").asText()).isEqualTo(at(-2).toString());
        var events = Arrays.asList(JsonFiles.JSON.readValue(temp.resolve("pending.events.json").toFile(), SimulationEvent[].class));
        assertThat(events).noneMatch(e -> e.type() == SimulationEvent.Type.STEP_COMPLETED
                || e.type() == SimulationEvent.Type.SIMULATION_ENDED);
    }

    @Test void completeRuleRunExportsRebuildableJournalWithoutASearchCompletenessClaim() throws Exception {
        var fixture = PolicyExperimentFixtures.witness(Sequence.Y, true);
        var controller = new SecurityPolicyController(new PerContactPolicy(PerContactPolicy.AdvancePreparation.JUST_IN_TIME));
        var result = controller.run(fixture.checkpoint());
        assertThat(result.status()).isEqualTo(ControlledSimulationResult.Status.FINISHED);
        var summary = FourPolicyExperimentMain.exportCase(temp, "rules", fixture, result, 123, false);
        assertThat(summary.finalMetrics()).isNotNull();
        assertThat(summary.partialProgress()).isNull();
        assertThat(summary.searchStatuses()).isEmpty();
        assertThat(summary.ruleDecisions()).isEqualTo(summary.decisions());
        assertThat(summary.exploredNodes()).isZero();
        assertThat(summary.searchNanos()).isZero();
        var events = Arrays.asList(JsonFiles.JSON.readValue(temp.resolve("rules.events.json").toFile(), SimulationEvent[].class));
        var metrics = new MetricsCollector().collect(fixture.scenario().contacts(), events);
        assertThat(metrics).isEqualTo(result.simulation().metrics());
        assertThat(summary.finalMetrics().controlCpu()).isEqualTo(metrics.controlCosts().totalCpu());
        assertThat(summary.finalMetrics().newKeys()).isEqualTo(metrics.createdKeys());
        assertThat(events).filteredOn(e -> e.type() == SimulationEvent.Type.SIMULATION_ENDED).hasSize(1);
        var decisions = JsonFiles.JSON.readTree(temp.resolve("rules.decisions.json").toFile());
        assertThat(decisions).allSatisfy(d -> {
            assertThat(d.path("source").asText()).isEqualTo("RULE");
            assertThat(d.path("searchStatus").isNull()).isTrue();
        });
    }
}
