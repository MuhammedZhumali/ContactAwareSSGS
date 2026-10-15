package org.contactaware.security;

/** Distinct traffic-key identities for the two directions; never contains secret material. */
public record KeyPairIds(String uplink, String downlink) {
    public KeyPairIds {
        SecurityChecks.text(uplink, "uplink key id");
        SecurityChecks.text(downlink, "downlink key id");
        SecurityChecks.require(!uplink.equals(downlink), "Directional keys must have distinct IDs");
    }

    public String forDirection(Direction direction) {
        return switch (direction) { case UPLINK -> uplink; case DOWNLINK -> downlink; };
    }
}
