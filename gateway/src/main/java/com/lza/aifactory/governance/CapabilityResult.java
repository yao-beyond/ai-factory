package com.lza.aifactory.governance;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Outcome of a capability decision. {@link #ALLOWED_UNENFORCED} is the honest
 * middle state: policy denies the action, but the denial cannot be enforced at
 * this layer yet, so the runtime records it without pretending it was blocked.
 */
public enum CapabilityResult {
    ALLOWED("allowed"),
    ALLOWED_UNENFORCED("allowed-unenforced"),
    BLOCKED("blocked");

    private final String wire;

    CapabilityResult(String wire) {
        this.wire = wire;
    }

    @JsonValue
    public String wire() {
        return wire;
    }
}
