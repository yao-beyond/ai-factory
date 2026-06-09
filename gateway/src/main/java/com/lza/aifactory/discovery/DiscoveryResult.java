package com.lza.aifactory.discovery;

import java.util.List;
import java.util.Map;

/**
 * The outcome of finalizing a discovery session: a plain-language, buildable
 * request that prefills the existing homepage form, plus the structured
 * capability boundary that is passed downstream so recommend/dev stay in-scope.
 * Produced deterministically (no model call) in v1.
 */
public record DiscoveryResult(
        String cardId,
        String draftRequest,
        String title,
        String formProjectType,
        List<String> assumptions,
        List<String> excluded,
        Map<String, Object> capabilityBoundary) {
}
