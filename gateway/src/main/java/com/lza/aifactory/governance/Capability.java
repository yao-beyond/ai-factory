package com.lza.aifactory.governance;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * A governed action an AI principal may attempt. Each capability carries the
 * {@link EnforcementLayer} where its denial can actually be enforced, so the
 * decision point can be honest about what it truly blocks today versus what is
 * only recorded as policy intent. The JSON wire name (e.g. {@code "merge:main"})
 * is the stable identifier used in governance-profiles.json.
 */
public enum Capability {
    READ_REPO("read:repo", EnforcementLayer.GATEWAY),
    WRITE_WORKSPACE("write:workspace", EnforcementLayer.EXECUTION_SANDBOX),
    RUN_TESTS("run:tests", EnforcementLayer.GATEWAY),
    PROPOSE_PR("propose:pr", EnforcementLayer.GATEWAY),
    MERGE_MAIN("merge:main", EnforcementLayer.GATEWAY),
    ACCESS_PROD_SECRETS("access:prod-secrets", EnforcementLayer.EXECUTION_SANDBOX),
    APPROVE_OWN_CHANGE("approve:own-change", EnforcementLayer.GATEWAY),
    MODIFY_POLICY("modify:policy", EnforcementLayer.GATEWAY),
    READ_DIFF("read:diff", EnforcementLayer.GATEWAY),
    READ_TEST_RESULTS("read:test-results", EnforcementLayer.GATEWAY),
    RUN_STATIC_ANALYSIS("run:static-analysis", EnforcementLayer.GATEWAY),
    EMIT_REVIEW_DECISION("emit:review-decision", EnforcementLayer.GATEWAY),
    WRITE_SOURCE("write:source", EnforcementLayer.EXECUTION_SANDBOX),
    APPROVE_OWN_REVIEW("approve:own-review", EnforcementLayer.GATEWAY);

    private static final Map<String, Capability> BY_WIRE =
            Stream.of(values()).collect(Collectors.toMap(Capability::wire, c -> c));

    private final String wire;
    private final EnforcementLayer layer;

    Capability(String wire, EnforcementLayer layer) {
        this.wire = wire;
        this.layer = layer;
    }

    @JsonValue
    public String wire() {
        return wire;
    }

    public EnforcementLayer layer() {
        return layer;
    }

    /**
     * Parse a wire name to a capability. Throws on an unknown name so a profile
     * referencing a capability the runtime does not understand fails fast at load
     * time rather than silently granting/denying nothing.
     */
    @JsonCreator
    public static Capability fromWire(String wire) {
        Capability c = BY_WIRE.get(wire);
        if (c == null) {
            throw new IllegalArgumentException("Unknown capability: '" + wire + "'");
        }
        return c;
    }
}
