package org.contactaware.security.simulation;

import org.contactaware.contacts.JsonFiles;
import org.contactaware.security.*;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.contactaware.security.simulation.ContinuationReuseGuard.*;
import static org.contactaware.security.simulation.HorizonWitness.*;
import static org.contactaware.security.simulation.PlanningAction.Kind.*;

class ContinuationReuseGuardTest {
    private static final Rules RULES = new Rules(DecisionTimeModel.V2_EVENT_BOUNDARIES, PlanTieBreak.CONTINUE_WAIT);
    private static Case prepared, generated;
    private final ContinuationReuseGuard guard = new ContinuationReuseGuard();
    private record Case(SearchEvidence evidence, PlanningObservation after) { }

    @BeforeAll static void solveTheTwoExistingExamplesOnce() throws Exception {
        prepared = solve("prepared", new ContinueSuffixOptimalityTest().fixture(), List.of(OPEN, WAIT, CONTINUE));
        generated = solve("prepare-in-suffix", new ContinueSuffixPrepareOptimalityTest().fixture(), List.of(OPEN, WAIT));
    }

    @Test void allowsPreparedKeyExample() {
        var result = check(prepared);
        assertThat(result.status()).isEqualTo(Status.ALLOWED);
        assertThat(result.reason()).contains("ONE_FRAME_NOMINAL");
    }

    @Test void allowsPrepareInSuffixIncludingSettledExpiryAtFrameEnd() {
        assertThat(generated.after().securityState().keys().values()).allMatch(k -> k.status() == KeyStatus.DEACTIVATED);
        assertThat(generated.after().at()).isEqualTo(at(2));
        assertThat(check(generated).status()).isEqualTo(Status.ALLOWED);
    }

    @Test void rejectsChangedHorizonEvenWithIdenticalVisibleContacts() throws Exception {
        reject(changed(prepared.after(), "horizon", 3), "HORIZON_CHANGED");
    }

    @Test void rejectsChangedPayloadInsideTheSameGeometricContact() throws Exception {
        var original = prepared.after().currentContact();
        var contact = new SimulationContact(original.window(), changed(original.payload(), "offeredUplinkBytes", 200L));
        var contacts = new ArrayList<>(prepared.after().visibleContacts());
        contacts.set(0, contact);
        var actual = changed(changed(prepared.after(), "currentContact", contact), "visibleContacts", contacts);
        reject(actual, "FORECAST_CHANGED");
    }

    @Test void rejectsChangedTerminalContactAndEvaluationEnd() throws Exception {
        var last = prepared.after().visibleContacts().getLast();
        var contacts = new ArrayList<>(prepared.after().visibleContacts());
        contacts.set(contacts.size() - 1, new SimulationContact(changed(last.window(), "end", at(85)), last.payload()));
        var actual = changed(prepared.after(), Map.of("visibleContacts", contacts, "evaluationEnd", at(85)));
        reject(actual, "FORECAST_CHANGED");
    }

    @Test void rejectsChangedDeliveryProgress() throws Exception {
        var progress = new TreeMap<>(prepared.after().payloadProgress());
        progress.put("C1", changed(progress.get("C1"), "uplinkBytes", 101L));
        reject(changed(prepared.after(), "payloadProgress", progress), "PROGRESS_MISMATCH");
    }

    @Test void rejectsChangedCounterEvenWhenKeyAndSaRemainConsistent() throws Exception {
        var state = prepared.after().securityState();
        var context = state.context(A);
        var oldKey = state.key(context.uplink().keyId());
        var keys = new TreeMap<>(state.keys());
        keys.put(oldKey.id(), changed(oldKey, "nextNonceCounter", oldKey.nextNonceCounter() + 1));
        var contexts = new TreeMap<>(state.contexts());
        contexts.put(A, changed(context, "uplink", changed(context.uplink(), "nextSequenceNumber", oldKey.nextNonceCounter() + 1)));
        var changedState = new SecurityState(state.satelliteId(), state.asOf(), contexts, keys);
        reject(changed(prepared.after(), "securityState", changedState), "SECURITY_STATE_MISMATCH");
    }

    @Test void rejectsIncompleteEvenWhenThereIsAnIncumbent() throws Exception {
        var evidence = prepared.evidence();
        var incomplete = changed(evidence.result(), "status", HorizonPlanResult.Status.INCOMPLETE);
        var result = guard.check(changed(evidence, "result", incomplete), prepared.after(), RULES);
        assertThat(result.status()).isEqualTo(Status.REJECTED);
        assertThat(result.reason()).contains("PLAN_NOT_COMPLETE", "INCOMPLETE");
    }

    @Test void rejectsChangedCostsConstraintsAndComparisonRules() throws Exception {
        reject(changed(prepared.after(), "costs", SecurityCostProfile.syntheticWitness()), "COSTS_CHANGED");
        reject(changed(prepared.after(), "constraints", changed(prepared.after().constraints(), "cryptoperiod", Duration.ofSeconds(151))),
                "CONSTRAINTS_CHANGED");
        var result = guard.check(prepared.evidence(), prepared.after(), new Rules(RULES.timing(), PlanTieBreak.CANONICAL));
        assertThat(result.status()).isEqualTo(Status.REJECTED);
        assertThat(result.reason()).contains("RULES_CHANGED");
    }

    @Test void rejectsChangedAccountingIssuedIdsAndTimers() throws Exception {
        reject(changed(prepared.after(), "recordedCosts", ResourceUsage.ZERO), "COST_PROGRESS_MISMATCH");
        reject(changed(prepared.after(), "accruedCosts", ResourceUsage.ZERO), "COST_PROGRESS_MISMATCH");
        reject(changed(prepared.after(), "usefulSecureTime", Duration.ZERO), "TOTALS_MISMATCH");
        reject(changed(prepared.after(), "createdKeys", 2L), "TOTALS_MISMATCH");
        var keyIds = new HashSet<>(prepared.after().issuedKeyIds()); keyIds.add("failed-prepare-attempt-id");
        reject(changed(prepared.after(), "issuedKeyIds", keyIds), "ISSUED_IDS_MISMATCH");
        var operationIds = new HashSet<>(prepared.after().issuedOperationIds()); operationIds.add("branch-operation:99");
        reject(changed(prepared.after(), "issuedOperationIds", operationIds), "ISSUED_IDS_MISMATCH");
        reject(prepared.after().withAdditionalWakeUps(List.of(at(3).plusMillis(250))), "WAKEUPS_MISMATCH");
    }

    @Test void rejectsStateBeforeProcessingTheKnownExpiry() throws Exception {
        var actual = changed(generated.after(), "securityState", generated.evidence().origin().securityState());
        var result = guard.check(generated.evidence(), actual, RULES);
        assertThat(result.status()).isEqualTo(Status.REJECTED);
        assertThat(result.reason()).contains("SECURITY_STATE_MISMATCH");
    }

    @Test void refusesUnknownOrConstrainedSearchProvenance() throws Exception {
        for (var scope : List.of(SearchScope.UNKNOWN, SearchScope.FIXED_FIRST_ACTION)) {
            var result = guard.check(changed(prepared.evidence(), "scope", scope), prepared.after(), RULES);
            assertThat(result.status()).isEqualTo(Status.UNSUPPORTED);
            assertThat(result.reason()).contains("SEARCH_PROVENANCE");
        }
        assertThat(guard.check(null, prepared.after(), RULES).status()).isEqualTo(Status.UNSUPPORTED);
    }

    @Test void refusesOtherTimingModelsAndUnrepresentedRootTimers() throws Exception {
        var rules = new Rules(DecisionTimeModel.PREPARE_DURATION_WAIT, PlanTieBreak.CONTINUE_WAIT);
        var otherTiming = guard.check(changed(prepared.evidence(), "rules", rules), prepared.after(), rules);
        assertThat(otherTiming.status()).isEqualTo(Status.UNSUPPORTED);
        assertThat(otherTiming.reason()).contains("TIMING_MODEL");
        var origin = prepared.evidence().origin().withAdditionalWakeUps(List.of(at(2).plusMillis(250)));
        var timer = guard.check(changed(prepared.evidence(), "origin", origin), prepared.after(), RULES);
        assertThat(timer.status()).isEqualTo(Status.UNSUPPORTED);
        assertThat(timer.reason()).contains("ROOT_RESTORATION");
    }

    @Test void refusesConcurrentCommittedWorkInsteadOfAssumingItRestores() throws Exception {
        var origin = prepared.evidence().origin();
        var reservations = List.of(new PlanningObservation.ResourceReservation("already-started", "ground:A", origin.at(), at(4)));
        var result = guard.check(changed(prepared.evidence(), "origin", changed(origin, "resourceReservations", reservations)),
                prepared.after(), RULES);
        assertThat(result.status()).isEqualTo(Status.UNSUPPORTED);
        assertThat(result.reason()).contains("COMMITTED_WORK");
    }

    private Result check(Case fixture) { return guard.check(fixture.evidence(), fixture.after(), RULES); }

    private void reject(PlanningObservation actual, String reason) {
        var result = guard.check(prepared.evidence(), actual, RULES);
        assertThat(result.status()).as(result.reason()).isEqualTo(Status.REJECTED);
        assertThat(result.reason()).contains(reason);
    }

    private static Case solve(String name, SimulationScenario scenario, List<PlanningAction.Kind> warmup) throws Exception {
        var engine = new SimulationEngine();
        var generator = new SecurityActionGenerator();
        var actual = engine.controlledSnapshot(engine.start(scenario));
        for (var kind : warmup) {
            var observation = PlanningObservation.from(actual, 2);
            var action = generator.candidates(observation).stream().filter(a -> a.kind() == kind)
                    .min(Comparator.comparing(PlanningAction::until)).orElseThrow();
            actual = SecurityPolicyController.executeAction(actual, observation, action, OperationOutcomes.nominal());
        }
        var origin = PlanningObservation.from(actual, 2);
        var planner = new ExactHorizonPlanner(new SearchLimits(250_000, Duration.ofSeconds(30)), PlannerOptions.reference(),
                RULES.timing(), RULES.tieBreak());
        var result = planner.plan(origin); // Unrestricted; metadata is bound at this call, not inferred from status.
        System.out.println("CONTINUATION_GUARD_SEARCH=" + JsonFiles.JSON.writer()
                .without(com.fasterxml.jackson.databind.SerializationFeature.INDENT_OUTPUT).writeValueAsString(
                        Map.of("fixture", name, "result", result)));
        assertThat(result.status()).isEqualTo(HorizonPlanResult.Status.COMPLETE);
        assertThat(result.firstAction().kind()).isEqualTo(CONTINUE);
        var evidence = new SearchEvidence(origin, result, RULES, SearchScope.UNRESTRICTED_CURRENT_ENGINE);
        // Execute just the first action with the ordinary controller executor. Never execute the suffix here.
        var after = SecurityPolicyController.executeAction(actual, origin, result.firstAction(), OperationOutcomes.nominal());
        return new Case(evidence, PlanningObservation.from(after, 2));
    }

    /** Mutate one observation fact for negative tests while retaining record constructor validation. */
    private static <T extends Record> T changed(T value, String field, Object replacement) throws Exception {
        return changed(value, Map.of(field, replacement));
    }

    @SuppressWarnings("unchecked")
    private static <T extends Record> T changed(T value, Map<String, Object> replacements) throws Exception {
        var components = value.getClass().getRecordComponents();
        var types = new Class<?>[components.length];
        var arguments = new Object[components.length];
        var unknown = new HashSet<>(replacements.keySet());
        for (int i = 0; i < components.length; i++) {
            var component = components[i];
            types[i] = component.getType();
            arguments[i] = replacements.containsKey(component.getName()) ? replacements.get(component.getName())
                    : component.getAccessor().invoke(value);
            unknown.remove(component.getName());
        }
        assertThat(unknown).isEmpty();
        return (T) value.getClass().getDeclaredConstructor(types).newInstance(arguments);
    }
}
