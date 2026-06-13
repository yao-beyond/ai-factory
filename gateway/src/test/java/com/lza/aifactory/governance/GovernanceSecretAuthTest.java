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

/**
 * When an internal secret is configured, the human approve/reject endpoints
 * require it (so workspace code that lacks it cannot self-approve), AND the
 * dashboard must still be usable: it advertises SECRET_REQUIRED=true so the
 * one-click buttons prompt the operator for the secret instead of silently 403ing.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "ai-factory.work-dir=${java.io.tmpdir}/ai-factory-gov-secret-test",
        "ai-factory.pipeline-script=${user.dir}/src/test/resources/noop-pipeline.sh",
        "AIF_INTERNAL_SECRET=top-secret-operator-key"
})
class GovernanceSecretAuthTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private GovernanceTokens governanceTokens;

    private void submit(String id) throws Exception {
        mvc.perform(post("/gateway/issue").contentType("application/json").content(
                "{\"source\":\"web\",\"mode\":\"new\",\"externalId\":\"" + id
                + "\",\"title\":\"t\",\"description\":\"d\",\"maxAgents\":1}"))
                .andExpect(status().isOk());
    }

    @Test
    void dashboardAdvertisesSecretRequired() throws Exception {
        String html = mvc.perform(get("/gateway/governance/dashboard"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        org.junit.jupiter.api.Assertions.assertTrue(html.contains("var SECRET_REQUIRED = true"));
    }

    @Test
    void approveWithoutSecretIsForbiddenButWithSecretSucceeds() throws Exception {
        submit("SEC-APR");
        // No header -> 403 (workspace code lacking the secret cannot self-approve).
        mvc.perform(post("/gateway/governance/SEC-APR/approve"))
                .andExpect(status().isForbidden());
        // Operator-supplied secret -> approved.
        mvc.perform(post("/gateway/governance/SEC-APR/approve")
                        .header("X-AIF-Internal", "top-secret-operator-key"))
                .andExpect(status().isOk());
        org.junit.jupiter.api.Assertions.assertTrue(java.nio.file.Files.exists(
                java.nio.file.Path.of(System.getProperty("java.io.tmpdir"),
                        "ai-factory-gov-secret-test", "SEC-APR", "governance.approve")));
    }

    @Test
    void promoteCheckRequiresSecretToo() throws Exception {
        submit("SEC-PC");
        mvc.perform(post("/gateway/governance/SEC-PC/promote-check").contentType("application/json")
                        .content("{\"testStatus\":\"pass\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/gateway/governance/SEC-PC/promote-check").contentType("application/json")
                        .header("X-AIF-Internal", "top-secret-operator-key")
                        .content("{\"testStatus\":\"pass\"}"))
                .andExpect(status().isOk());
    }

    @Test
    void promoteCheckAcceptsScopedPromoteTokenNotTheOperatorSecret() throws Exception {
        submit("SEC-PT");
        // The pipeline authenticates with a per-task promote token (it never holds
        // the operator secret). A valid token authorizes promote-check.
        String token = governanceTokens.issue("SEC-PT", "promote-check");
        mvc.perform(post("/gateway/governance/SEC-PT/promote-check").contentType("application/json")
                        .header("X-AIF-Promote-Token", token)
                        .content("{\"testStatus\":\"pass\"}"))
                .andExpect(status().isOk());
        // A token minted for a DIFFERENT task must not work (scoped, no replay).
        String otherToken = governanceTokens.issue("SOME-OTHER", "promote-check");
        mvc.perform(post("/gateway/governance/SEC-PT/promote-check").contentType("application/json")
                        .header("X-AIF-Promote-Token", otherToken)
                        .content("{\"testStatus\":\"pass\"}"))
                .andExpect(status().isForbidden());
    }
}
