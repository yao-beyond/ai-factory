package com.lza.aifactory.governance;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Loads and validates the governance profile library from
 * {@code governance-profiles.json}. The library IS the policy source of truth, so
 * it is treated like code: a malformed or self-defeating profile (e.g. a
 * high-risk tier without independent review or human approval) FAILS STARTUP
 * rather than silently degrading governance. Mirrors {@link
 * com.lza.aifactory.discovery.DiscoveryCardLibrary}. See
 * docs/design/governance-runtime.md §3.1.
 */
@Component
public class GovernanceProfileLibrary {

    private static final Logger log = LoggerFactory.getLogger(GovernanceProfileLibrary.class);

    static final Set<String> RISK_TIERS = Set.of("standard", "high", "critical");
    static final Set<String> HUMAN_APPROVAL = Set.of("optional", "required");
    static final Set<String> ROLES = Set.of("implementer", "reviewer");
    static final Set<String> KNOWN_GATES = Set.of(
            "tests-pass", "independent-review-pass", "human-approval",
            "evidence-bundle-complete", "override-rationale-recorded");

    private final ObjectMapper objectMapper;
    private final String resourcePath;
    private List<GovernanceProfile> profiles = List.of();
    private Map<String, GovernanceProfile> byId = Map.of();

    @Autowired
    public GovernanceProfileLibrary(ObjectMapper objectMapper) {
        this(objectMapper, "governance-profiles.json");
    }

    /** Test seam: load from an arbitrary classpath resource. */
    GovernanceProfileLibrary(ObjectMapper objectMapper, String resourcePath) {
        this.objectMapper = objectMapper;
        this.resourcePath = resourcePath;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record Catalog(int schemaVersion, List<GovernanceProfile> profiles) {
    }

    @PostConstruct
    public void load() {
        Catalog catalog;
        try (InputStream in = new ClassPathResource(resourcePath).getInputStream()) {
            catalog = objectMapper.readValue(in, Catalog.class);
        } catch (IOException e) {
            // An unknown capability wire-name surfaces here as a JSON mapping error.
            throw new IllegalStateException("Cannot read governance profile library: " + resourcePath, e);
        }
        if (catalog == null || catalog.profiles() == null || catalog.profiles().isEmpty()) {
            throw new IllegalStateException("Governance profile library is empty: " + resourcePath);
        }
        if (catalog.schemaVersion() != 1) {
            throw new IllegalStateException(
                    "Unsupported governance schemaVersion " + catalog.schemaVersion() + " in " + resourcePath);
        }
        List<String> errors = new ArrayList<>();
        Map<String, GovernanceProfile> ids = new LinkedHashMap<>();
        List<GovernanceProfile> loaded = new ArrayList<>();
        for (GovernanceProfile p : catalog.profiles()) {
            validate(p, errors, ids.keySet());
            if (p != null && p.id() != null) {
                ids.put(p.id(), p);
            }
            if (p != null) {
                loaded.add(p);
            }
        }
        if (!errors.isEmpty()) {
            throw new IllegalStateException(
                    "Invalid governance profile library (" + errors.size() + " problem(s)):\n - "
                            + String.join("\n - ", errors));
        }
        this.profiles = List.copyOf(loaded);
        this.byId = Map.copyOf(ids);
        log.info("Loaded {} governance profiles ({} enabled)", profiles.size(),
                profiles.stream().filter(GovernanceProfile::enabled).count());
    }

    private void validate(GovernanceProfile p, List<String> errors, Set<String> seenIds) {
        if (p == null) {
            errors.add("null profile entry");
            return;
        }
        String id = p.id();
        String tag = "profile[" + id + "]";
        if (id == null || id.isBlank()) {
            errors.add("profile with blank id");
            return;
        }
        if (seenIds.contains(id)) errors.add(tag + ": duplicate id");
        if (p.version() < 1) errors.add(tag + ": version must be >= 1");
        if (isBlank(p.title())) errors.add(tag + ": missing title");
        if (isBlank(p.description())) errors.add(tag + ": missing description");
        if (p.riskTier() == null || !RISK_TIERS.contains(p.riskTier()))
            errors.add(tag + ": invalid riskTier '" + p.riskTier() + "'");
        if (p.humanApproval() == null || !HUMAN_APPROVAL.contains(p.humanApproval()))
            errors.add(tag + ": invalid humanApproval '" + p.humanApproval() + "'");

        // Both roles must be present; only known roles allowed; allow/deny disjoint.
        Map<String, GovernanceProfile.RoleCapabilities> caps =
                p.capabilities() == null ? Map.of() : p.capabilities();
        for (String role : ROLES) {
            if (!caps.containsKey(role)) errors.add(tag + ": missing capabilities for role '" + role + "'");
        }
        for (Map.Entry<String, GovernanceProfile.RoleCapabilities> e : caps.entrySet()) {
            if (!ROLES.contains(e.getKey())) {
                errors.add(tag + ": unknown role '" + e.getKey() + "'");
                continue;
            }
            GovernanceProfile.RoleCapabilities rc = e.getValue();
            if (rc == null) {
                errors.add(tag + ": null capabilities for role '" + e.getKey() + "'");
                continue;
            }
            for (Capability c : rc.allow()) {
                if (rc.deny().contains(c)) {
                    errors.add(tag + ": capability '" + c.wire() + "' is both allowed and denied for "
                            + e.getKey());
                }
            }
        }

        List<String> gates = p.gates() == null ? List.of() : p.gates().beforeDeliverable();
        for (String g : gates) {
            if (!KNOWN_GATES.contains(g)) errors.add(tag + ": unknown gate '" + g + "'");
        }

        // High/critical tiers must not be hollow: human approval and independent
        // review (unless an explicit review-bypass profile) are mandatory, and the
        // independence guards must be on.
        boolean elevated = "high".equals(p.riskTier()) || "critical".equals(p.riskTier());
        if (elevated) {
            if (!p.humanApprovalRequired())
                errors.add(tag + ": " + p.riskTier() + " tier requires humanApproval=required");
            if (!gates.contains("human-approval"))
                errors.add(tag + ": " + p.riskTier() + " tier must gate on human-approval");
            if (!gates.contains("evidence-bundle-complete"))
                errors.add(tag + ": " + p.riskTier() + " tier must gate on evidence-bundle-complete");
            if (!p.allowReviewBypass() && !gates.contains("independent-review-pass"))
                errors.add(tag + ": " + p.riskTier()
                        + " tier must gate on independent-review-pass (or set allowReviewBypass)");
            if (p.allowReviewBypass() && !gates.contains("override-rationale-recorded"))
                errors.add(tag + ": review-bypass profile must gate on override-rationale-recorded");
            GovernanceProfile.Independence ind = p.independence();
            if (ind == null) {
                errors.add(tag + ": " + p.riskTier() + " tier requires independence settings");
            } else {
                if (!ind.requireDistinctPrincipal())
                    errors.add(tag + ": " + p.riskTier() + " tier requires requireDistinctPrincipal=true");
                if (!ind.requireReviewerReadOnly())
                    errors.add(tag + ": " + p.riskTier() + " tier requires requireReviewerReadOnly=true");
                // A non-bypass high/critical profile reviewed by the same vendor that
                // wrote the change is not genuinely independent — require distinct vendor.
                if (!p.allowReviewBypass() && !ind.requireDistinctVendor())
                    errors.add(tag + ": " + p.riskTier()
                            + " tier (non-bypass) requires requireDistinctVendor=true");
            }
        }

        // protectedPaths must be non-blank entries (a blank glob is a config error
        // that would silently protect nothing once Phase 1b enforces them).
        if (p.protectedPaths() != null) {
            for (String path : p.protectedPaths()) {
                if (path == null || path.isBlank()) {
                    errors.add(tag + ": protectedPaths contains a blank entry");
                }
            }
        }
    }

    /** All profiles, enabled or not. */
    public List<GovernanceProfile> all() {
        return profiles;
    }

    /** Enabled profiles only — what the picker UI offers. */
    public List<GovernanceProfile> enabled() {
        return profiles.stream().filter(GovernanceProfile::enabled).toList();
    }

    /**
     * Canonical lookup by id; empty for unknown OR disabled ids, so a forged/stale
     * profile id from a client can never select a profile the library does not vouch
     * for (mirrors DiscoveryCardLibrary.enabledById).
     */
    public Optional<GovernanceProfile> enabledById(String id) {
        if (id == null) return Optional.empty();
        GovernanceProfile p = byId.get(id);
        if (p == null || !p.enabled()) return Optional.empty();
        return Optional.of(p);
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
