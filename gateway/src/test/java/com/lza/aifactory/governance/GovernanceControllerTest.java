package com.lza.aifactory.governance;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "ai-factory.work-dir=${java.io.tmpdir}/ai-factory-governance-test",
        "ai-factory.pipeline-script=${user.dir}/src/test/resources/noop-pipeline.sh"
})
class GovernanceControllerTest {

    @Autowired
    private MockMvc mvc;

    private java.nio.file.Path workDir() {
        return java.nio.file.Path.of(System.getProperty("java.io.tmpdir"), "ai-factory-governance-test");
    }

    @Test
    void profilesAreListedDisplaySafe() throws Exception {
        // Decode UTF-8 explicitly: MockMvc's default getContentAsString uses
        // ISO-8859-1, which would mangle the zh-TW titles.
        String json = mvc.perform(get("/gateway/governance/profiles"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        org.junit.jupiter.api.Assertions.assertTrue(json.contains("compliance-patcher"));
        org.junit.jupiter.api.Assertions.assertTrue(json.contains("合規修復員"));
        // Internal capability vocabulary must NOT leak to the picker.
        org.junit.jupiter.api.Assertions.assertFalse(json.contains("merge:main"));
    }

    @Test
    void submitWithUnknownProfileIsRejected() throws Exception {
        String body = """
                {"source":"web","externalId":"GOV-BAD","title":"t","description":"d","maxAgents":1,
                 "governanceProfileId":"does-not-exist"}
                """;
        mvc.perform(post("/gateway/issue").contentType("application/json").content(body))
                .andExpect(status().isBadRequest());
    }

    @Test
    void submitWithValidProfilePersistsIt() throws Exception {
        String body = """
                {"source":"web","externalId":"GOV-OK","title":"t","description":"d","maxAgents":1,
                 "governanceProfileId":"compliance-patcher"}
                """;
        mvc.perform(post("/gateway/issue").contentType("application/json").content(body))
                .andExpect(status().isOk());
        String issue = java.nio.file.Files.readString(workDir().resolve("GOV-OK").resolve("issue.json"));
        org.junit.jupiter.api.Assertions.assertTrue(issue.contains("compliance-patcher"));
    }

    @Test
    void defaultProfileWhenUnspecified() throws Exception {
        String body = """
                {"source":"web","externalId":"GOV-DEF","title":"t","description":"d","maxAgents":1}
                """;
        mvc.perform(post("/gateway/issue").contentType("application/json").content(body))
                .andExpect(status().isOk());
        String issue = java.nio.file.Files.readString(workDir().resolve("GOV-DEF").resolve("issue.json"));
        org.junit.jupiter.api.Assertions.assertTrue(issue.contains("standard-app"));
    }

    @Test
    void eventsEndpointReturnsChainAndTamperFlag() throws Exception {
        // No events yet for a fresh task -> empty, not tampered.
        String body = """
                {"source":"web","externalId":"GOV-EVT","title":"t","description":"d","maxAgents":1}
                """;
        mvc.perform(post("/gateway/issue").contentType("application/json").content(body))
                .andExpect(status().isOk());
        mvc.perform(get("/gateway/governance/GOV-EVT/events"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("\"events\":[]")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("\"tampered\":false")));
    }

    @Test
    void promoteCheckRunsGatesAndReportsState() throws Exception {
        mvc.perform(post("/gateway/issue").contentType("application/json").content("""
                {"source":"web","mode":"new","externalId":"GOV-PC","title":"t","description":"d","maxAgents":1,
                 "governanceProfileId":"standard-app"}
                """))
                .andExpect(status().isOk());
        // standard-app + tests passed -> eligible.
        mvc.perform(post("/gateway/governance/GOV-PC/promote-check").contentType("application/json")
                        .content("{\"testStatus\":\"pass\",\"diffRef\":\"ai/x/final\",\"mode\":\"local\"}"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("DELIVERABLE_ELIGIBLE")));
        // tests failed -> blocked.
        mvc.perform(post("/gateway/governance/GOV-PC/promote-check").contentType("application/json")
                        .content("{\"testStatus\":\"fail\",\"diffRef\":\"ai/x/final\",\"mode\":\"local\"}"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("BLOCKED")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("tests-pass")));
    }

    @Test
    void promoteCheckUnknownTaskIs404() throws Exception {
        mvc.perform(post("/gateway/governance/__none__/promote-check").contentType("application/json")
                        .content("{\"testStatus\":\"pass\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void approveWritesMarker() throws Exception {
        mvc.perform(post("/gateway/issue").contentType("application/json").content("""
                {"source":"web","mode":"new","externalId":"GOV-APR","title":"t","description":"d","maxAgents":1}
                """))
                .andExpect(status().isOk());
        mvc.perform(post("/gateway/governance/GOV-APR/approve")).andExpect(status().isOk());
        org.junit.jupiter.api.Assertions.assertTrue(
                java.nio.file.Files.exists(workDir().resolve("GOV-APR").resolve("governance.approve")));
    }

    @Test
    void overrideRequiresRationaleTicketExpiry() throws Exception {
        mvc.perform(post("/gateway/issue").contentType("application/json").content("""
                {"source":"web","mode":"new","externalId":"GOV-OVR","title":"t","description":"d","maxAgents":1}
                """))
                .andExpect(status().isOk());
        // Missing fields -> 400.
        mvc.perform(post("/gateway/governance/GOV-OVR/override").param("rationale", "x"))
                .andExpect(status().isBadRequest());
        // Complete -> recorded.
        mvc.perform(post("/gateway/governance/GOV-OVR/override")
                        .param("rationale", "緊急").param("ticket", "INC-9").param("expiry", "2026-06-14T00:00:00Z"))
                .andExpect(status().isOk());
        org.junit.jupiter.api.Assertions.assertTrue(
                java.nio.file.Files.exists(workDir().resolve("GOV-OVR").resolve("governance.override")));
    }

    @Test
    void evidenceEndpointReportsCompletenessAndGep() throws Exception {
        String body = """
                {"source":"web","externalId":"GOV-EVD","title":"t","description":"d","maxAgents":1,
                 "governanceProfileId":"compliance-patcher"}
                """;
        mvc.perform(post("/gateway/issue").contentType("application/json").content(body))
                .andExpect(status().isOk());
        String json = mvc.perform(get("/gateway/governance/GOV-EVD/evidence"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        org.junit.jupiter.api.Assertions.assertTrue(json.contains("compliance-patcher"));
        org.junit.jupiter.api.Assertions.assertTrue(json.contains("gepMarkdown"));
        org.junit.jupiter.api.Assertions.assertTrue(json.contains("治理證據包"));
        // Critical profile with no artifacts yet -> not complete.
        org.junit.jupiter.api.Assertions.assertTrue(json.contains("\"complete\":false"));
    }
}
