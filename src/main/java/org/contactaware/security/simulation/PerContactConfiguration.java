package org.contactaware.security.simulation;

import java.util.Objects;

/** Whether local preparation may start just before the next visible contact. */
public record PerContactConfiguration(PerContactPolicy.AdvancePreparation advancePreparation) implements PolicyConfiguration {
    public PerContactConfiguration { Objects.requireNonNull(advancePreparation); }
    @Override public String name() { return "PER_CONTACT"; }
    @Override public int horizon() { return 1; }
}
