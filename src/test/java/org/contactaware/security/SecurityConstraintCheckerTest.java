package org.contactaware.security;

import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.contactaware.security.SecurityFixtures.*;
import static org.contactaware.security.SecurityConstraintChecker.Code.*;

class SecurityConstraintCheckerTest {
    private final LifecycleTransitions transitions = new LifecycleTransitions(LIMITS);
    private final SecurityConstraintChecker checker = new SecurityConstraintChecker(LIMITS);

    private static void rejects(SecurityConstraintChecker.Result result, SecurityConstraintChecker.Code code) {
        assertThat(result.allowed()).isFalse();
        assertThat(result.violations()).extracting(SecurityConstraintChecker.Violation::code).contains(code);
    }

    @Test void reuseGapIsInclusiveAndUsesContactStartNotResumeCompletion() {
        var state = firstACompleted(transitions);
        var exact = contact("exact", A, 140, 170);
        assertThat(checker.canReuseContext(state, A, exact, at(145)).allowed()).isTrue();
        var justLate = new org.contactaware.contacts.Model.ContactWindow("one-nanosecond-late", SATELLITE,
                A.groundStationId(), at(140).plusNanos(1), at(170), null, null, List.of());
        rejects(checker.canReuseContext(state, A, justLate, at(145)), REUSE_GAP_EXCEEDED);
        var late = contact("late", A, 141, 170);
        rejects(checker.canReuseContext(state, A, late, at(141)), REUSE_GAP_EXCEEDED);
        var overlapping = contact("overlap", A, 39, 60);
        rejects(checker.canReuseContext(state, A, overlapping, at(41)), INVALID_REUSE_ORDER);
    }

    @Test void zeroReuseGapAllowsTouchingSuccessfulContacts() {
        var zero = new SecurityConstraintChecker(limits(1000, 2000, 0));
        var state = firstACompleted(transitions);
        assertThat(zero.canReuseContext(state, A, contact("touch", A, 40, 60), at(40)).allowed()).isTrue();
        rejects(zero.canReuseContext(state, A, contact("gap", A, 41, 60), at(41)), REUSE_GAP_EXCEEDED);
    }

    @Test void expiryBoundaryBlocksStartButAllowsTrafficEndingExactlyAtDeadline() {
        var limits = limits(20, 2000, 100);
        var shortLifetime = new LifecycleTransitions(limits);
        var bound = keyed(shortLifetime, initial(shortLifetime), A, A_KEYS, FIRST_A);
        var open = shortLifetime.startContext(bound, A, FIRST_A, at(10));
        var validator = new SecurityConstraintChecker(limits);
        assertThat(validator.canProtect(open, A, at(11), at(30), 1, 1).allowed()).isTrue();
        rejects(validator.canProtect(open, A, at(11), at(30).plusNanos(1), 1, 1), KEY_EXPIRED);
        var atDeadline = shortLifetime.recordProtectedTraffic(open, A, at(11), at(30), 1, 1);
        rejects(validator.canProtect(atDeadline, A, at(30), at(31), 1, 1), KEY_EXPIRED);
        var stopped = shortLifetime.stopContext(atDeadline, A, at(30));
        rejects(validator.canStartContext(stopped, A, contact("next", A, 30, 50), at(30)), KEY_EXPIRED);
        var retired = shortLifetime.expireContext(atDeadline, A, DeactivationReason.EXPIRED, at(30));
        assertThat(retired.context(A).status()).isEqualTo(AssociationStatus.UNKEYED);
    }

    @Test void materialAgeCanExpireBeforeCryptoperiodAndBeforeActivation() {
        var limits = limits(1000, 15, 100);
        var shortMaterialAge = new LifecycleTransitions(limits);
        var bound = keyed(shortMaterialAge, initial(shortMaterialAge), A, A_KEYS, FIRST_A);
        assertThat(bound.key(A_KEYS.uplink()).notAfter()).isEqualTo(at(15));
        rejects(new SecurityConstraintChecker(limits).canStartContext(bound, A, FIRST_A, at(15)), KEY_EXPIRED);
        var prepared = shortMaterialAge.prepareKeys(initial(shortMaterialAge), A, A_KEYS, at(0));
        assertThatThrownBy(() -> shortMaterialAge.confirmInstallation(prepared, A, A_KEYS, FIRST_A, at(15))).hasMessageContaining("KEY_EXPIRED");
        var installed = shortMaterialAge.confirmInstallation(prepared, A, A_KEYS, FIRST_A, at(14));
        assertThatThrownBy(() -> shortMaterialAge.activateKeys(installed, A, A_KEYS, FIRST_A, at(15))).hasMessageContaining("KEY_EXPIRED");
        assertThat(installed.key(A_KEYS.uplink()).status()).isEqualTo(KeyStatus.PRE_ACTIVE);
    }

    @Test void laterConfigurationCannotExtendFrozenDeadlineButCanTightenIt() {
        var bound = keyed(transitions, initial(transitions), A, A_KEYS, FIRST_A);
        var key = bound.key(A_KEYS.uplink());
        assertThat(key.hardDeadline(limits(5000, 9000, 100))).isEqualTo(at(1010));
        assertThat(key.hardDeadline(limits(5, 2000, 100))).isEqualTo(at(15));
        var prepared = transitions.prepareKeys(initial(transitions), A, A_KEYS, at(0));
        assertThat(prepared.key(A_KEYS.uplink()).hardDeadline(limits(5000, 9000, 100))).isEqualTo(at(2000));
    }

    @Test void blockedAndUnauthorizedKeysCannotBeUsedButCanBeRetired() {
        var state = firstACompleted(transitions);
        var blocked = new SecurityConstraints(LIMITS.cryptoperiod(), LIMITS.maxMaterialAge(), LIMITS.maxReuseGap(), Set.of(A, B), Set.of(A_KEYS.uplink()));
        var next = contact("next", A, 100, 130);
        rejects(new SecurityConstraintChecker(blocked).canReuseContext(state, A, next, at(100)), KEY_BLOCKED);
        var unauthorized = new SecurityConstraints(LIMITS.cryptoperiod(), LIMITS.maxMaterialAge(), LIMITS.maxReuseGap(), Set.of(B), Set.of());
        rejects(new SecurityConstraintChecker(unauthorized).canReuseContext(state, A, next, at(100)), UNAUTHORIZED_CONTEXT);
        assertThat(new LifecycleTransitions(unauthorized).expireContext(state, A, DeactivationReason.REVOKED, at(100)).context(A).status()).isEqualTo(AssociationStatus.UNKEYED);
    }

    @Test void crossStationCrossSatelliteAndCrossDirectionReuseAreRejected() {
        var state = firstACompleted(transitions);
        var bContact = contact("B", B, 100, 130);
        rejects(checker.canReuseContext(state, A, bContact, at(100)), CONTACT_MISMATCH);
        var otherSatellite = new ContextId("NORAD-1", A.groundStationId());
        rejects(checker.canReuseContext(state, A, contact("other", otherSatellite, 100, 130), at(100)), CONTACT_MISMATCH);
        rejects(checker.canBindKeys(state, B, A_KEYS, bContact, at(100)), KEY_SCOPE_MISMATCH);
        var wrongDirections = new KeyPairIds(A_KEYS.downlink(), A_KEYS.uplink());
        rejects(checker.canBindKeys(transitions.unbindKeys(state, A, at(40)), A, wrongDirections,
                contact("A", A, 100, 130), at(100)), KEY_SCOPE_MISMATCH);
    }

    @Test void peerDesynchronizationPreventsReuse() {
        var state = firstACompleted(transitions);
        var context = state.context(A);
        var sa = context.uplink();
        var unsynchronized = new SecurityAssociation(sa.id(), A, Direction.UPLINK, sa.status(), sa.keyId(), sa.generation(), sa.nextSequenceNumber(), false);
        var changed = context.closed(unsynchronized, context.downlink());
        var snapshot = state.replace(at(40), changed, List.of());
        rejects(checker.canReuseContext(snapshot, A, contact("next", A, 100, 130), at(100)), PEER_NOT_SYNCHRONIZED);
    }

    @Test void anotherOperationalStationBlocksOnlineStepsButNotLocalPreparation() {
        var bound = keyed(transitions, initial(transitions), A, A_KEYS, FIRST_A);
        var open = transitions.startContext(bound, A, FIRST_A, at(10));
        var preparedB = transitions.prepareKeys(open, B, B_KEYS, at(11));
        var overlappingB = contact("B", B, 10, 40);
        rejects(checker.canInstallKeys(preparedB, B, B_KEYS, overlappingB, at(12)), LINK_BUSY);
        assertThat(preparedB.key(B_KEYS.uplink()).status()).isEqualTo(KeyStatus.PRE_ACTIVE);
    }

    @Test void emptySessionDoesNotRefreshSuccessfulReuseHistory() {
        var previous = firstACompleted(transitions);
        var emptyContact = contact("empty", A, 100, 120);
        var opened = transitions.startContext(previous, A, emptyContact, at(100));
        var stopped = transitions.stopContext(opened, A, at(120));
        assertThat(stopped.key(A_KEYS.uplink()).lastSuccessfulContactEnd()).isEqualTo(at(40));
        rejects(checker.canReuseContext(stopped, A, contact("next", A, 150, 180), at(150)), REUSE_GAP_EXCEEDED);
    }

    @Test void firstStartIsNotCountedAsReuseAndAdmissionChecksAreReadOnly() {
        var state = keyed(transitions, initial(transitions), A, A_KEYS, FIRST_A);
        var snapshot = state;
        assertThat(checker.canStartContext(state, A, FIRST_A, at(10)).allowed()).isTrue();
        rejects(checker.canReuseContext(state, A, FIRST_A, at(10)), NO_PREVIOUS_SUCCESS);
        rejects(checker.canStartContext(state, A, FIRST_A, at(9)), TIME_BEFORE_STATE);
        rejects(checker.canStartContext(state, A, FIRST_A, at(40)), OUTSIDE_CONTACT);
        assertThat(state).isEqualTo(snapshot);
    }

    @Test void counterExhaustionCannotWrapAndNoTrafficCanOccurOutsideSession() {
        var bound = keyed(transitions, initial(transitions), A, A_KEYS, FIRST_A);
        var open = transitions.startContext(bound, A, FIRST_A, at(10));
        var full = transitions.recordProtectedTraffic(open, A, at(11), at(12), Long.MAX_VALUE, 1);
        rejects(checker.canProtect(full, A, at(12), at(13), 1, 0), COUNTER_EXHAUSTED);
        rejects(checker.canProtect(open, A, at(11), at(41), 1, 1), OUTSIDE_CONTACT);
        rejects(checker.canProtect(bound, A, at(11), at(12), 1, 1), INVALID_SA_STATUS);
        rejects(checker.canProtect(open, A, at(11), at(11), 1, 1), INVALID_INTERVAL);
        rejects(checker.canProtect(open, A, at(11), at(12), 0, 0), INVALID_INTERVAL);
    }

    @Test void onlySuccessfulDirectionRefreshesItsHistory() {
        var bound = keyed(transitions, initial(transitions), A, A_KEYS, FIRST_A);
        var open = transitions.startContext(bound, A, FIRST_A, at(10));
        var used = transitions.recordProtectedTraffic(open, A, at(11), at(39), 0, 1);
        var stopped = transitions.stopContext(used, A, at(40));
        assertThat(stopped.key(A_KEYS.uplink()).lastSuccessfulContactEnd()).isNull();
        assertThat(stopped.key(A_KEYS.downlink()).lastSuccessfulContactEnd()).isEqualTo(at(40));
        assertThat(checker.canReuseContext(stopped, A, contact("next", A, 100, 130), at(100)).allowed()).isTrue();
        rejects(checker.canReuseContext(stopped, A, contact("late", A, 141, 170), at(141)), REUSE_GAP_EXCEEDED);
    }
}
