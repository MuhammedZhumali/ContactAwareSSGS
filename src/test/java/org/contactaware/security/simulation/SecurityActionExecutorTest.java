package org.contactaware.security.simulation;

import org.contactaware.security.*;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
import static org.contactaware.security.SecurityAction.*;
import static org.contactaware.security.simulation.HorizonWitness.*;

class SecurityActionExecutorTest {
    final SecurityActionExecutor executor = new SecurityActionExecutor(CONSTRAINTS);
    final LifecycleTransitions transitions = new LifecycleTransitions(CONSTRAINTS);

    @Test void admissionDoesNotInstallActivateOrBillAnythingBeforeConfirmation() {
        var initial = transitions.prepareKeys(initialState(), A, NEW_A, at(0));
        var install = operation("install", INSTALL, 0, A, NEW_A, "C1");
        var contact = window("C1", A, 0, 20);
        executor.admit(initial, install.intent(), INSTALL, contact, at(0));
        assertThat(initial.key(NEW_A.uplink()).installedOnBothPeers()).isFalse();
        var installed = executor.complete(initial, install.intent(), INSTALL, contact, at(4));
        assertThat(installed.key(NEW_A.uplink()).installedOnBothPeers()).isTrue();
        assertThat(installed.key(NEW_A.uplink()).status()).isEqualTo(KeyStatus.PRE_ACTIVE);
        assertThat(installed.context(A).boundKeys()).isEqualTo(OLD_A);
        var switched = executor.complete(installed, operation("switch", SWITCH, 4, A, NEW_A, "C1").intent(), SWITCH, contact, at(5));
        assertThat(switched.context(A).boundKeys()).isEqualTo(NEW_A);
        assertThat(switched.key(OLD_A.uplink()).status()).isEqualTo(KeyStatus.DEACTIVATED);
        assertThat(switched.context(A).status()).isEqualTo(AssociationStatus.KEYED);
        assertThatThrownBy(() -> executor.admit(switched, operation("reuse", REUSE, 5, A, null, "C1").intent(), REUSE, contact, at(5)))
                .isInstanceOfSatisfying(SecurityActionExecutor.Rejected.class, e -> assertThat(e.code()).isEqualTo("NO_PREVIOUS_SUCCESS"));
        var opened = executor.complete(switched, operation("open", OPEN, 5, A, null, "C1").intent(), OPEN, contact, at(6));
        assertThat(opened.context(A).status()).isEqualTo(AssociationStatus.OPERATIONAL);
    }

    @Test void compositeResolvesOnlyMissingPreparationAndInstallation() {
        var intent = operation("rotate", ROTATE, 0, A, NEW_A, "C1").intent();
        assertThat(executor.resolve(initialState(), intent)).containsExactly(PREPARE, INSTALL, SWITCH, OPEN);
        var prepared = transitions.prepareKeys(initialState(), A, NEW_A, at(0));
        assertThat(executor.resolve(prepared, intent)).containsExactly(INSTALL, SWITCH, OPEN);
        var installed = transitions.confirmInstallation(prepared, A, NEW_A, window("C1", A, 0, 20), at(1));
        assertThat(executor.resolve(installed, intent)).isEqualTo(List.of(SWITCH, OPEN));
    }

    @Test void failedSwitchLeavesOldBindingsAndCandidateUnchanged() {
        var state = transitions.prepareKeys(initialState(), A, NEW_A, at(0));
        var intent = operation("switch", SWITCH, 0, A, NEW_A, "C1").intent();
        assertThatThrownBy(() -> executor.complete(state, intent, SWITCH, window("C1", A, 0, 20), at(1)))
                .isInstanceOfSatisfying(SecurityActionExecutor.Rejected.class, e -> assertThat(e.code()).isEqualTo("KEY_NOT_INSTALLED"));
        assertThat(state.context(A).boundKeys()).isEqualTo(OLD_A);
        assertThat(state.key(OLD_A.uplink()).status()).isEqualTo(KeyStatus.ACTIVE);
        assertThat(state.key(NEW_A.uplink()).status()).isEqualTo(KeyStatus.PRE_ACTIVE);
    }

    @Test void unboundExpiryCannotBeUsedToDetachBoundKeysOrExpireBeforeDeadline() {
        var prepared = transitions.prepareKeys(initialState(), A, NEW_A, at(0));
        assertThatThrownBy(() -> transitions.expireUnboundKey(prepared, OLD_A.uplink(), at(70))).hasMessageContaining("bound key");
        assertThatThrownBy(() -> transitions.expireUnboundKey(prepared, NEW_A.uplink(), at(399))).hasMessageContaining("not expired");
        var expired = transitions.expireUnboundKey(prepared, NEW_A.uplink(), at(400));
        assertThat(expired.key(NEW_A.uplink()).status()).isEqualTo(KeyStatus.DEACTIVATED);
        assertThat(expired.key(NEW_A.downlink()).status()).isEqualTo(KeyStatus.PRE_ACTIVE);
        assertThat(expired.context(A)).isEqualTo(prepared.context(A));
    }
}
