package com.lza.aifactory.governance;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GovernanceEventLogTest {

    /** A mapper configured like Spring Boot's (JavaTimeModule, ISO dates). */
    private ObjectMapper bootMapper() {
        ObjectMapper m = new ObjectMapper().findAndRegisterModules();
        m.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        return m;
    }

    private GovernanceEventLog log(Path workDir) {
        return new GovernanceEventLog(bootMapper(), workDir.toString());
    }

    private GovernanceEvent.Actor actor() {
        return new GovernanceEvent.Actor("ai.claude.session.x", "implementer", "anthropic");
    }

    @Test
    void appendChainsAndVerifies(@org.junit.jupiter.api.io.TempDir Path tmp) throws Exception {
        Files.createDirectories(tmp.resolve("T1"));
        GovernanceEventLog l = log(tmp);
        l.append("T1", "compliance-patcher", "sha256:p", "profile-selected", actor(), null, null);
        CapabilityDecision d = new CapabilityDecision(CapabilityResult.BLOCKED, Capability.MERGE_MAIN,
                "implementer", EnforcementLayer.GATEWAY, "capabilities.implementer.deny.merge:main", "blocked");
        l.append("T1", "compliance-patcher", "sha256:p", "boundary-violation-blocked", actor(), d, null);

        GovernanceEventLog.ReadResult r = l.read("T1");
        assertEquals(2, r.events().size());
        assertFalse(r.tampered());
        assertEquals(0, r.events().get(0).seq());
        assertEquals(1, r.events().get(1).seq());
        assertNotNull(r.events().get(1).integrity().eventHash());
    }

    @Test
    void tamperingIsDetected(@org.junit.jupiter.api.io.TempDir Path tmp) throws Exception {
        Files.createDirectories(tmp.resolve("T2"));
        GovernanceEventLog l = log(tmp);
        l.append("T2", "p", "sha256:p", "e1", actor(), null, null);
        l.append("T2", "p", "sha256:p", "e2", actor(), null, null);
        Path f = tmp.resolve("T2").resolve("governance-events.jsonl");
        List<String> lines = Files.readAllLines(f);
        // Tamper with the first event's payload without recomputing its hash.
        lines.set(0, lines.get(0).replace("\"e1\"", "\"e1-tampered\""));
        Files.write(f, lines);

        GovernanceEventLog.ReadResult r = l.read("T2");
        assertTrue(r.tampered());
        assertEquals(0L, r.brokenSeq());
    }

    @Test
    void addingUnknownFieldIsDetectedAsTampering(@org.junit.jupiter.api.io.TempDir Path tmp) throws Exception {
        Files.createDirectories(tmp.resolve("T2b"));
        GovernanceEventLog l = log(tmp);
        l.append("T2b", "p", "sha256:p", "e1", actor(), null, null);
        Path f = tmp.resolve("T2b").resolve("governance-events.jsonl");
        List<String> lines = Files.readAllLines(f);
        // Inject an unknown field WITHOUT touching the stored hash. A record-based
        // verifier would drop it and miss the edit; tree-based hashing catches it.
        lines.set(0, lines.get(0).replaceFirst("\\{", "{\"injected\":\"evil\","));
        Files.write(f, lines);
        assertTrue(l.read("T2b").tampered());
    }

    @Test
    void readRedactsSecretsInReason(@org.junit.jupiter.api.io.TempDir Path tmp) throws Exception {
        Files.createDirectories(tmp.resolve("T3"));
        GovernanceEventLog l = log(tmp);
        CapabilityDecision d = new CapabilityDecision(CapabilityResult.BLOCKED, Capability.MERGE_MAIN,
                "implementer", EnforcementLayer.GATEWAY, "rule",
                "blocked; saw GITHUB_TOKEN=ghp_0123456789abcdef0123456789abcdef");
        l.append("T3", "p", "sha256:p", "boundary-violation-blocked", actor(), d, null);
        GovernanceEventLog.ReadResult r = l.read("T3");
        String reason = r.events().get(0).decision().reason();
        assertFalse(reason.contains("ghp_0123456789abcdef"));
        assertTrue(reason.contains("<redacted>"));
    }

    @Test
    void concurrentAppendsDoNotInterleave(@org.junit.jupiter.api.io.TempDir Path tmp) throws Exception {
        Files.createDirectories(tmp.resolve("T4"));
        GovernanceEventLog l = log(tmp);
        int n = 20;
        ExecutorService pool = Executors.newFixedThreadPool(8);
        CountDownLatch start = new CountDownLatch(1);
        for (int i = 0; i < n; i++) {
            final int idx = i;
            pool.submit(() -> {
                try {
                    start.await();
                    l.append("T4", "p", "sha256:p", "e", actor(), null, Map.of("i", idx));
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
            });
        }
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(20, TimeUnit.SECONDS));

        GovernanceEventLog.ReadResult r = l.read("T4");
        assertEquals(n, r.events().size());
        assertFalse(r.tampered());
        // Sequence numbers are a clean 0..n-1 with no gaps/dupes — chain held.
        for (int i = 0; i < n; i++) {
            assertEquals(i, r.events().get(i).seq());
        }
    }
}
