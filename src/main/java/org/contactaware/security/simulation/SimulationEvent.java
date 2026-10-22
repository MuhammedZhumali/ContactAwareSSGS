package org.contactaware.security.simulation;

import org.contactaware.security.*;
import java.time.*;
import java.util.Objects;

/** Append-only factual journal. Costs occur only on terminal primitive-step events. */
public record SimulationEvent(long sequence, Instant at, Type type, String operationId, SecurityAction action,
                              ContextId contextId, String contactId, KeyPairIds keyIds,
                              ResourceUsage usage, Duration usefulTime, long uplinkPayloadBytes, long downlinkPayloadBytes,
                              long uplinkFrames, long downlinkFrames, long createdKeys, String failureCode, String detail) {
    public enum Type {
        CONTACT_STARTED, CONTACT_ENDED, ACTION_STARTED, ACTION_COMPLETED, ACTION_FAILED,
        STEP_STARTED, STEP_COMPLETED, STEP_FAILED, STEP_INTERRUPTED,
        PAYLOAD_TRANSFERRED, PAYLOAD_REJECTED, KEY_EXPIRED, CONTEXT_STOPPED, CONTEXT_UNCERTAIN, SIMULATION_ENDED
    }
    public SimulationEvent {
        Objects.requireNonNull(at); Objects.requireNonNull(type);
        Objects.requireNonNull(usage); Objects.requireNonNull(usefulTime);
        Objects.requireNonNull(detail);
    }
}
