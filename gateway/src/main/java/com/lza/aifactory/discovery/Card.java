package com.lza.aifactory.discovery;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;
import java.util.Map;

/**
 * One starter card in the discovery library. A card is executable policy, not
 * just display text: its {@code submissionMode}, {@code ownerReceivesData},
 * {@code constraints} and (for handoff cards) {@code handoff} fields are passed
 * downstream as HARD constraints so the generated project can never POST, fetch,
 * store remotely, or imply the owner receives data. See
 * docs/design/discovery-stage.md.
 *
 * <p>The card library IS the v1 capability boundary; this type is loaded from
 * {@code discovery-cards.json} and validated at startup
 * ({@link DiscoveryCardLibrary}).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record Card(
        String id,
        int version,
        boolean enabled,
        List<String> audiences,
        List<String> intents,
        String formProjectType,
        String submissionMode,
        boolean ownerReceivesData,
        String title,
        String oneLiner,
        String draftTemplate,
        List<String> assumptions,
        List<String> included,
        List<String> excluded,
        Constraints constraints,
        Handoff handoff) {

    /** Structured capability constraints, passed downstream so recommend/dev stay in-scope. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Constraints(
            int actors,
            int workflows,
            List<String> dataSources,
            boolean externalIntegrations,
            boolean auth,
            boolean payment) {
    }

    /**
     * Extra hard constraints for {@code visitor_manual_handoff} cards: a static
     * page cannot receive submissions, so the generated code must never submit
     * over the network and must disclose that the visitor sends it manually.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Handoff(
            boolean networkSubmissionAllowed,
            List<String> allowedHandoffMethods,
            List<String> forbiddenBehaviors,
            String requiredUserFacingDisclosure) {
    }

    /** True when this card needs the visitor-manual-handoff honesty treatment. */
    public boolean isHandoff() {
        return "visitor_manual_handoff".equals(submissionMode);
    }

    /** The capability boundary, as a flat map, for passing to the downstream pipeline. */
    public Map<String, Object> capabilityBoundary() {
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("cardId", id);
        m.put("formProjectType", formProjectType);
        m.put("submissionMode", submissionMode);
        m.put("ownerReceivesData", ownerReceivesData);
        m.put("included", included);
        m.put("excluded", excluded);
        m.put("constraints", constraints);
        if (handoff != null) {
            m.put("handoff", handoff);
        }
        return m;
    }
}
