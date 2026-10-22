package org.contactaware.security.simulation;

import org.contactaware.security.*;
import java.time.*;
import java.util.Objects;

/** Append-only factual journal. Costs occur only on terminal primitive-step events. */
public record SimulationEvent(long sequence, Instant at, Type type, String operationId, SecurityAction action,
                              ContextId contextId, String contactId, KeyPairIds keyIds,
                              ResourceUsage usage, Duration usefulTime, long uplinkPayloadBytes, long downlinkPayloadBytes,
                              long uplinkFrames, long downlinkFrames, long createdKeys, String failureCode, String detail,
                              FrameEvidence frameEvidence) {
    public SimulationEvent(long sequence, Instant at, Type type, String operationId, SecurityAction action,
                           ContextId contextId, String contactId, KeyPairIds keyIds,
                           ResourceUsage usage, Duration usefulTime, long uplinkPayloadBytes, long downlinkPayloadBytes,
                           long uplinkFrames, long downlinkFrames, long createdKeys, String failureCode, String detail) {
        this(sequence, at, type, operationId, action, contextId, contactId, keyIds, usage, usefulTime,
                uplinkPayloadBytes, downlinkPayloadBytes, uplinkFrames, downlinkFrames, createdKeys, failureCode, detail, null);
    }
    /** Actual emitted ciphertext payload. Partial aborted frames are not delivered/useful traffic. */
    public record FrameEvidence(Instant from, Instant until, Direction direction, String keyId,
                                long frameBytes, long emittedBytes, boolean delivered) {
        public FrameEvidence {
            Objects.requireNonNull(from); Objects.requireNonNull(until); Objects.requireNonNull(direction);
            Objects.requireNonNull(keyId);
            if (until.isBefore(from) || frameBytes <= 0 || emittedBytes < 0 || emittedBytes > frameBytes)
                throw new IllegalArgumentException("Invalid factual frame evidence");
            if (delivered && emittedBytes != frameBytes) throw new IllegalArgumentException("Delivered frame must be complete");
        }
    }
    public enum Type {
        CONTACT_STARTED, CONTACT_ENDED, ACTION_STARTED, ACTION_COMPLETED, ACTION_FAILED,
        STEP_STARTED, STEP_COMPLETED, STEP_FAILED, STEP_INTERRUPTED,
        PAYLOAD_TRANSFERRED, PAYLOAD_REJECTED, KEY_EXPIRED, CONTEXT_STOPPED, CONTEXT_UNCERTAIN, SIMULATION_ENDED,
        KEY_DISCLOSED, DISCLOSURE_NO_TARGET, REVOCATION_RECEIVED_GROUND, REVOCATION_RECEIVED_SPACECRAFT, PAYLOAD_ABORTED
    }
    public SimulationEvent {
        Objects.requireNonNull(at); Objects.requireNonNull(type);
        Objects.requireNonNull(usage); Objects.requireNonNull(usefulTime);
        Objects.requireNonNull(detail);
    }
}
