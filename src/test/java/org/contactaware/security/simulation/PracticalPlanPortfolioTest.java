package org.contactaware.security.simulation;

import org.contactaware.contacts.JsonFiles;
import org.contactaware.security.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.*;
import static org.contactaware.security.simulation.HorizonWitness.*;
import static org.contactaware.security.simulation.PlanningAction.Kind.*;
import static org.contactaware.security.simulation.PracticalPlanPortfolio.Source.*;
import static org.contactaware.security.simulation.PracticalPlanPortfolio.Variant.*;

/** PC2 contracts: same-root comparison, actual continuation receipts and observable inputs. */
class PracticalPlanPortfolioTest {
    private final SimulationEngine simulation = new SimulationEngine();
    private final BranchEngine branches = new BranchEngine();
    private final SecurityActionGenerator generator = new SecurityActionGenerator();
    private final ContinuationEvaluator evaluator = new ContinuationEvaluator();
    @TempDir Path temporary;

    // A: full DFS is the oracle. A seed/retained result must not outrank its full winner.
    @ParameterizedTest
    @EnumSource(PlanTieBreak.class)
    void completeFullDfsKeepsItsEntirePlanScoreAndCertificate(PlanTieBreak rule) throws Exception {
        var observation = PlanningObservation.from(warm(new ContinueSuffixPrepareOptimalityTest().fixture()), 2);
        var oracle = new ExactHorizonPlanner(new SearchLimits(250_000, Duration.ofSeconds(30)),
                PlannerOptions.reference(), DecisionTimeModel.V2_EVENT_BOUNDARIES, rule);
        var portfolio = new PracticalPlanPortfolio(oracle, rule, SEED_AND_RETENTION);
        var decision = portfolio.select(observation, generator.candidates(observation));
        var search = decision.search();
        // Preserve status/nodes/full plan even when the fixed oracle budget is insufficient.
        JsonFiles.write(Path.of("target", "pc2-oracle-" + rule + ".json"), Map.of(
                "fixture", "ContinueSuffixPrepareOptimalityTest after OPEN and WAIT",
                "rule", rule, "limits", new SearchLimits(250_000, Duration.ofSeconds(30)),
                "options", PlannerOptions.reference(), "observation", observation,
                "search", search, "portfolio", decision.audit()));
        assertThat(search.status()).as("nodes=%s stop=%s", search.exploredNodes(), search.diagnostics().stopReason())
                .isEqualTo(HorizonPlanResult.Status.COMPLETE);
        assertThat(search.score()).isEqualTo(score(1, 2, 2500, 56, 2));
        assertThat(decision.audit().selected()).isEqualTo(new PlanningResult(search.score(), search.bestPlan()));
        assertThat(decision.audit().source()).isEqualTo(SEARCH);
        assertThat(decision.selection().action()).isEqualTo(search.firstAction());
        assertThat(decision.selection().certificate()).isNotNull();
        assertThat(decision.selection().certificate().matches(observation, search, oracle.continuationRules())).isTrue();
        assertThat(replay(observation, decision.audit().selected().plan())).isEqualTo(decision.audit().selected());
    }

    // B: an incomplete terminal incumbent cannot bypass the common five-metric comparator.
    @ParameterizedTest
    @EnumSource(PlanTieBreak.class)
    void poorerTerminalWaitCannotBeatAFeasibleServiceSeed(PlanTieBreak rule) {
        var observation = PlanningObservation.from(warm(new ContinueSuffixPrepareOptimalityTest().fixture()), 2);
        var wait = waitOnlyIncumbent(observation);
        assertThat(wait.firstAction().kind()).isEqualTo(WAIT);
        assertThat(wait.score()).isEqualTo(score(0, 0, 0, 0, 0));
        var portfolio = new PracticalPlanPortfolio(ignored -> wait, rule, SEED_AND_RETENTION);
        var decision = portfolio.select(observation, generator.candidates(observation));
        var seed = candidate(decision, SEED);
        assertThat(new PlanningResultComparator(rule).compare(seed, new PlanningResult(wait.score(), wait.bestPlan()))).isNegative();
        assertThat(decision.audit().source()).isEqualTo(SEED);
        assertThat(decision.audit().selected()).isEqualTo(seed);
        assertThat(decision.selection().action().kind()).isEqualTo(CONTINUE);
        assertThat(decision.search().status()).isEqualTo(HorizonPlanResult.Status.INCOMPLETE);
        assertThat(decision.search().diagnostics().stopReason()).isEqualTo(SearchDiagnostics.StopReason.NODE_LIMIT);
        assertThat(decision.selection().certificate()).isNull();
    }

    @ParameterizedTest
    @EnumSource(PlanTieBreak.class)
    void actualOneNodeSearchStillReturnsTheFullyEvaluatedSeed(PlanTieBreak rule) {
        var observation = PlanningObservation.from(warm(new ContinueSuffixPrepareOptimalityTest().fixture()), 2);
        var planner = new ExactHorizonPlanner(new SearchLimits(1, Duration.ZERO),
                PlannerOptions.acceleratedServiceFirst(), DecisionTimeModel.V2_EVENT_BOUNDARIES, rule);
        var decision = new PracticalPlanPortfolio(planner, rule, SEED_ONLY)
                .select(observation, generator.candidates(observation));
        assertThat(decision.search().status()).isEqualTo(HorizonPlanResult.Status.INCOMPLETE);
        assertThat(decision.search().diagnostics().stopReason()).isEqualTo(SearchDiagnostics.StopReason.NODE_LIMIT);
        assertThat(decision.audit().source()).isEqualTo(SEED);
        assertThat(decision.audit().selected()).isEqualTo(replay(observation, decision.audit().selected().plan()));
        assertThat(decision.audit().selected().evaluation().servedContacts()).isEqualTo(1);
        assertThat(decision.selection().certificate()).isNull();
    }

    // C: the real saved pending-PREPARE observation rebases both continuations, including all costs.
    @ParameterizedTest
    @EnumSource(PlanTieBreak.class)
    void savedPostPrepareSuffixIsReplayedAtTheNewOriginWithoutRechargingPastCosts(PlanTieBreak rule) throws Exception {
        var directory = fixtures().resolve("post-prepare-known-retained-winner");
        var observation = ExecutionCheckpointFiles.read(directory.resolve("observation.json"), PlanningObservation.class);
        var retained = readPlan(directory.resolve("alternative-plan.json"));
        var fresh = readPlan(directory.resolve("selected-plan.json"));
        assertThat(observation.ongoingOperations()).singleElement().satisfies(operation -> {
            assertThat(operation.action()).isEqualTo(SecurityAction.PREPARE);
            assertThat(operation.expectedStepCompletion()).isAfter(observation.at());
        });
        assertThat(observation.accruedCosts().totalCpu()).isEqualTo(Duration.ofMillis(910));
        assertThat(observation.createdKeys()).isEqualTo(4);
        var freshResult = replay(observation, fresh);
        assertThat(freshResult.evaluation()).isEqualTo(score(2, 100, 1930, 3240, 4));
        var portfolio = new PracticalPlanPortfolio(ignored -> incomplete(freshResult), rule, SEED_AND_RETENTION);
        portfolio.offerForReplay(observation, retained);
        var decision = portfolio.select(observation, generator.candidates(observation));
        var retainedResult = candidate(decision, RETAINED);
        assertThat(decision.audit().retention()).isEqualTo("FEASIBLE_RETAINED");
        assertThat(retainedResult.plan()).isEqualTo(retained);
        assertThat(retainedResult.evaluation()).isEqualTo(score(2, 100, 1220, 2160, 2));
        var comparator = new PlanningResultComparator(rule);
        assertThat(comparator.compare(retainedResult, freshResult)).isNegative();
        assertThat(comparator.decisiveCriterion(retainedResult, freshResult)).isEqualTo(PlanningResultComparator.Criterion.CONTROL_CPU);
        // A seed may tie or improve this feasible witness; it must never force the poorer fresh plan.
        assertThat(comparator.compare(decision.audit().selected(), retainedResult)).isLessThanOrEqualTo(0);
        assertThat(decision.audit().selected().evaluation()).isNotEqualTo(freshResult.evaluation());
        assertThat(decision.selection().certificate()).isNull();
    }

    // D: exercise ordinary actual projection after frames and every management transition.
    @ParameterizedTest
    @EnumSource(PlanTieBreak.class)
    void retentionCrossesActualFramesAndPrepareInstallSwitchOpenWithRootRelativeCosts(PlanTieBreak rule) throws Exception {
        var actual = warm(new ContinueSuffixPrepareOptimalityTest().fixture());
        var portfolio = new PracticalPlanPortfolio(this::waitOnlyIncumbent, rule, SEED_AND_RETENTION);
        var executed = EnumSet.noneOf(PlanningAction.Kind.class);
        int decisions = 0;
        while (actual.at().isBefore(actual.scenario().end())) {
            assertThat(++decisions).isLessThan(30);
            var observation = PlanningObservation.from(actual, 2);
            var serialized = JsonFiles.JSON.writeValueAsBytes(actual);
            var decision = portfolio.select(observation, generator.candidates(observation));
            assertThat(JsonFiles.JSON.writeValueAsBytes(actual)).isEqualTo(serialized);
            if (decisions > 1) {
                assertThat(decision.audit().retention()).as("after %s at %s", executed, actual.at())
                        .isEqualTo("FEASIBLE_RETAINED");
                var retained = candidate(decision, RETAINED);
                assertThat(replay(observation, retained.plan())).isEqualTo(retained);
            }
            assertThat(decision.selection().certificate()).isNull();
            var selected = decision.audit().selected();
            var terminal = branches.create(observation);
            for (var action : selected.plan()) terminal = branches.execute(terminal, action);
            assertThat(selected.evaluation().controlCpu()).isEqualTo(
                    terminal.snapshot().accruedCosts().totalCpu().minus(observation.accruedCosts().totalCpu()));
            assertThat(selected.evaluation().managementBytes()).isEqualTo(
                    terminal.snapshot().accruedCosts().managementBytes() - observation.accruedCosts().managementBytes());
            assertThat(selected.evaluation().newKeys()).isEqualTo(terminal.snapshot().createdKeys() - observation.createdKeys());
            var action = decision.selection().action();
            executed.add(action.kind());
            var predicted = branches.execute(branches.create(observation), action).observation();
            actual = SecurityPolicyController.executeAction(actual, observation, action, OperationOutcomes.nominal());
            assertThat(PlanningObservation.from(actual, 2)).isEqualTo(predicted);
        }
        assertThat(executed).contains(CONTINUE, PREPARE, INSTALL, SWITCH, OPEN, WAIT);
        assertThat(actual.createdKeys()).isEqualTo(2);
        assertThat(actual.usefulSecureTime()).isEqualTo(Duration.ofSeconds(2));
        assertThat(actual.accruedCosts().totalCpu()).isEqualTo(Duration.ofSeconds(3));
        assertThat(actual.accruedCosts().managementBytes()).isEqualTo(64);
        assertThat(actual.journal()).noneMatch(event -> event.type() == SimulationEvent.Type.ACTION_FAILED);
        assertThat(actual.journal()).filteredOn(event -> event.type() == SimulationEvent.Type.PAYLOAD_TRANSFERRED).hasSize(2);
    }

    @ParameterizedTest
    @EnumSource(PlanTieBreak.class)
    void retainedSuffixExtendsOnlyAfterNewContactBecomesVisibleAndRebasesPastService(PlanTieBreak rule) {
        var base = new RecedingHorizonControllerTest().keyed(6, List.of());
        var payload = base.contacts().getFirst().payload();
        var scenario = new SimulationScenario("retained-horizon-growth", base.start(), at(26), base.initialState(),
                base.constraints(), base.costs(), List.of(base.contacts().getFirst(),
                new SimulationContact(window("C2", A, 10, 16), payload),
                new SimulationContact(window("C3", A, 20, 26), payload)), List.of());
        var actual = warm(scenario);
        for (int frame = 0; frame < 4; frame++) {
            var observation = PlanningObservation.from(actual, 1);
            var action = generator.candidates(observation).stream().filter(candidate -> candidate.kind() == CONTINUE)
                    .min(Comparator.comparing(PlanningAction::until)).orElseThrow();
            actual = SecurityPolicyController.executeAction(actual, observation, action, OperationOutcomes.nominal());
        }
        assertThat(actual.at()).isEqualTo(at(5));
        assertThat(actual.usefulSecureTime()).isEqualTo(Duration.ofSeconds(4));
        assertThat(actual.accruedCosts().totalCpu()).isEqualTo(Duration.ofSeconds(1));

        var portfolio = new PracticalPlanPortfolio(this::waitOnlyIncumbent, rule, SEED_AND_RETENTION);
        var beforeGap = PlanningObservation.from(actual, 1);
        assertThat(beforeGap.visibleContacts()).extracting(contact -> contact.window().id()).containsExactly("C1", "C2");
        assertThat(beforeGap.evaluationEnd()).isEqualTo(at(16));
        var first = portfolio.select(beforeGap, generator.candidates(beforeGap));
        assertThat(first.audit().selected().plan()).noneMatch(action -> "C3".equals(action.contactId()) || action.until().isAfter(at(16)));
        actual = SecurityPolicyController.executeAction(actual, beforeGap, first.selection().action(), OperationOutcomes.nominal());
        assertThat(actual.at()).isEqualTo(at(6));

        var gap = PlanningObservation.from(actual, 1);
        assertThat(gap.visibleContacts()).extracting(contact -> contact.window().id()).containsExactly("C2");
        var gapDecision = portfolio.select(gap, generator.candidates(gap));
        assertThat(gapDecision.audit().retention()).isEqualTo("FEASIBLE_RETAINED");
        assertThat(gapDecision.audit().selected().evaluation()).isEqualTo(score(1, 4, 1000, 0, 0));
        assertThat(gapDecision.selection().action().kind()).isEqualTo(WAIT);
        assertThat(gapDecision.selection().action().until()).isEqualTo(at(10));
        var oldSuffix = List.copyOf(gapDecision.audit().selected().plan().subList(1, gapDecision.audit().selected().plan().size()));
        assertThat(oldSuffix).noneMatch(action -> "C3".equals(action.contactId()) || action.until().isAfter(at(16)));
        var predicted = branches.execute(branches.create(gap), gapDecision.selection().action()).observation();
        assertThat(predicted.visibleContacts()).extracting(contact -> contact.window().id()).containsExactly("C2");
        actual = SecurityPolicyController.executeAction(actual, gap, gapDecision.selection().action(), OperationOutcomes.nominal());

        var expanded = PlanningObservation.from(actual, 1);
        assertThat(expanded.at()).isEqualTo(at(10));
        assertThat(expanded.visibleContacts()).extracting(contact -> contact.window().id()).containsExactly("C2", "C3");
        assertThat(expanded.evaluationEnd()).isEqualTo(at(26));
        assertThat(PracticalPlanPortfolio.anchorMismatch(predicted, expanded)).isNull();
        var decision = portfolio.select(expanded, generator.candidates(expanded));
        var retained = candidate(decision, RETAINED);
        assertThat(decision.audit().retention()).isEqualTo("FEASIBLE_RETAINED");
        assertThat(retained.plan().subList(0, oldSuffix.size())).isEqualTo(oldSuffix);
        assertThat(retained.plan().subList(oldSuffix.size(), retained.plan().size()))
                .anyMatch(action -> "C3".equals(action.contactId()));
        assertThat(retained.plan().getLast().until()).isEqualTo(at(26));
        assertThat(decision.audit().suffixTransitions()).isEqualTo(retained.plan().size()).isGreaterThan(oldSuffix.size());
        assertThat(retained.evaluation()).isEqualTo(score(2, 8, 2000, 0, 0));
        assertThat(replay(expanded, retained.plan())).isEqualTo(retained);

        var replayed = branches.create(expanded);
        for (var action : oldSuffix) replayed = branches.execute(replayed, action);
        assertThat(replayed.snapshot().at()).isEqualTo(at(16));
        for (var action : retained.plan().subList(oldSuffix.size(), retained.plan().size())) replayed = branches.execute(replayed, action);
        assertThat(replayed.snapshot().at()).isEqualTo(at(26));
        assertThat(replayed.snapshot().usefulSecureTime()).isEqualTo(Duration.ofSeconds(12));
        assertThat(replayed.snapshot().accruedCosts().totalCpu()).isEqualTo(Duration.ofSeconds(3));
        assertThat(retained.evaluation().usefulSecureTime()).isEqualTo(replayed.snapshot().usefulSecureTime().minus(expanded.usefulSecureTime()));
        assertThat(retained.evaluation().controlCpu()).isEqualTo(replayed.snapshot().accruedCosts().totalCpu().minus(expanded.accruedCosts().totalCpu()));
        assertThat(decision.selection().certificate()).isNull();
    }

    // E: receipt equality rejects stale facts; actual outages/notices exercise the same boundary.
    @Test
    void staleTimeHorizonCountersAndUncertaintyInvalidateRetention() throws Exception {
        var observation = PlanningObservation.from(warm(new ContinueSuffixPrepareOptimalityTest().fixture()), 2);
        assertThat(PracticalPlanPortfolio.anchorMismatch(observation, observation)).isNull();
        assertRejected(observation, changed(observation, "at", observation.at().plusNanos(1)), "REJECTED_STALE_TIME");
        assertRejected(observation, changed(observation, "horizon", 1), "REJECTED_HORIZON");
        assertRejected(observation, changed(observation, "uncertainContexts", Set.of(A)), "REJECTED_COMMITTED_WORK_OR_UNCERTAINTY");
        var state = observation.securityState();
        var context = state.context(A);
        var key = state.key(context.uplink().keyId());
        var keys = new TreeMap<>(state.keys());
        keys.put(key.id(), changed(key, "nextNonceCounter", key.nextNonceCounter() + 1));
        var contexts = new TreeMap<>(state.contexts());
        contexts.put(A, changed(context, "uplink", changed(context.uplink(), "nextSequenceNumber", key.nextNonceCounter() + 1)));
        var counters = changed(observation, "securityState", new SecurityState(state.satelliteId(), state.asOf(), contexts, keys));
        assertRejected(observation, counters, "REJECTED_SECURITY_COUNTERS_OR_NOTICE");
    }

    @Test
    void actualOutageAndObservedNoticeInvalidateTheNominalReceipt() {
        var base = new RecedingHorizonControllerTest().keyed(6, List.of());
        var outage = new SimulationScenario(base.name(), base.start(), base.end(), base.initialState(), base.constraints(),
                base.costs(), base.contacts(), List.of(), List.of(), List.of(new ChannelInterruption("C1", at(2), at(3))));
        var notice = new SimulationScenario(base.name(), base.start(), base.end(), base.initialState(), base.constraints(),
                base.costs(), base.contacts(), List.of(), List.of(new TrafficKeyDisclosure("receipt-notice", A, at(1), at(2))));
        var nominal = PlanningObservation.from(simulation.advance(simulation.start(base), at(2)), 2);
        var lost = PlanningObservation.from(simulation.advance(simulation.start(outage), at(2)), 2);
        assertThat(lost.currentContact()).isNull();
        assertRejected(nominal, lost, "REJECTED_CONTACT_OR_OUTAGE");
        var notified = PlanningObservation.from(simulation.advance(simulation.start(notice), at(2)), 2);
        assertThat(notified.securityState()).isNotEqualTo(nominal.securityState());
        assertRejected(nominal, notified, "REJECTED_SECURITY_COUNTERS_OR_NOTICE");
    }

    @Test
    void unexpectedActualExpiryRejectsTheSavedStillActiveReceipt() throws Exception {
        var base = new RecedingHorizonControllerTest().keyed(6, List.of());
        // A changed actual key deadline is an observable receipt mismatch, never a reason to
        // transplant the old active state or shift its retained actions past the expiration.
        var keys = new TreeMap<String, KeyState>();
        for (var key : base.initialState().keys().values()) keys.put(key.id(), changed(key, "notAfter", at(2)));
        var initial = new SecurityState(base.initialState().satelliteId(), base.initialState().asOf(), base.initialState().contexts(), keys);
        var expires = new SimulationScenario("unexpected-expiry", base.start(), base.end(), initial,
                base.constraints(), base.costs(), base.contacts(), List.of());
        var expected = PlanningObservation.from(simulation.advance(simulation.start(base), at(2)), 2);
        var expiredSnapshot = simulation.advance(simulation.start(expires), at(2));
        var actual = PlanningObservation.from(expiredSnapshot, 2);
        assertThat(expected.securityState().keys().values()).allMatch(key -> key.status() == KeyStatus.ACTIVE);
        assertThat(actual.securityState().keys().values()).allMatch(key -> key.status() == KeyStatus.DEACTIVATED
                && key.deactivationReason() == DeactivationReason.EXPIRED);
        assertThat(expiredSnapshot.journal()).anyMatch(event -> event.type() == SimulationEvent.Type.KEY_EXPIRED);
        assertRejected(expected, actual, "REJECTED_SECURITY_COUNTERS_OR_NOTICE");
    }

    @Test
    void sameRootReceiptStillReplaysAndRejectsAStaleActionTimestamp() throws Exception {
        var observation = PlanningObservation.from(warm(new ContinueSuffixPrepareOptimalityTest().fixture()), 2);
        var stale = new ArrayList<>(waitOnlyIncumbent(observation).bestPlan());
        stale.set(0, changed(stale.getFirst(), "at", observation.at().minusNanos(1)));
        var portfolio = new PracticalPlanPortfolio(this::waitOnlyIncumbent, PlanTieBreak.CANONICAL, SEED_AND_RETENTION);
        portfolio.offerForReplay(observation, stale);
        var decision = portfolio.select(observation, generator.candidates(observation));
        assertThat(decision.audit().retention()).isEqualTo("REJECTED_REPLAY:STALE_ACTION_TIME");
        assertThat(decision.audit().candidates()).noneMatch(candidate -> candidate.source() == RETAINED);
        assertThat(decision.audit().source()).isEqualTo(SEED);
    }

    // F: hidden C3 information and speculation cannot alter a permitted observation or actual checkpoint.
    @ParameterizedTest
    @EnumSource(PlanTieBreak.class)
    void hiddenThirdContactCannotChangeH1PortfolioDecisionsUnderTheSameNodeBudget(PlanTieBreak rule) throws Exception {
        var actualFirst = simulation.controlledSnapshot(simulation.start(hiddenThird(A, 80, 85)));
        var actualSecond = simulation.controlledSnapshot(simulation.start(hiddenThird(B, 90, 95)));
        var first = PlanningObservation.from(actualFirst, 1);
        var second = PlanningObservation.from(actualSecond, 1);
        assertThat(JsonFiles.JSON.writeValueAsBytes(first)).isEqualTo(JsonFiles.JSON.writeValueAsBytes(second));
        assertThat(first.visibleContacts()).extracting(contact -> contact.window().id()).containsExactly("C1", "C2");
        var leftPortfolio = fixedNodePortfolio(rule);
        var rightPortfolio = fixedNodePortfolio(rule);
        var left = leftPortfolio.select(first, generator.candidates(first));
        var right = rightPortfolio.select(second, generator.candidates(second));
        assertThat(left.search().diagnostics().stopReason()).isEqualTo(SearchDiagnostics.StopReason.NODE_LIMIT);
        assertThat(left.search().exploredNodes()).isEqualTo(right.search().exploredNodes());
        assertThat(left.audit().selected()).isEqualTo(right.audit().selected());
        assertThat(left.audit().source()).isEqualTo(right.audit().source());
        assertThat(left.selection().action()).isEqualTo(right.selection().action());
        assertThat(left.audit().candidates()).isEqualTo(right.audit().candidates());

        actualFirst = SecurityPolicyController.executeAction(actualFirst, first, left.selection().action(), OperationOutcomes.nominal());
        actualSecond = SecurityPolicyController.executeAction(actualSecond, second, right.selection().action(), OperationOutcomes.nominal());
        var nextFirst = PlanningObservation.from(actualFirst, 1);
        var nextSecond = PlanningObservation.from(actualSecond, 1);
        assertThat(JsonFiles.JSON.writeValueAsBytes(nextFirst)).isEqualTo(JsonFiles.JSON.writeValueAsBytes(nextSecond));
        assertThat(nextFirst.visibleContacts()).extracting(contact -> contact.window().id()).containsExactly("C1", "C2");
        assertThat(nextFirst.wakeUps()).doesNotContain(at(78), at(80), at(85), at(88), at(90), at(95));
        var nextLeft = leftPortfolio.select(nextFirst, generator.candidates(nextFirst));
        var nextRight = rightPortfolio.select(nextSecond, generator.candidates(nextSecond));
        assertThat(nextLeft.audit().retention()).isEqualTo("FEASIBLE_RETAINED");
        assertThat(nextRight.audit().retention()).isEqualTo("FEASIBLE_RETAINED");
        assertThat(candidate(nextLeft, RETAINED)).isEqualTo(candidate(nextRight, RETAINED));
        assertThat(nextLeft.audit().candidates()).isEqualTo(nextRight.audit().candidates());
        assertThat(nextLeft.audit().selected()).isEqualTo(nextRight.audit().selected());
        assertThat(nextLeft.audit().source()).isEqualTo(nextRight.audit().source());
        assertThat(nextLeft.selection().action()).isEqualTo(nextRight.selection().action());
        assertThat(nextLeft.search().exploredNodes()).isEqualTo(nextRight.search().exploredNodes());
    }

    @Test
    void checkpointRoundTripAndSpeculativePortfolioPreserveActualStateExactly() throws Exception {
        var actual = warm(new ContinueSuffixPrepareOptimalityTest().fixture());
        var path = temporary.resolve("actual-checkpoint.json");
        ExecutionCheckpointFiles.write(path, actual);
        var restored = ExecutionCheckpointFiles.read(path);
        assertThat(restored).isEqualTo(actual);
        var fileBefore = Files.readAllBytes(path);
        var observation = PlanningObservation.from(actual, 2);
        var before = JsonFiles.JSON.writeValueAsBytes(actual);
        var portfolio = new PracticalPlanPortfolio(this::waitOnlyIncumbent, PlanTieBreak.CANONICAL, SEED_AND_RETENTION);
        var selection = portfolio.select(observation, generator.candidates(observation));
        assertThat(JsonFiles.JSON.writeValueAsBytes(actual)).isEqualTo(before);
        assertThat(Files.readAllBytes(path)).isEqualTo(fileBefore);
        assertThat(PlanningObservation.from(restored, 2)).isEqualTo(observation);
        var fromOriginal = SecurityPolicyController.executeAction(actual, observation, selection.selection().action(), OperationOutcomes.nominal());
        var fromRestored = SecurityPolicyController.executeAction(restored, observation, selection.selection().action(), OperationOutcomes.nominal());
        assertThat(fromRestored).isEqualTo(fromOriginal);
    }

    @ParameterizedTest
    @ValueSource(strings = {"SEED", "SUFFIX"})
    void cancellationPreservesAttemptedWorkAndExecutesNoActualAction(String phase) throws Exception {
        var actual = warm(new ContinueSuffixPrepareOptimalityTest().fixture());
        var observation = PlanningObservation.from(actual, 2);
        var portfolio = new PracticalPlanPortfolio(this::waitOnlyIncumbent, PlanTieBreak.CANONICAL, SEED_AND_RETENTION);
        portfolio.offerForReplay(observation, waitOnlyIncumbent(observation).bestPlan());
        var suffixStarted = new AtomicBoolean();
        var control = new AtomicReference<SearchControl.Scope>();
        var policy = new SecurityPolicy() {
            @Override public PolicyConfiguration configuration() { return HorizonPolicy.multi(2); }
            @Override public PolicySelection select(PlanningObservation root, List<PlanningAction> candidates) {
                return portfolio.select(root, candidates).selection();
            }
        };
        var before = JsonFiles.JSON.writeValueAsBytes(actual);
        ControlledSimulationResult result;
        try (var scope = SearchControl.open(Duration.ZERO, SearchDiagnostics.StopReason.CONTROLLER_TIME_LIMIT,
                System::nanoTime, checkpoint -> {
                    if (checkpoint.equals("portfolio.retainedReplay")) suffixStarted.set(true);
                    if (checkpoint.equals("branch.execute") && (phase.equals("SUFFIX") == suffixStarted.get()))
                        control.get().cancel();
                })) {
            control.set(scope);
            result = new SecurityPolicyController(policy).run(actual);
        }
        assertThat(result.status()).isEqualTo(ControlledSimulationResult.Status.CANCELLED);
        assertThat(result.decisions()).isEmpty();
        assertThat(result.simulation()).isNull();
        assertThat(result.snapshot()).isEqualTo(actual);
        assertThat(JsonFiles.JSON.writeValueAsBytes(actual)).isEqualTo(before);
        assertThat(portfolio.audits()).isEmpty();
        assertThat(portfolio.stoppedWork()).singleElement().satisfies(stopped -> {
            assertThat(stopped.reason()).isEqualTo("CANCELLED");
            assertThat(stopped.checkpoint()).isEqualTo("branch.execute");
            assertThat(stopped.phase()).isEqualTo(phase);
            assertThat(stopped.seedTransitions()).isPositive();
            assertThat(stopped.suffixTransitions()).isEqualTo(phase.equals("SUFFIX") ? 1 : 0);
            if (phase.equals("SEED")) assertThat(stopped.seedTransitions()).isEqualTo(1);
            assertThat(stopped.searchTransitions()).isZero();
            assertThat(stopped.predictionTransitions()).isZero();
            assertThat(stopped.elapsedNanos()).isNotNegative();
            assertThat(stopped.activePhaseNanos()).isNotNegative();
        });
        // An aborted call must not leave an unvalidated retained candidate for a later call.
        var retried = portfolio.select(observation, generator.candidates(observation));
        assertThat(retried.audit().retention()).isEqualTo("NO_RETAINED_PLAN");
    }

    private void assertRejected(PlanningObservation expected, PlanningObservation actual, String reason) {
        assertThat(PracticalPlanPortfolio.anchorMismatch(expected, actual)).isEqualTo(reason);
        var portfolio = new PracticalPlanPortfolio(this::waitOnlyIncumbent, PlanTieBreak.CANONICAL, SEED_AND_RETENTION);
        portfolio.offerForReplay(expected, waitOnlyIncumbent(expected).bestPlan());
        var decision = portfolio.select(actual, generator.candidates(actual));
        assertThat(decision.audit().retention()).isEqualTo(reason);
        assertThat(decision.audit().candidates()).noneMatch(candidate -> candidate.source() == RETAINED);
    }

    private PracticalPlanPortfolio fixedNodePortfolio(PlanTieBreak rule) {
        var planner = new ExactHorizonPlanner(new SearchLimits(5, Duration.ZERO), PlannerOptions.acceleratedServiceFirst(),
                DecisionTimeModel.V2_EVENT_BOUNDARIES, rule);
        return new PracticalPlanPortfolio(planner, rule, SEED_AND_RETENTION);
    }

    private SimulationScenario hiddenThird(ContextId third, long from, long until) {
        var payload = new SimulationContact.PayloadProfile(100, 100, 100, 100, 100, 100);
        return new SimulationScenario("hidden-third", at(0), at(until), initialState(), CONSTRAINTS,
                SecurityCostProfile.syntheticWitness(), List.of(new SimulationContact(window("C1", A, 0, 20), payload),
                new SimulationContact(window("C2", B, 30, 50), payload), new SimulationContact(window("C3", third, from, until), payload)), List.of());
    }

    private ExecutionSnapshot warm(SimulationScenario scenario) {
        var actual = simulation.controlledSnapshot(simulation.start(scenario));
        for (var kind : List.of(OPEN, WAIT)) {
            var observation = PlanningObservation.from(actual, 2);
            var action = generator.candidates(observation).stream().filter(candidate -> candidate.kind() == kind)
                    .min(Comparator.comparing(PlanningAction::until)).orElseThrow();
            actual = SecurityPolicyController.executeAction(actual, observation, action, OperationOutcomes.nominal());
        }
        return actual;
    }

    private PlanningResult replay(PlanningObservation observation, List<PlanningAction> plan) {
        var branch = branches.create(observation);
        for (var action : plan) branch = branches.execute(branch, action);
        assertThat(branch.snapshot().at()).isEqualTo(observation.evaluationEnd());
        return evaluator.evaluate(branch);
    }

    private HorizonPlanResult waitOnlyIncumbent(PlanningObservation observation) {
        var branch = branches.create(observation);
        int steps = 0;
        while (branch.snapshot().at().isBefore(observation.evaluationEnd())) {
            assertThat(++steps).isLessThan(100);
            var action = generator.candidates(branch.observation()).stream().filter(candidate -> candidate.kind() == WAIT)
                    .min(Comparator.comparing(PlanningAction::until)).orElseThrow();
            branch = branches.execute(branch, action);
        }
        return incomplete(evaluator.evaluate(branch));
    }

    private static HorizonPlanResult incomplete(PlanningResult result) {
        var diagnostics = new SearchDiagnostics.Counter();
        diagnostics.stopReason = SearchDiagnostics.StopReason.NODE_LIMIT;
        diagnostics.terminalPlans = 1;
        return new HorizonPlanResult(result.plan(), result.evaluation(), result.plan().isEmpty() ? null : result.plan().getFirst(),
                HorizonPlanResult.Status.INCOMPLETE, 1, 1, diagnostics.snapshot());
    }

    private static PlanningResult candidate(PracticalPlanPortfolio.Decision decision, PracticalPlanPortfolio.Source source) {
        return decision.audit().candidates().stream().filter(candidate -> candidate.source() == source)
                .map(PracticalPlanPortfolio.Candidate::result).findFirst().orElseThrow();
    }

    private static Path fixtures() {
        var path = Path.of(System.getProperty("pc2.fixtures", "target/practical-controller-pc2/fixtures"));
        assertThat(path).as("Extract PC2 fixtures or pass -Dpc2.fixtures=<preserved fixture root>").isDirectory();
        return path;
    }

    private static List<PlanningAction> readPlan(Path path) throws Exception {
        var actions = new ArrayList<PlanningAction>();
        for (var node : JsonFiles.JSON.readTree(path.toFile())) actions.add(ExecutionCheckpointFiles.convert(node, PlanningAction.class));
        return List.copyOf(actions);
    }

    private static BranchEvaluation score(long served, long usefulSeconds, long cpuMillis, long bytes, long keys) {
        return new BranchEvaluation(served, Duration.ofSeconds(usefulSeconds), Duration.ofMillis(cpuMillis), bytes, keys);
    }

    @SuppressWarnings("unchecked")
    private static <T extends Record> T changed(T value, String field, Object replacement) throws Exception {
        var components = value.getClass().getRecordComponents();
        var types = new Class<?>[components.length];
        var arguments = new Object[components.length];
        for (int i = 0; i < components.length; i++) {
            types[i] = components[i].getType();
            arguments[i] = components[i].getName().equals(field) ? replacement : components[i].getAccessor().invoke(value);
        }
        return (T) value.getClass().getDeclaredConstructor(types).newInstance(arguments);
    }
}
