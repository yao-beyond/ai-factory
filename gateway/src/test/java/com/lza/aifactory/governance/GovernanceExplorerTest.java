package com.lza.aifactory.governance;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The GEP Evidence Explorer is a read-only view over the existing hash-chained
 * event log. These lock in: it renders the chain, surfaces the tamper verdict,
 * degrades on an empty/unknown task, and never lets event text break out as HTML
 * or leak a secret. See docs/design/gep-explorer.md.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "ai-factory.work-dir=${java.io.tmpdir}/ai-factory-gov-explorer-test",
        "ai-factory.pipeline-script=${user.dir}/src/test/resources/noop-pipeline.sh"
})
class GovernanceExplorerTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private GovernancePromotionService promotion;

    private Path eventsFile(String taskId) {
        return Path.of(System.getProperty("java.io.tmpdir"), "ai-factory-gov-explorer-test",
                taskId, "governance-events.jsonl");
    }

    private void submit(String externalId) throws Exception {
        mvc.perform(post("/gateway/issue").contentType("application/json").content(
                "{\"source\":\"web\",\"mode\":\"new\",\"externalId\":\"" + externalId + "\",\"title\":\"t\","
                        + "\"description\":\"d\",\"maxAgents\":1,\"governanceProfileId\":\"compliance-patcher\"}"))
                .andExpect(status().isOk());
    }

    private String getExplorer(String taskId) throws Exception {
        return mvc.perform(get("/gateway/governance/" + taskId + "/explorer"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    @Test
    void rendersChainAndVerifiedBanner() throws Exception {
        submit("EXP-OK");
        promotion.check("EXP-OK", new PromoteCheckRequest("pass", "docs/ai/CODEX_REVIEW.md", "ai/x/final", "local"));

        String html = getExplorer("EXP-OK");
        assertTrue(html.contains("EXP-OK"), "task id shown");
        assertTrue(html.contains("gate-passed") || html.contains("promotion-state-change"), "events rendered");
        assertTrue(html.contains("雜湊鏈驗證通過"), "verified integrity banner");
    }

    @Test
    void showsTamperBannerWhenChainBroken() throws Exception {
        submit("EXP-TMP");
        promotion.check("EXP-TMP", new PromoteCheckRequest("pass", "docs/ai/CODEX_REVIEW.md", "ai/x/final", "local"));

        // Corrupt a MIDDLE event line: changing any content field breaks the hash
        // for that seq onward (see GovernanceEventLog.read).
        Path f = eventsFile("EXP-TMP");
        List<String> lines = Files.readAllLines(f);
        assertTrue(lines.size() >= 2, "expected a multi-event chain");
        lines.set(1, lines.get(1).replace("compliance-patcher", "tampered-profile"));
        Files.write(f, lines);

        String html = getExplorer("EXP-TMP");
        assertTrue(html.contains("驗證失敗"), "tamper banner shown");
    }

    @Test
    void emptyChainDegradesGracefully() throws Exception {
        submit("EXP-EMPTY");   // no promote-check -> no events
        String html = getExplorer("EXP-EMPTY");
        assertTrue(html.contains("尚無治理事件"), "empty state, not a 500");
    }

    @Test
    void unknownTaskIs404() throws Exception {
        mvc.perform(get("/gateway/governance/__nope__/explorer")).andExpect(status().isNotFound());
    }

    @Test
    void eventTextIsHtmlEscapedNotInjected() throws Exception {
        submit("EXP-XSS");
        // Hand-write an event carrying an XSS probe in the decision reason. The chain
        // won't verify (that's fine — tampered events still render), and the reason
        // must come out escaped, never as a live tag.
        Files.writeString(eventsFile("EXP-XSS"),
                "{\"seq\":0,\"eventType\":\"gate-failed\",\"taskId\":\"EXP-XSS\",\"profile\":\"compliance-patcher\","
                        + "\"decision\":{\"result\":\"DENY\",\"reason\":\"<script>alert(1)</script>\"},"
                        + "\"integrity\":{\"prevEventHash\":\"sha256:genesis\",\"eventHash\":\"sha256:00\"}}\n");

        String html = getExplorer("EXP-XSS");
        assertFalse(html.contains("<script>alert(1)</script>"), "reason must be escaped, not injected");
        assertTrue(html.contains("&lt;script&gt;"), "escaped form present");
    }

    @Test
    void secretsInEventTextAreRedacted() throws Exception {
        submit("EXP-RED");
        Files.writeString(eventsFile("EXP-RED"),
                "{\"seq\":0,\"eventType\":\"gate-failed\",\"taskId\":\"EXP-RED\",\"profile\":\"compliance-patcher\","
                        + "\"decision\":{\"result\":\"DENY\",\"reason\":\"外洩 password=hunter2secret 請小心\"},"
                        + "\"integrity\":{\"prevEventHash\":\"sha256:genesis\",\"eventHash\":\"sha256:00\"}}\n");

        String html = getExplorer("EXP-RED");
        assertFalse(html.contains("hunter2secret"), "secret-shaped value must be redacted");
    }
}
