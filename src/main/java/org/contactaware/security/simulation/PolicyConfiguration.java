package org.contactaware.security.simulation;

/** Reproducible policy parameters; horizon describes the permitted observation, not optimality. */
public interface PolicyConfiguration {
    String name();
    int horizon();
}
