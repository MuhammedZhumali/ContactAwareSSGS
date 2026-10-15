package org.contactaware.security;

/** Identity of one spacecraft/ground-station security context. */
public record ContextId(String satelliteId, String groundStationId) implements Comparable<ContextId> {
    public ContextId {
        SecurityChecks.text(satelliteId, "satelliteId");
        SecurityChecks.text(groundStationId, "groundStationId");
    }

    @Override
    public int compareTo(ContextId other) {
        int satelliteOrder = satelliteId.compareTo(other.satelliteId);
        return satelliteOrder != 0 ? satelliteOrder : groundStationId.compareTo(other.groundStationId);
    }
}
