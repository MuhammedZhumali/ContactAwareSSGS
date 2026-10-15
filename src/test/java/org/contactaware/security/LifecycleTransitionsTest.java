package org.contactaware.security;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.contactaware.security.SecurityFixtures.*;

class LifecycleTransitionsTest {
    private final LifecycleTransitions transitions = new LifecycleTransitions(LIMITS);

    @Test void prepareIsLocalAndCannotActivateWithoutConfirmedInstallation() {
        var before = initial(transitions);
        var prepared = transitions.prepareKeys(before, A, A_KEYS, at(1));
        assertThat(before.keys()).isEmpty();
        assertThat(prepared.key(A_KEYS.uplink()).status()).isEqualTo(KeyStatus.PRE_ACTIVE);
        assertThat(prepared.key(A_KEYS.uplink()).installedOnBothPeers()).isFalse();
        assertThat(prepared.key(A_KEYS.uplink()).activatedAt()).isNull();
        assertThat(prepared.context(A).status()).isEqualTo(AssociationStatus.UNKEYED);
        assertThatThrownBy(() -> transitions.activateKeys(prepared, A, A_KEYS, FIRST_A, at(10))).hasMessageContaining("KEY_NOT_INSTALLED");
        assertThatThrownBy(() -> transitions.confirmInstallation(prepared, A, A_KEYS, FIRST_A, at(9))).hasMessageContaining("OUTSIDE_CONTACT");
        assertThatThrownBy(() -> transitions.confirmInstallation(prepared, A, A_KEYS, FIRST_A, at(40))).hasMessageContaining("OUTSIDE_CONTACT");
    }

    @Test void completeLifecycleKeepsKeyAndSaStatesSeparate() {
        var used = firstACompleted(transitions);
        assertThat(used.context(A).status()).isEqualTo(AssociationStatus.KEYED);
        assertThat(used.key(A_KEYS.uplink()).status()).isEqualTo(KeyStatus.ACTIVE);
        assertThat(used.key(A_KEYS.uplink()).lastSuccessfulContactEnd()).isEqualTo(at(40));
        assertThat(used.key(A_KEYS.uplink()).nextNonceCounter()).isEqualTo(2);
        var detached = transitions.unbindKeys(used, A, at(40));
        assertThat(detached.context(A).status()).isEqualTo(AssociationStatus.UNKEYED);
        assertThat(detached.key(A_KEYS.uplink()).status()).isEqualTo(KeyStatus.ACTIVE);
        var retired = transitions.deactivateKeys(detached, A_KEYS, DeactivationReason.POLICY_RETIRED, at(41));
        var destroyed = transitions.destroyKeys(retired, A_KEYS, at(42));
        assertThat(destroyed.key(A_KEYS.uplink()).status()).isEqualTo(KeyStatus.DESTROYED);
        assertThat(destroyed.key(A_KEYS.uplink()).deactivationReason()).isEqualTo(DeactivationReason.POLICY_RETIRED);
        assertThat(destroyed.key(A_KEYS.uplink()).destroyedAt()).isEqualTo(at(42));
        assertThat(transitions.deleteContext(destroyed, A, at(42)).context(A).status()).isEqualTo(AssociationStatus.ABSENT);
        assertThat(used.key(A_KEYS.uplink()).status()).isEqualTo(KeyStatus.ACTIVE);
    }

    @Test void aToBToAReusesIndependentContextWithoutResettingAgeOrCounters() {
        var aCompleted = firstACompleted(transitions);
        var bContact = contact("B-1", B, 50, 70);
        var bKeyed = keyed(transitions, aCompleted, B, B_KEYS, bContact);
        var bOpen = transitions.startContext(bKeyed, B, bContact, at(50));
        var bUsed = transitions.recordProtectedTraffic(bOpen, B, at(51), at(69), 1, 1);
        var bStopped = transitions.stopContext(bUsed, B, at(70));
        assertThat(bStopped.context(A)).isEqualTo(aCompleted.context(A));
        assertThat(bStopped.key(A_KEYS.uplink())).isEqualTo(aCompleted.key(A_KEYS.uplink()));
        var secondA = contact("A-2", A, 100, 130);
        var resumed = transitions.startContext(bStopped, A, secondA, at(101));
        assertThat(resumed.context(A).uplink().nextSequenceNumber()).isEqualTo(2);
        assertThat(resumed.key(A_KEYS.uplink()).activatedAt()).isEqualTo(at(10));
        assertThat(resumed.key(A_KEYS.uplink()).notAfter()).isEqualTo(at(1010));
        var reused = transitions.recordProtectedTraffic(resumed, A, at(102), at(129), 4, 5);
        assertThat(reused.key(A_KEYS.uplink()).nextNonceCounter()).isEqualTo(6);
        assertThat(reused.context(A).downlink().nextSequenceNumber()).isEqualTo(8);
        assertThat(reused.context(B).status()).isEqualTo(AssociationStatus.KEYED);
    }

    @Test void rebindAndSaRecreationCannotResetNonceProgressOrKeyLifetime() {
        var completed = firstACompleted(transitions);
        var detached = transitions.unbindKeys(completed, A, at(40));
        var deleted = transitions.deleteContext(detached, A, at(40));
        var recreated = transitions.createContext(deleted, A, "SA-A-up", "SA-A-down", at(41));
        var next = contact("A-2", A, 100, 130);
        var rebound = transitions.bindKeys(recreated, A, A_KEYS, next, at(100));
        assertThat(rebound.key(A_KEYS.uplink())).isEqualTo(completed.key(A_KEYS.uplink()));
        assertThat(rebound.context(A).uplink().nextSequenceNumber()).isEqualTo(2);
        assertThat(rebound.context(A).uplink().generation()).isEqualTo(2);
        var resumed = transitions.startContext(rebound, A, next, at(101));
        assertThat(resumed.key(A_KEYS.uplink()).activatedAt()).isEqualTo(at(10));
    }

    @Test void rotationStagesNewMaterialSeparatelyAndRetiresOnlyOldKeys() {
        var bound = keyed(transitions, initial(transitions), A, A_KEYS, FIRST_A);
        var open = transitions.startContext(bound, A, FIRST_A, at(10));
        var used = transitions.recordProtectedTraffic(open, A, at(11), at(20), 2, 3);
        var nextKeys = new KeyPairIds("A-up-2", "A-down-2");
        var staged = transitions.prepareKeys(used, A, nextKeys, at(21));
        var installed = transitions.confirmInstallation(staged, A, nextKeys, FIRST_A, at(22));
        var activated = transitions.activateKeys(installed, A, nextKeys, FIRST_A, at(23));
        assertThat(activated.context(A).boundKeys()).isEqualTo(A_KEYS);
        assertThatThrownBy(() -> transitions.bindKeys(activated, A, nextKeys, FIRST_A, at(23))).hasMessageContaining("INVALID_SA_STATUS");
        assertThatThrownBy(() -> transitions.deactivateKeys(activated, A_KEYS, DeactivationReason.ROTATED, at(23))).hasMessageContaining("Detach");
        var stopped = transitions.stopContext(activated, A, at(24));
        var unbound = transitions.unbindKeys(stopped, A, at(25));
        var rebound = transitions.bindKeys(unbound, A, nextKeys, FIRST_A, at(26));
        var retired = transitions.deactivateKeys(rebound, A_KEYS, DeactivationReason.ROTATED, at(27));
        var restarted = transitions.startContext(retired, A, FIRST_A, at(28));
        assertThat(restarted.context(A).boundKeys()).isEqualTo(nextKeys);
        assertThat(restarted.key(A_KEYS.uplink()).status()).isEqualTo(KeyStatus.DEACTIVATED);
        assertThat(restarted.key(nextKeys.uplink()).activatedAt()).isEqualTo(at(23));
        assertThat(restarted.context(A).uplink().nextSequenceNumber()).isZero();
        assertThat(restarted.key(A_KEYS.uplink()).nextNonceCounter()).isEqualTo(2);
    }

    @Test void contextExpirationLeavesIndependentPreparedMaterialIntact() {
        var bound = keyed(transitions, initial(transitions), A, A_KEYS, FIRST_A);
        var stagedIds = new KeyPairIds("next-up", "next-down");
        var staged = transitions.prepareKeys(bound, A, stagedIds, at(11));
        assertThatThrownBy(() -> transitions.expireContext(staged, A, DeactivationReason.EXPIRED, at(12))).hasMessageContaining("No directional key has expired");
        var expired = transitions.expireContext(staged, A, DeactivationReason.EXPIRED, at(1010));
        assertThat(expired.context(A).status()).isEqualTo(AssociationStatus.UNKEYED);
        assertThat(expired.key(A_KEYS.uplink()).deactivationReason()).isEqualTo(DeactivationReason.EXPIRED);
        assertThat(expired.key(stagedIds.uplink())).isEqualTo(staged.key(stagedIds.uplink()));
    }

    @Test void cancellationAndDestructionAreTerminalAndIdsCannotBeRecycled() {
        var prepared = transitions.prepareKeys(initial(transitions), A, A_KEYS, at(1));
        assertThatThrownBy(() -> transitions.destroyKeys(prepared, A_KEYS, at(2))).hasMessageContaining("deactivated");
        var cancelled = transitions.deactivateKeys(prepared, A_KEYS, DeactivationReason.CANCELLED_PREPARATION, at(2));
        var destroyed = transitions.destroyKeys(cancelled, A_KEYS, at(3));
        assertThatThrownBy(() -> transitions.activateKeys(destroyed, A, A_KEYS, FIRST_A, at(10))).hasMessageContaining("INVALID_KEY_STATUS");
        assertThatThrownBy(() -> transitions.prepareKeys(destroyed, A, A_KEYS, at(4))).hasMessageContaining("cannot be reused");
        assertThatThrownBy(() -> transitions.deactivateKeys(cancelled, A_KEYS, DeactivationReason.POLICY_RETIRED, at(4))).hasMessageContaining("already retired");
    }

    @Test void invalidSaTransitionsAndBackdatedWritesAreRejected() {
        var initial = initial(transitions);
        assertThatThrownBy(() -> transitions.stopContext(initial, A, at(0))).hasMessageContaining("OPERATIONAL");
        assertThatThrownBy(() -> transitions.unbindKeys(initial, A, at(0))).hasMessageContaining("KEYED");
        assertThatThrownBy(() -> transitions.createContext(initial, A, "x", "y", at(0))).hasMessageContaining("already exists");
        var used = firstACompleted(transitions);
        assertThatThrownBy(() -> transitions.deleteContext(used, A, at(40))).hasMessageContaining("UNKEYED");
        assertThatThrownBy(() -> transitions.unbindKeys(used, A, at(39))).hasMessageContaining("must not precede");
        var active = keyed(transitions, initial, A, A_KEYS, FIRST_A);
        assertThatThrownBy(() -> transitions.activateKeys(active, A, A_KEYS, FIRST_A, at(11))).hasMessageContaining("INVALID_KEY_STATUS");
    }

    @Test void sameCommandsProduceEqualImmutableSnapshots() {
        assertThat(firstACompleted(transitions)).isEqualTo(firstACompleted(transitions));
        var state = firstACompleted(transitions);
        assertThatThrownBy(() -> state.contexts().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> state.keys().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test void deletedContextKeepsMaterialButMustBeRecreatedBeforeOnlineManagement() {
        var prepared = transitions.prepareKeys(initial(transitions), A, A_KEYS, at(1));
        var installed = transitions.confirmInstallation(prepared, A, A_KEYS, FIRST_A, at(10));
        var deleted = transitions.deleteContext(installed, A, at(11));
        assertThat(deleted.key(A_KEYS.uplink())).isEqualTo(installed.key(A_KEYS.uplink()));
        assertThatThrownBy(() -> transitions.activateKeys(deleted, A, A_KEYS, FIRST_A, at(12)))
                .hasMessageContaining("INVALID_SA_STATUS");
        assertThatThrownBy(() -> transitions.confirmInstallation(deleted, A, A_KEYS, FIRST_A, at(12)))
                .hasMessageContaining("INVALID_SA_STATUS");
        var recreated = transitions.createContext(deleted, A, "SA-A-up", "SA-A-down", at(12));
        assertThat(transitions.activateKeys(recreated, A, A_KEYS, FIRST_A, at(13)).key(A_KEYS.uplink()).status())
                .isEqualTo(KeyStatus.ACTIVE);
    }

    @Test void firstDirectionalExpiryRetiresThePairWithoutMislabelingTheOtherKeyAsExpired() {
        var shortLifetime = new LifecycleTransitions(limits(20, 2000, 100));
        var prepared = shortLifetime.prepareKeys(initial(shortLifetime), A, A_KEYS, at(0));
        var installed = shortLifetime.confirmInstallation(prepared, A, A_KEYS, FIRST_A, at(10));
        var activated = shortLifetime.activateKeys(installed, A, A_KEYS, FIRST_A, at(10));
        var laterKeys = new KeyPairIds("later-up", "later-down");
        var nextPrepared = transitions.prepareKeys(activated, A, laterKeys, at(11));
        var nextInstalled = transitions.confirmInstallation(nextPrepared, A, laterKeys, FIRST_A, at(12));
        var nextActivated = transitions.activateKeys(nextInstalled, A, laterKeys, FIRST_A, at(12));
        var directionalPair = new KeyPairIds(A_KEYS.uplink(), laterKeys.downlink());
        var bound = transitions.bindKeys(nextActivated, A, directionalPair, FIRST_A, at(13));
        var open = transitions.startContext(bound, A, FIRST_A, at(14));

        var expired = transitions.expireContext(open, A, DeactivationReason.EXPIRED, at(30));
        assertThat(expired.context(A).status()).isEqualTo(AssociationStatus.UNKEYED);
        assertThat(expired.key(directionalPair.uplink()).deactivationReason()).isEqualTo(DeactivationReason.EXPIRED);
        assertThat(expired.key(directionalPair.downlink()).deactivationReason()).isEqualTo(DeactivationReason.POLICY_RETIRED);
        assertThat(expired.key(laterKeys.uplink())).isEqualTo(open.key(laterKeys.uplink()));
    }

    @Test void rejectionOfOneDirectionalKeyLeavesBothKeysAndTheOriginalSnapshotUntouched() {
        var prepared = transitions.prepareKeys(initial(transitions), A, A_KEYS, at(1));
        var installed = transitions.confirmInstallation(prepared, A, A_KEYS, FIRST_A, at(10));
        var blockedDownlink = new SecurityConstraints(LIMITS.cryptoperiod(), LIMITS.maxMaterialAge(),
                LIMITS.maxReuseGap(), LIMITS.authorizedContexts(), Set.of(A_KEYS.downlink()));
        var blockedTransitions = new LifecycleTransitions(blockedDownlink);

        assertThatThrownBy(() -> blockedTransitions.activateKeys(installed, A, A_KEYS, FIRST_A, at(11)))
                .hasMessageContaining("KEY_BLOCKED");
        assertThat(installed.key(A_KEYS.uplink()).status()).isEqualTo(KeyStatus.PRE_ACTIVE);
        assertThat(installed.key(A_KEYS.downlink()).status()).isEqualTo(KeyStatus.PRE_ACTIVE);
        assertThat(installed.key(A_KEYS.uplink()).activatedAt()).isNull();
        assertThat(installed.asOf()).isEqualTo(at(10));
        assertThat(installed.context(A).status()).isEqualTo(AssociationStatus.UNKEYED);
    }
}
