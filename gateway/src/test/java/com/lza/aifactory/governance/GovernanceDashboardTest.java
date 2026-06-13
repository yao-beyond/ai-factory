package com.lza.aifactory.governance;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "ai-factory.work-dir=${java.io.tmpdir}/ai-factory-gov-dashboard-test",
        "ai-factory.pipeline-script=${user.dir}/src/test/resources/noop-pipeline.sh"
})
class GovernanceDashboardTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private GovernancePromotionService promotion;

    private java.nio.file.Path workDir() {
        return java.nio.file.Path.of(System.getProperty("java.io.tmpdir"), "ai-factory-gov-dashboard-test");
    }

    @Test
    void dashboardRendersControlSurface() throws Exception {
        String html = mvc.perform(get("/gateway/governance/dashboard"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        org.junit.jupiter.api.Assertions.assertTrue(html.contains("治理控制台"));
        org.junit.jupiter.api.Assertions.assertTrue(html.contains("Safe Catch"));
        org.junit.jupiter.api.Assertions.assertTrue(html.contains("政策衝突熱點"));
    }

    @Test
    void dashboardShowsSafeCatchAndFrictionFromEvents() throws Exception {
        // Submit a task and run a promote-check that blocks → emits gate-failed,
        // which the dashboard surfaces as a Safe Catch + friction entry.
        mvc.perform(post("/gateway/issue").contentType("application/json").content("""
                {"source":"web","mode":"new","externalId":"DASH-BLK","title":"t","description":"d","maxAgents":1,
                 "governanceProfileId":"compliance-patcher"}
                """)).andExpect(status().isOk());
        promotion.check("DASH-BLK", new PromoteCheckRequest("fail", "docs/ai/CODEX_REVIEW.md", "ai/x/final", "local"));

        String html = mvc.perform(get("/gateway/governance/dashboard"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        org.junit.jupiter.api.Assertions.assertTrue(html.contains("DASH-BLK"), "blocked task should appear");
        org.junit.jupiter.api.Assertions.assertTrue(html.contains("🛡️ Safe Catch"));
    }

    @Test
    void gepDownloadServesMarkdownAttachment() throws Exception {
        mvc.perform(post("/gateway/issue").contentType("application/json").content("""
                {"source":"web","mode":"new","externalId":"DASH-GEP","title":"t","description":"d","maxAgents":1,
                 "governanceProfileId":"compliance-patcher"}
                """)).andExpect(status().isOk());
        var res = mvc.perform(get("/gateway/governance/DASH-GEP/gep"))
                .andExpect(status().isOk())
                .andReturn().getResponse();
        org.junit.jupiter.api.Assertions.assertTrue(
                res.getHeader("Content-Disposition").contains("GEP-DASH-GEP.md"));
        String md = res.getContentAsString(StandardCharsets.UTF_8);
        org.junit.jupiter.api.Assertions.assertTrue(md.contains("治理證據包"));
    }

    @Test
    void gepUnknownTaskIs404() throws Exception {
        mvc.perform(get("/gateway/governance/__nope__/gep")).andExpect(status().isNotFound());
    }

    @Test
    void dashboardListsTaskAwaitingDeliveryApproval() throws Exception {
        // A task the real pipeline parked at the human-approval gate must surface in
        // the pending feed with one-click approve/reject buttons — that human gate
        // the AI cannot self-clear is the whole point of the control surface, yet it
        // had no test. The title carries an XSS probe to lock in that it rides
        // HTML-escaped data attributes, never a raw injection.
        mvc.perform(post("/gateway/issue").contentType("application/json").content("""
                {"source":"web","mode":"new","externalId":"DASH-PEND","title":"<script>x</script>修補",
                 "description":"d","maxAgents":1,"governanceProfileId":"compliance-patcher"}
                """)).andExpect(status().isOk());
        // The noop test pipeline leaves status untouched; stamp the gate state the
        // real pipeline would write so listTasks() reports AWAITING_DELIVERY_APPROVAL.
        java.nio.file.Path statusFile = workDir().resolve("DASH-PEND").resolve("status.txt");
        java.nio.file.Files.createDirectories(statusFile.getParent());
        java.nio.file.Files.writeString(statusFile,
                "STATUS=AWAITING_DELIVERY_APPROVAL\nMESSAGE=awaiting human delivery approval\n");

        String html = mvc.perform(get("/gateway/governance/dashboard"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        org.junit.jupiter.api.Assertions.assertTrue(
                html.contains("data-task-id=\"DASH-PEND\""),
                "pending task should expose approve/reject buttons");
        org.junit.jupiter.api.Assertions.assertTrue(html.contains("✅ 核准交付"), "approve button rendered");
        org.junit.jupiter.api.Assertions.assertTrue(html.contains("❌ 退回"), "reject button rendered");
        org.junit.jupiter.api.Assertions.assertFalse(
                html.contains("<script>x</script>"),
                "title must be HTML-escaped, not injected raw");
    }
}
