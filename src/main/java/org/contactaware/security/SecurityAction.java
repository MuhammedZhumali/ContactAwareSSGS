package org.contactaware.security;

public enum SecurityAction {
    PREPARE, INSTALL, SWITCH, OPEN, ESTABLISH, REUSE, ROTATE, STOP, EXPIRE_CONTEXT;

    public boolean needsKeyIds() {
        return this == PREPARE || this == INSTALL || this == SWITCH || this == ESTABLISH || this == ROTATE;
    }

    public boolean requiresContact() {
        return this == INSTALL || this == SWITCH || this == OPEN || this == ESTABLISH || this == REUSE || this == ROTATE;
    }
}
