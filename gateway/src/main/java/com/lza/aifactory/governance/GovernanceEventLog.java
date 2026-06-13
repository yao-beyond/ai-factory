package com.lza.aifactory.governance;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.lza.aifactory.service.SecretRedactor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Append-only, hash-chained governance event log — one {@code
 * governance-events.jsonl} per task. Appends are serialized per task so the
 * chain (seq + prevEventHash) cannot interleave. On read, the chain is verified
 * and a tamper flag plus the first broken seq are returned (the stream is not
 * refused, so the dashboard can still show what it has, with a warning). All
 * human-visible text is run through {@link SecretRedactor} on the way out.
 *
 * <p>See docs/design/governance-runtime.md §3.3.
 */
@Service
public class GovernanceEventLog {

    private static final Logger log = LoggerFactory.getLogger(GovernanceEventLog.class);
    private static final String GENESIS = "sha256:genesis";

    private final ObjectMapper canonical;
    private final Path workDir;
    private final ConcurrentHashMap<String, Object> locks = new ConcurrentHashMap<>();

    public GovernanceEventLog(ObjectMapper objectMapper,
                              @Value("${ai-factory.work-dir}") String workDir) {
        // Canonical writer: sorted map keys + ISO timestamps, so the same logical
        // event always serializes to the same bytes for hashing.
        this.canonical = objectMapper.copy()
                .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true)
                .configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false);
        this.workDir = Path.of(workDir);
    }

    /** Result of reading a task's chain: events plus a tamper verdict. */
    public record ReadResult(List<GovernanceEvent> events, boolean tampered, Long brokenSeq) {
    }

    private Path file(String taskId) {
        return workDir.resolve(taskId).resolve("governance-events.jsonl");
    }

    /**
     * Append one event, chaining it onto the task's existing log. Returns the
     * persisted event (with seq + integrity filled). Best-effort: an IO failure is
     * logged, not thrown, so governance logging never breaks the pipeline.
     */
    public GovernanceEvent append(String taskId, String profile, String policyHash,
                                  String eventType, GovernanceEvent.Actor actor,
                                  CapabilityDecision decision, Map<String, Object> extra) {
        Object lock = locks.computeIfAbsent(taskId, k -> new Object());
        synchronized (lock) {
            try {
                Path f = file(taskId);
                Files.createDirectories(f.getParent());
                List<JsonNode> existing = Files.exists(f) ? rawReadTrees(f) : List.of();
                long seq;
                String prevHash;
                if (existing.isEmpty()) {
                    seq = 0;
                    prevHash = GENESIS;
                } else {
                    JsonNode last = existing.get(existing.size() - 1);
                    seq = last.path("seq").asLong(existing.size() - 1) + 1;
                    prevHash = last.path("integrity").path("eventHash").asText(GENESIS);
                }

                GovernanceEvent draft = new GovernanceEvent(
                        "evt_" + UUID.randomUUID(), seq, eventType, Instant.now(), taskId,
                        profile, policyHash, actor,
                        decision == null ? null : GovernanceEvent.Decision.of(decision),
                        extra,
                        new GovernanceEvent.Integrity(prevHash, null, null));
                // Hash over the JSON TREE (not the re-serialized record) so the
                // verification on read covers EVERY field, including any an attacker
                // adds to the line — record deserialization would silently drop those.
                String eventHash = hashOf(canonical.valueToTree(draft));
                GovernanceEvent finalEvent = draft.withIntegrity(
                        new GovernanceEvent.Integrity(prevHash, eventHash, null));

                Files.writeString(f, canonical.writeValueAsString(finalEvent) + "\n",
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                return finalEvent;
            } catch (IOException | RuntimeException e) {
                log.warn("Failed to append governance event ({}) for task {}: {}",
                        eventType, taskId, e.getMessage());
                return null;
            }
        }
    }

    /** Read + verify the chain, returning redacted events and a tamper verdict. */
    public ReadResult read(String taskId) {
        Path f = file(taskId);
        if (!Files.exists(f)) return new ReadResult(List.of(), false, null);
        List<JsonNode> trees;
        try {
            trees = rawReadTrees(f);
        } catch (IOException e) {
            return new ReadResult(List.of(), true, 0L);
        }
        boolean tampered = false;
        Long brokenSeq = null;
        String expectedPrev = GENESIS;
        List<GovernanceEvent> events = new ArrayList<>(trees.size());
        for (JsonNode tree : trees) {
            JsonNode integ = tree.path("integrity");
            long seq = tree.path("seq").asLong(-1);
            String prev = integ.path("prevEventHash").asText(null);
            String stored = integ.path("eventHash").asText(null);
            if (!tampered) {
                if (prev == null || stored == null || !expectedPrev.equals(prev)) {
                    tampered = true;
                    brokenSeq = seq;
                } else if (!hashOf(tree).equals(stored)) {
                    // Verify over the whole tree: an added/edited field (even one the
                    // record would ignore) changes the hash and is caught here.
                    tampered = true;
                    brokenSeq = seq;
                } else {
                    expectedPrev = stored;
                }
            }
            try {
                events.add(redact(canonical.treeToValue(tree, GovernanceEvent.class)));
            } catch (Exception ex) {
                tampered = true;
                if (brokenSeq == null) brokenSeq = seq;
            }
        }
        return new ReadResult(events, tampered, brokenSeq);
    }

    private List<JsonNode> rawReadTrees(Path f) throws IOException {
        List<JsonNode> out = new ArrayList<>();
        for (String line : Files.readAllLines(f)) {
            if (line.isBlank()) continue;
            out.add(canonical.readTree(line));
        }
        return out;
    }

    /**
     * Canonical content hash of an event tree: every field is hashed EXCEPT the
     * integrity {@code eventHash}/{@code signature} (which are derived from it).
     * Working on the raw tree — not a deserialized record — is what makes added
     * unknown fields tamper-evident.
     */
    private String hashOf(JsonNode eventTree) {
        try {
            ObjectNode copy = eventTree.deepCopy();
            JsonNode integ = copy.get("integrity");
            if (integ instanceof ObjectNode integObj) {
                integObj.putNull("eventHash");
                integObj.putNull("signature");
            }
            return "sha256:" + sha256Hex(canonical.writeValueAsString(copy));
        } catch (Exception e) {
            // A line that can't be canonicalized is, by definition, not verifiable.
            return "sha256:UNVERIFIABLE";
        }
    }

    /** Redact human-visible free text (decision reason + extra string values). */
    private GovernanceEvent redact(GovernanceEvent e) {
        GovernanceEvent.Decision d = e.decision();
        GovernanceEvent.Decision rd = d == null ? null : new GovernanceEvent.Decision(
                d.capability(), d.result(), d.enforcementLayer(), d.matchedRule(),
                d.reason() == null ? null : SecretRedactor.redact(d.reason()));
        Map<String, Object> rx = null;
        if (e.extra() != null) {
            rx = new LinkedHashMap<>();
            for (Map.Entry<String, Object> en : e.extra().entrySet()) {
                Object v = en.getValue();
                rx.put(en.getKey(), v instanceof String s ? SecretRedactor.redact(s) : v);
            }
        }
        return new GovernanceEvent(e.eventId(), e.seq(), e.eventType(), e.occurredAt(),
                e.taskId(), e.profile(), e.policyHash(), e.actor(), rd, rx, e.integrity());
    }

    private static String sha256Hex(String s) {
        try {
            byte[] h = MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(h.length * 2);
            for (byte b : h) sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
