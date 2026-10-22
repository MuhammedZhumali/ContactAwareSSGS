package org.contactaware.security.simulation;

/** Full-plan order used only after exact equality of all five objective components. */
public enum PlanTieBreak {
    CANONICAL,
    /** Claude audit's stable root-candidate order: group, until, original tuple. */
    CONTINUE_WAIT
}
