package org.contactaware.security.simulation;

/** The only policy difference is the number of permitted future contacts. */
public record HorizonPolicy(String name, int horizon) implements PolicyConfiguration {
    public HorizonPolicy {
        if (!("NEXT".equals(name) && horizon == 1 || "MULTI".equals(name) && horizon >= 2))
            throw new IllegalArgumentException("NEXT requires H=1; MULTI requires H>=2");
    }
    public static HorizonPolicy next() { return new HorizonPolicy("NEXT", 1); }
    public static HorizonPolicy multi(int horizon) { return new HorizonPolicy("MULTI", horizon); }
}
