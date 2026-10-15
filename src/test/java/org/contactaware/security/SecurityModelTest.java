package org.contactaware.security;

import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.contactaware.security.SecurityFixtures.*;

class SecurityModelTest {
    @Test void invalidIdentifiersConstraintsAndKeySnapshotsAreRejected() {
        assertThatThrownBy(() -> new ContextId(" ", "GS")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new KeyPairIds("same", "same")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> limits(0, 100, 10)).hasMessageContaining("cryptoperiod");
        assertThatThrownBy(() -> limits(100, 0, 10)).hasMessageContaining("maxMaterialAge");
        assertThatThrownBy(() -> limits(100, 100, -1)).hasMessageContaining("maxReuseGap");
        assertThatThrownBy(() -> new KeyState("key", A, Direction.UPLINK, at(10), at(100), at(9),
                KeyStatus.ACTIVE, true, null, null, null, 0, null)).hasMessageContaining("activatedAt");
        assertThatThrownBy(() -> new KeyState("key", A, Direction.UPLINK, at(0), at(100), at(10),
                KeyStatus.ACTIVE, false, null, null, null, 0, null)).hasMessageContaining("installation");
        assertThatThrownBy(() -> new KeyState("key", A, Direction.UPLINK, at(0), at(100), null,
                KeyStatus.DEACTIVATED, false, null, null, null, 0, null)).hasMessageContaining("Deactivation");
    }

    @Test void scheduledActionsAreValidatedIntentAndNeverMutateState() {
        var state = SecurityState.empty(SATELLITE, at(0));
        var action = new ScheduledSecurityAction(SecurityAction.PREPARE, at(5), A, A_KEYS, "Prepare for the next authorized contact");
        assertThat(action.scheduledAt()).isEqualTo(at(5));
        assertThat(state.keys()).isEmpty();
        assertThatThrownBy(() -> new ScheduledSecurityAction(SecurityAction.PREPARE, at(5), A, null, "reason")).hasMessageContaining("key IDs");
        assertThatThrownBy(() -> new ScheduledSecurityAction(SecurityAction.REUSE, at(5), A, A_KEYS, "reason")).hasMessageContaining("key IDs");
        assertThatThrownBy(() -> new ScheduledSecurityAction(SecurityAction.STOP, at(5), A, null, " ")).hasMessageContaining("reason");
    }

    @Test void externalCollectionsCannotMutateConfigurationOrSnapshots() {
        var authorized = new HashSet<>(Set.of(A));
        var blocked = new HashSet<String>();
        var limits = new SecurityConstraints(Duration.ofHours(1), Duration.ofHours(2), Duration.ZERO, authorized, blocked);
        authorized.clear(); blocked.add("key");
        assertThat(limits.authorizedContexts()).containsExactly(A);
        assertThat(limits.blockedKeyIds()).isEmpty();
        var transitions = new LifecycleTransitions(LIMITS);
        var initial = initial(transitions);
        var mutableContexts = new HashMap<>(initial.contexts());
        var copied = new SecurityState(SATELLITE, at(0), mutableContexts, Map.of());
        mutableContexts.clear();
        assertThat(copied.contexts()).hasSize(2);
    }

    @Test void stateRejectsScopeIdentityCounterAndTimeCorruption() {
        var transitions = new LifecycleTransitions(LIMITS);
        var state = firstACompleted(transitions);
        var context = state.context(A);
        var sa = context.uplink();
        assertThatThrownBy(() -> new SecurityState("NORAD-1", state.asOf(), state.contexts(), state.keys())).hasMessageContaining("Mixed satellites");
        assertThatThrownBy(() -> new SecurityState(SATELLITE, at(0), state.contexts(), state.keys())).hasMessageContaining("timestamp");
        assertThatThrownBy(() -> new SecurityState(SATELLITE, at(40), state.contexts(), Map.of("wrong-id", state.key(A_KEYS.uplink())))).hasMessageContaining("identity");
        var incorrectCounter = new SecurityAssociation(sa.id(), A, Direction.UPLINK, sa.status(), sa.keyId(), sa.generation(), 0, true);
        assertThatThrownBy(() -> state.replace(at(40), context.closed(incorrectCounter, context.downlink()), List.of())).hasMessageContaining("counter mismatch");
        var wrongKey = new SecurityAssociation(sa.id(), A, Direction.UPLINK, sa.status(), A_KEYS.downlink(), sa.generation(), 3, true);
        assertThatThrownBy(() -> context.closed(wrongKey, context.downlink())).hasMessageContaining("distinct");
        assertThatThrownBy(() -> transitions.createContext(SecurityState.empty(SATELLITE, at(0)), A, "same", "same", at(0))).hasMessageContaining("distinct");
    }

    @Test void snapshotCannotKeepAnOpenSessionBeyondItsContactOrBeforeKeyActivation() {
        var transitions = new LifecycleTransitions(LIMITS);
        var bound = keyed(transitions, initial(transitions), A, A_KEYS, FIRST_A);
        var open = transitions.startContext(bound, A, FIRST_A, at(10));
        assertThatThrownBy(() -> transitions.prepareKeys(open, B, B_KEYS, at(41)))
                .hasMessageContaining("Close session");
        var stopped = transitions.stopContext(open, A, at(40));
        assertThat(transitions.prepareKeys(stopped, B, B_KEYS, at(41)).asOf()).isEqualTo(at(41));

        var key = open.key(A_KEYS.uplink());
        var activatedTooLate = new KeyState(key.id(), A, Direction.UPLINK, key.createdAt(), key.notAfter(),
                at(11), KeyStatus.ACTIVE, true, null, null, null, 0, null);
        assertThatThrownBy(() -> open.replace(at(11), null, List.of(activatedTooLate)))
                .hasMessageContaining("Key must be active when session opens");
    }

    @Test void snapshotRejectsTwoSimultaneousOperationalLinks() {
        var transitions = new LifecycleTransitions(LIMITS);
        var boundA = keyed(transitions, initial(transitions), A, A_KEYS, FIRST_A);
        var openA = transitions.startContext(boundA, A, FIRST_A, at(10));
        var contactB = contact("B-overlap", B, 10, 40);
        var boundB = keyed(transitions, initial(transitions), B, B_KEYS, contactB);
        var openB = transitions.startContext(boundB, B, contactB, at(10));
        var contexts = Map.of(A, openA.context(A), B, openB.context(B));
        var keys = new HashMap<>(openA.keys());
        keys.putAll(openB.keys());
        assertThatThrownBy(() -> new SecurityState(SATELLITE, at(10), contexts, keys))
                .hasMessageContaining("Only one simultaneous");
    }
}
