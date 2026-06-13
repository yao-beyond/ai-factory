package com.lza.aifactory.governance;

import java.util.List;

/**
 * Result of checking that the implementer and the reviewer are genuinely
 * independent under a profile's requirements. "A different model reviewed it" is
 * too weak; this records the concrete dimensions that were checked. See
 * docs/design/governance-runtime.md §4.4.
 */
public record IndependenceCheck(
        boolean passed,
        String implementer,
        String implementerVendor,
        String reviewer,
        String reviewerVendor,
        List<String> failures) {
}
